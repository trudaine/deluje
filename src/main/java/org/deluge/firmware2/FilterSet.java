package org.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code filter_set.cpp} and {@code filter_set.h}. Manages a low-pass
 * filter block (ladder/SVF) and high-pass filter block (ladder/SVF), handles parameter-based gain
 * compensation, and routes buffers in series (HP->LP, LP->HP) or parallel.
 */
public final class FilterSet {

  public enum FilterMode {
    TRANSISTOR_12DB,
    TRANSISTOR_24DB,
    TRANSISTOR_24DB_DRIVE,
    SVF_BAND,
    SVF_NOTCH,
    HPLADDER,
    OFF
  }

  public enum FilterFamily {
    LP_LADDER,
    SVF,
    HP_LADDER,
    NONE
  }

  public static FilterFamily getFilterFamily(FilterMode mode) {
    if (mode == FilterMode.OFF) {
      return FilterFamily.NONE;
    }
    if (mode == FilterMode.HPLADDER) {
      return FilterFamily.HP_LADDER;
    }
    if (mode == FilterMode.SVF_BAND || mode == FilterMode.SVF_NOTCH) {
      return FilterFamily.SVF;
    }
    return FilterFamily.LP_LADDER;
  }

  public final LpLadderFilter lpLadder = new LpLadderFilter();
  public final HpLadderFilter hpLadder = new HpLadderFilter();
  public final SVFilter lpSvf = new SVFilter();
  public final SVFilter hpSvf = new SVFilter();

  private FilterMode lpfMode_ = FilterMode.OFF;
  // C filter_set.h:46-47 — `FilterSet() { reset(); }` and `reset()` is
  // `memset(this, 0, sizeof(FilterSet))`, so lastLPFMode_/lastHPFMode_ start as enum value 0 ==
  // TRANSISTOR_12DB, NOT OFF. That difference is load-bearing: on the first setConfig the C sees
  // `lastMode != mode` and calls `filter.reset(lastMode == OFF)` => reset(FALSE), which leaves
  // dryFade at 0, i.e. FULLY WET from sample 0. Starting these at OFF instead made it reset(TRUE),
  // which sets dryFade=1/wetLevel=0 and fades the filter in over ~500 samples (filter.h:110-122) —
  // so the first ~11 ms of every note came out unfiltered. On the short single notes the
  // calibration corpus plays that dominates: it is why the CALIB HPF group scored a median of
  // 0.677 with nine NEGATIVE-cosine cases (dry signal bleeding through an otherwise correct
  // high-pass reads as an inverted spectral tilt). See §4.2sexsexagies.
  private FilterMode lastLPFMode_ = FilterMode.TRANSISTOR_12DB;
  // Tracks which LowPass union member (filter_set.h:32-36) last wrote the shared union memory,
  // because FilterMode.OFF leaves lpfilter untouched (filter_set.cpp:161-163).
  private FilterFamily lastActiveLPFamily_ = FilterFamily.LP_LADDER;
  private FilterMode hpfMode_ = FilterMode.OFF;
  private FilterMode lastHPFMode_ = FilterMode.TRANSISTOR_12DB;
  private FilterRoute routing_ = FilterRoute.HIGH_TO_LOW;

  private boolean LPFOn;
  private boolean HPFOn;

  private int[] tempRenderBuffer = new int[128 * 2]; // * 2 to accommodate stereo samples

  private void ensureTempRenderBufferCapacity(int len) {
    if (tempRenderBuffer.length < len) {
      tempRenderBuffer = new int[len];
    }
  }

  /**
   * C filter_set.h:47 — {@code void reset() { memset(this, 0, sizeof(FilterSet)); }}. Every field
   * goes to zero, which for the filters means state cleared AND {@code dryFade = 0} / {@code
   * wetLevel = 0} (i.e. the no-fade state, so the next configure renders fully wet), and for the
   * mode fields means enum value 0 — {@code TRANSISTOR_12DB}, not {@code OFF}.
   */
  public void reset() {
    lpLadder.reset(false);
    hpLadder.reset(false);
    lpSvf.reset(false);
    hpSvf.reset(false);
    // C filter_set.h:47 — memset(this, 0, sizeof(FilterSet)) clears fields that per-filter
    // resetFilter() intentionally leaves intact (lpladder.h:45-50, hpladder.h:44-48).
    lpLadder.l.noiseLastValue = 0;
    lpLadder.r.noiseLastValue = 0;
    hpLadder.l.hpfLastWorkingValue = 0;
    hpLadder.r.hpfLastWorkingValue = 0;
    lpLadder.dryFade = 0.0f;
    lpLadder.wetLevel = 0;
    hpLadder.dryFade = 0.0f;
    hpLadder.wetLevel = 0;
    lpSvf.dryFade = 0.0f;
    lpSvf.wetLevel = 0;
    hpSvf.dryFade = 0.0f;
    hpSvf.wetLevel = 0;
    lpfMode_ = FilterMode.TRANSISTOR_12DB;
    lastLPFMode_ = FilterMode.TRANSISTOR_12DB;
    lastActiveLPFamily_ = FilterFamily.LP_LADDER;
    hpfMode_ = FilterMode.TRANSISTOR_12DB;
    lastHPFMode_ = FilterMode.TRANSISTOR_12DB;
    routing_ = FilterRoute.HIGH_TO_LOW;
    LPFOn = false;
    HPFOn = false;
  }

  public boolean isOn() {
    return LPFOn || HPFOn;
  }

  public int setConfig(
      int lpfFrequency,
      int lpfResonance,
      FilterMode lpfmode,
      int lpfMorph,
      int hpfFrequency,
      int hpfResonance,
      FilterMode hpfmode,
      int hpfMorph,
      int filterGain,
      FilterRoute routing) {
    lpfMode_ = lpfmode;
    hpfMode_ = hpfmode;
    routing_ = routing;
    LPFOn = lpfMode_ != FilterMode.OFF;
    HPFOn = hpfMode_ != FilterMode.OFF;

    // Insanely, having changes happen in the small bytes too often causes rustling
    hpfResonance = (hpfResonance >> 21) << 21;

    if (LPFOn) {
      if ((lpfMode_ == FilterMode.SVF_BAND) || (lpfMode_ == FilterMode.SVF_NOTCH)) {
        if (getFilterFamily(lastLPFMode_) != FilterFamily.SVF) {
          // C filter_set.h:32-36 — LowPass is a union, so switching LP_LADDER -> SVF while
          // lastLPFMode_ != OFF shares the base Filter blend state (dryFade, wetLevel).
          if (lastLPFMode_ != FilterMode.OFF) {
            lpSvf.dryFade = lpLadder.dryFade;
            lpSvf.wetLevel = lpLadder.wetLevel;
          }
          lpSvf.reset(lastLPFMode_ == FilterMode.OFF);
        }
        filterGain = lpSvf.configure(lpfFrequency, lpfResonance, lpfMode_, lpfMorph, filterGain);
        lastActiveLPFamily_ = FilterFamily.SVF;
      } else {
        if (getFilterFamily(lastLPFMode_) != FilterFamily.LP_LADDER) {
          // C filter_set.h:32-36, lpladder.h:40,79-80, svf.h:39-44,48 — LowPass union overlaps
          // ladder.l.noiseLastValue with svf.l.band (+24) and ladder.r.noiseLastValue with
          // svf.c_low (+44). LpLadderState::reset() preserves noiseLastValue, and OFF leaves the
          // union untouched (filter_set.cpp:161-163), so switching from SVF (directly or via OFF)
          // exposes those SVF words to the ladder.
          if (lastActiveLPFamily_ == FilterFamily.SVF) {
            lpLadder.l.noiseLastValue = lpSvf.l.band;
            lpLadder.r.noiseLastValue = lpSvf.c_low;
          }
          if (lastLPFMode_ != FilterMode.OFF) {
            lpLadder.dryFade = lpSvf.dryFade;
            lpLadder.wetLevel = lpSvf.wetLevel;
          }
          lpLadder.reset(lastLPFMode_ == FilterMode.OFF);
        }
        filterGain = lpLadder.configure(lpfFrequency, lpfResonance, lpfMode_, lpfMorph, filterGain);
        lastActiveLPFamily_ = FilterFamily.LP_LADDER;
      }
      lastLPFMode_ = lpfMode_;
    } else {
      lastLPFMode_ = FilterMode.OFF;
    }

    // This changes the overall amplitude so that, with resonance on 50%, the amplitude is the
    // same as it was pre June 2017
    filterGain = Functions.multiply_32x32_rshift32(filterGain, 1720000000) << 1;

    // HPF
    if (HPFOn) {
      if (hpfMode_ == FilterMode.HPLADDER) {
        filterGain = hpLadder.configure(hpfFrequency, hpfResonance, hpfMode_, hpfMorph, filterGain);
        if (lastHPFMode_ != hpfMode_) {
          // C filter_set.cpp:173-181, filter_set.h:38-42 — HighPass is a union; any non-OFF,
          // non-HPLADDER mode writes hpfilter.svf, so switching to HPLADDER shares
          // dryFade/wetLevel.
          if (lastHPFMode_ != FilterMode.OFF) {
            hpLadder.dryFade = hpSvf.dryFade;
            hpLadder.wetLevel = hpSvf.wetLevel;
          }
          hpLadder.reset(lastHPFMode_ == FilterMode.OFF);
        }
      } else {
        // invert the morph for the HPF so it goes high-band/notch-low
        int invertedMorph = ((1 << 29) - 1) - hpfMorph;
        filterGain =
            hpSvf.configure(hpfFrequency, hpfResonance, hpfMode_, invertedMorph, filterGain);
        if (lastHPFMode_ != hpfMode_) {
          if (lastHPFMode_ == FilterMode.HPLADDER) {
            hpSvf.dryFade = hpLadder.dryFade;
            hpSvf.wetLevel = hpLadder.wetLevel;
          }
          hpSvf.reset(lastHPFMode_ == FilterMode.OFF);
        }
      }
      lastHPFMode_ = hpfMode_;
    } else {
      lastHPFMode_ = FilterMode.OFF;
    }

    return filterGain;
  }

  /**
   * C filter_set.cpp:69-99 — mono filter rendering across {@code [0, numSamples)} with unit stride.
   */
  public void renderLong(int[] monoBuf, int numSamples) {
    renderLong(monoBuf, 0, numSamples, 1);
  }

  /**
   * C filter_set.cpp:69-99 — mono filter rendering across {@code [startIdx, endIdx)} stepping by
   * {@code sampleIncrement}.
   */
  public void renderLong(int[] buf, int startIdx, int endIdx, int sampleIncrement) {
    switch (routing_) {
      case HIGH_TO_LOW:
        renderHPFLong(buf, startIdx, endIdx, sampleIncrement);
        renderLPFLong(buf, startIdx, endIdx, sampleIncrement);
        break;
      case LOW_TO_HIGH:
        renderLPFLong(buf, startIdx, endIdx, sampleIncrement);
        renderHPFLong(buf, startIdx, endIdx, sampleIncrement);
        break;
      case PARALLEL:
        int length = endIdx - startIdx;
        ensureTempRenderBufferCapacity(length);
        System.arraycopy(buf, startIdx, tempRenderBuffer, 0, length);
        renderHPFLong(tempRenderBuffer, 0, length, sampleIncrement);
        renderLPFLong(buf, startIdx, endIdx, sampleIncrement);
        for (int i = 0; i < length; i++) {
          buf[startIdx + i] += tempRenderBuffer[i];
        }
        break;
    }
  }

  private void renderLPFLong(int[] buf, int startIdx, int endIdx, int sampleIncrement) {
    if (LPFOn) {
      if ((lpfMode_ == FilterMode.SVF_BAND) || (lpfMode_ == FilterMode.SVF_NOTCH)) {
        lpSvf.filterMono(buf, startIdx, endIdx, sampleIncrement);
      } else {
        lpLadder.filterMono(buf, startIdx, endIdx, sampleIncrement);
      }
    }
  }

  private void renderHPFLong(int[] buf, int startIdx, int endIdx, int sampleIncrement) {
    if (HPFOn) {
      if (hpfMode_ == FilterMode.HPLADDER) {
        hpLadder.filterMono(buf, startIdx, endIdx, sampleIncrement);
      } else if ((hpfMode_ == FilterMode.SVF_BAND) || (hpfMode_ == FilterMode.SVF_NOTCH)) {
        // C filter_set.cpp:25-34 guards this branch on SVF_BAND/SVF_NOTCH.
        hpSvf.filterMono(buf, startIdx, endIdx, sampleIncrement);
      }
    }
  }

  public void renderLongStereo(int[] stereoBuf, int numSamples) {
    int endIdx = numSamples * 2;
    switch (routing_) {
      case HIGH_TO_LOW:
        renderHPFLongStereo(stereoBuf, 0, endIdx);
        renderLPFLongStereo(stereoBuf, 0, endIdx);
        break;
      case LOW_TO_HIGH:
        renderLPFLongStereo(stereoBuf, 0, endIdx);
        renderHPFLongStereo(stereoBuf, 0, endIdx);
        break;
      case PARALLEL:
        int length = endIdx;
        ensureTempRenderBufferCapacity(length);
        System.arraycopy(stereoBuf, 0, tempRenderBuffer, 0, length);
        renderHPFLongStereo(tempRenderBuffer, 0, length);
        renderLPFLongStereo(stereoBuf, 0, length);
        for (int i = 0; i < length; i++) {
          stereoBuf[i] += tempRenderBuffer[i];
        }
        break;
    }
  }

  private void renderLPFLongStereo(int[] buf, int startIdx, int endIdx) {
    if (LPFOn) {
      if ((lpfMode_ == FilterMode.SVF_BAND) || (lpfMode_ == FilterMode.SVF_NOTCH)) {
        lpSvf.filterStereo(buf, startIdx, endIdx);
      } else {
        lpLadder.filterStereo(buf, startIdx, endIdx);
      }
    }
  }

  private void renderHPFLongStereo(int[] buf, int startIdx, int endIdx) {
    if (HPFOn) {
      if (hpfMode_ == FilterMode.HPLADDER) {
        hpLadder.filterStereo(buf, startIdx, endIdx);
      } else if ((hpfMode_ == FilterMode.SVF_BAND) || (hpfMode_ == FilterMode.SVF_NOTCH)) {
        // C filter_set.cpp:36-43 guards this branch on SVF_BAND/SVF_NOTCH. It is NOT an unqualified
        // else: HPFOn is `hpfmode != OFF` (filter_set.cpp:139), so an LP-mode string carried in the
        // hpfMode slot ("12dB"/"24dB"/"24dBDrive" — which is what every ALLSYN instrument has)
        // leaves HPFOn true while matching no render branch. On hardware that is an INERT
        // high-pass; rendering it through the SVF here would apply a filter the firmware never
        // applies. See docs/FIDELITY_GAP_ANALYSIS.md §4.2nonies.
        hpSvf.filterStereo(buf, startIdx, endIdx);
      }
    }
  }
}
