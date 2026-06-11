package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code filter.h} template base class. Handles dry/wet blending,
 * frequency curving, and stereo/mono buffer rendering wrappers.
 */
public abstract class Filter {
  public int fc;
  public float dryFade = 0.0f;
  public int wetLevel = Functions.ONE_Q31;
  public int tannedFrequency;
  public int divideBy1PlusTannedFrequency;

  // blend buffer matches deluge buffer size, grows dynamically if needed
  protected int[] blendBuffer = new int[128 * 2];

  protected final void ensureBlendBufferCapacity(int len) {
    if (blendBuffer.length < len) {
      blendBuffer = new int[len];
    }
  }

  public void reset(boolean fade) {
    resetFilter();
    if (fade) {
      dryFade = 1.0f;
      wetLevel = 0;
    }
  }

  public abstract void resetFilter();

  public final void updateBlend() {
    dryFade *= 0.99f;
    wetLevel = (int) (Functions.ONE_Q31 * (1.0f - dryFade));
  }

  public final void curveFrequency(int frequency) {
    tannedFrequency = Functions.instantTan(Functions.lshiftAndSaturate(frequency, 5));
    double denom = (double) (Functions.ONE_Q16 + (tannedFrequency >> 1));
    divideBy1PlusTannedFrequency = (int) (288230376151711744.0 / denom);
    fc =
        Functions.multiply_32x32_rshift32_rounded(tannedFrequency, divideBy1PlusTannedFrequency)
            << 4;
  }

  public final int configure(
      int frequency, int resonance, FilterSet.FilterMode mode, int morph, int filterGain) {
    int shiftedMorph = Functions.lshiftAndSaturate(morph, 2);
    return setConfig(frequency, resonance, mode, shiftedMorph, filterGain);
  }

  public abstract int setConfig(
      int frequency, int resonance, FilterSet.FilterMode mode, int morph, int filterGain);

  public abstract void doFilter(int[] buf, int startIdx, int endIdx, int step);

  public abstract void doFilterStereo(int[] buf, int startIdx, int endIdx);

  public final void filterMono(int[] buf, int startIdx, int endIdx, int step) {
    if (dryFade < 0.001f) {
      doFilter(buf, startIdx, endIdx, step);
    } else {
      int len = endIdx - startIdx;
      ensureBlendBufferCapacity(len);
      System.arraycopy(buf, startIdx, blendBuffer, 0, len);
      doFilter(buf, startIdx, endIdx, step);
      int currentDryIdx = 0;
      for (int i = startIdx; i < endIdx; i += step) {
        int wet = Functions.multiply_32x32_rshift32(buf[i], wetLevel);
        buf[i] =
            Functions.multiply_accumulate_32x32_rshift32_rounded(
                    wet, blendBuffer[currentDryIdx], Functions.ONE_Q31 - wetLevel)
                << 1;
        currentDryIdx += step;
        updateBlend();
      }
    }
  }

  public final void filterStereo(int[] buf, int startIdx, int endIdx) {
    if (dryFade < 0.001f) {
      doFilterStereo(buf, startIdx, endIdx);
    } else {
      int len = endIdx - startIdx;
      ensureBlendBufferCapacity(len);
      System.arraycopy(buf, startIdx, blendBuffer, 0, len);
      doFilterStereo(buf, startIdx, endIdx);
      int currentDryIdx = 0;
      for (int i = startIdx; i < endIdx; i += 2) {
        int wetL = Functions.multiply_32x32_rshift32(buf[i], wetLevel);
        buf[i] =
            Functions.multiply_accumulate_32x32_rshift32_rounded(
                    wetL, blendBuffer[currentDryIdx], Functions.ONE_Q31 - wetLevel)
                << 1;

        int wetR = Functions.multiply_32x32_rshift32(buf[i + 1], wetLevel);
        buf[i + 1] =
            Functions.multiply_accumulate_32x32_rshift32_rounded(
                    wetR, blendBuffer[currentDryIdx + 1], Functions.ONE_Q31 - wetLevel)
                << 1;

        currentDryIdx += 2;
        updateBlend();
      }
    }
  }
}
