package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code ModControllableAudio::processSRRAndBitcrushing}
 * (mod_controllable_audio.cpp: 269-371): bit-depth reduction (bitcrush) and sample-rate reduction
 * (decimation with linear down/up conversion). Both controls are bipolar Q31 params
 * (UNPATCHED_BITCRUSHING / UNPATCHED_SAMPLE_RATE_REDUCTION). The two SRR positions are uint32
 * fixed-point (4194304 == "1"), handled here with unsigned ops.
 */
public final class SrrBitcrush {
  private boolean sampleRateReductionOnLastTime;
  private int lowSampleRatePos; // uint32
  private int highSampleRatePos; // uint32
  private int lastSampleL;
  private int lastSampleR;
  private int grabbedSampleL;
  private int grabbedSampleR;
  private int lastGrabbedSampleL;
  private int lastGrabbedSampleR;

  /** C:269-272. */
  public static boolean isBitcrushingEnabled(int bitcrushParam) {
    return bitcrushParam >= -2113929216;
  }

  /** C:274-277. */
  public static boolean isSRREnabled(int srrParam) {
    return srrParam != -2147483648;
  }

  /**
   * @param buffer {@code [numSamples][2]} — {l, r}, modified in place
   * @param postFXVolume single-element array, reduced for heavy bitcrushing (as in the firmware)
   */
  public void process(
      int[][] buffer, int numSamples, int bitcrushParam, int srrParam, int[] postFXVolume) {
    int bitCrushMaskForSRR = 0xFFFFFFFF;
    boolean srrEnabled = isSRREnabled(srrParam);

    // Bitcrushing (C:285-305). positivePreset = (param + 2^31) as uint32, >> 29 → 0..7.
    if (isBitcrushingEnabled(bitcrushParam)) {
      int positivePreset = (bitcrushParam + Integer.MIN_VALUE) >>> 29;
      if (Integer.compareUnsigned(positivePreset, 4) > 0) {
        postFXVolume[0] >>= (positivePreset - 4);
      }
      if (!srrEnabled) {
        int mask = 0xFFFFFFFF << (19 + positivePreset);
        for (int i = 0; i < numSamples; i++) {
          buffer[i][0] &= mask;
          buffer[i][1] &= mask;
        }
      } else {
        bitCrushMaskForSRR = 0xFFFFFFFF << (18 + positivePreset);
      }
    }

    // Sample rate reduction (C:308-368).
    if (srrEnabled) {
      if (!sampleRateReductionOnLastTime) {
        sampleRateReductionOnLastTime = true;
        lastSampleL = lastSampleR = 0;
        grabbedSampleL = grabbedSampleR = 0;
        lowSampleRatePos = 0;
      }

      // 22 bits represent "1" (4194304). positivePreset = param + 2^31 as uint32.
      int positivePreset = srrParam + Integer.MIN_VALUE;
      int lowSampleRateIncrement = Functions.getExp(4194304, positivePreset >>> 3);
      int highSampleRateIncrement =
          Integer.divideUnsigned(0xFFFFFFFF, lowSampleRateIncrement >> 6) << 6;

      for (int i = 0; i < numSamples; i++) {
        // Convert down — if it's time to "grab" another sample for down-conversion.
        if (Integer.compareUnsigned(lowSampleRatePos, 4194304) < 0) {
          int strength2 = lowSampleRatePos;
          int strength1 = 4194303 - strength2;

          lastGrabbedSampleL = grabbedSampleL; // what was current is now last
          lastGrabbedSampleR = grabbedSampleR;
          grabbedSampleL =
              Functions.multiply_32x32_rshift32_rounded(lastSampleL, strength1 << 9)
                  + Functions.multiply_32x32_rshift32_rounded(buffer[i][0], strength2 << 9);
          grabbedSampleR =
              Functions.multiply_32x32_rshift32_rounded(lastSampleR, strength1 << 9)
                  + Functions.multiply_32x32_rshift32_rounded(buffer[i][1], strength2 << 9);
          grabbedSampleL &= bitCrushMaskForSRR;
          grabbedSampleR &= bitCrushMaskForSRR;

          lowSampleRatePos += lowSampleRateIncrement;
          // Re-sync the up-conversion spinner.
          highSampleRatePos =
              Functions.multiply_32x32_rshift32_rounded(
                      lowSampleRatePos & 4194303, highSampleRateIncrement << 8)
                  << 2;
        }
        lowSampleRatePos -= 4194304; // one step closer to grabbing the next sample
        lastSampleL = buffer[i][0];
        lastSampleR = buffer[i][1];

        // Convert up. strength2 = min(highSampleRatePos, 4194303) (unsigned).
        int strength2 =
            (Integer.compareUnsigned(highSampleRatePos, 4194303) < 0) ? highSampleRatePos : 4194303;
        int strength1 = 4194303 - strength2;
        buffer[i][0] =
            (Functions.multiply_32x32_rshift32_rounded(lastGrabbedSampleL, strength1 << 9)
                    + Functions.multiply_32x32_rshift32_rounded(grabbedSampleL, strength2 << 9))
                << 2;
        buffer[i][1] =
            (Functions.multiply_32x32_rshift32_rounded(lastGrabbedSampleR, strength1 << 9)
                    + Functions.multiply_32x32_rshift32_rounded(grabbedSampleR, strength2 << 9))
                << 2;

        highSampleRatePos += highSampleRateIncrement;
      }
    } else {
      sampleRateReductionOnLastTime = false;
    }
  }
}
