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
          lpSvf.reset(lastLPFMode_ == FilterMode.OFF);
        }
        filterGain = lpSvf.configure(lpfFrequency, lpfResonance, lpfMode_, lpfMorph, filterGain);
      } else {
        if (getFilterFamily(lastLPFMode_) != FilterFamily.LP_LADDER) {
          lpLadder.reset(lastLPFMode_ == FilterMode.OFF);
        }
        filterGain = lpLadder.configure(lpfFrequency, lpfResonance, lpfMode_, lpfMorph, filterGain);
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
          hpLadder.reset(lastHPFMode_ == FilterMode.OFF);
        }
      } else {
        // invert the morph for the HPF so it goes high-band/notch-low
        int invertedMorph = ((1 << 29) - 1) - hpfMorph;
        filterGain =
            hpSvf.configure(hpfFrequency, hpfResonance, hpfMode_, invertedMorph, filterGain);
        if (lastHPFMode_ != hpfMode_) {
          hpSvf.reset(lastHPFMode_ == FilterMode.OFF);
        }
      }
      lastHPFMode_ = hpfMode_;
    } else {
      lastHPFMode_ = FilterMode.OFF;
    }

    return filterGain;
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
