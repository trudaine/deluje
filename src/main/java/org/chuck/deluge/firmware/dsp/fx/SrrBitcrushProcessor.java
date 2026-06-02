package org.chuck.deluge.firmware.dsp.fx;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of ModControllableAudio::processSRRAndBitcrushing (mod_controllable_audio.cpp): bit-depth
 * reduction (bitcrush) and sample-rate reduction (decimation with linear up/down conversion). The
 * two control values are bipolar Q31 params (knob 0..1 maps to [Integer.MIN_VALUE, MAX_VALUE]),
 * matching the firmware's UNPATCHED_BITCRUSHING / UNPATCHED_SAMPLE_RATE_REDUCTION.
 */
public final class SrrBitcrushProcessor {
  // Sample-rate-reduction state (persists across blocks).
  private int lastSampleL, lastSampleR;
  private int grabbedSampleL, grabbedSampleR;
  private int lastGrabbedSampleL, lastGrabbedSampleR;
  private long lowSampleRatePos; // 22-bit fixed point where 4194304 == "1"
  private long highSampleRatePos;
  private boolean srrOnLastTime = false;

  public static boolean isBitcrushingEnabled(int bitcrushParam) {
    return bitcrushParam >= -2113929216;
  }

  public static boolean isSRREnabled(int srrParam) {
    return srrParam != Integer.MIN_VALUE;
  }

  /**
   * @param bitcrushParam bipolar Q31 bitcrush amount
   * @param srrParam bipolar Q31 sample-rate-reduction amount
   * @param postFXVolume single-element array, reduced for heavy bitcrushing (as in the firmware)
   */
  public void process(StereoSample[] buffer, int numSamples, int bitcrushParam, int srrParam,
      int[] postFXVolume) {
    int bitCrushMaskForSRR = 0xFFFFFFFF;
    boolean srrEnabled = isSRREnabled(srrParam);

    // ── Bitcrushing ──
    if (isBitcrushingEnabled(bitcrushParam)) {
      int positivePreset = (int) (((bitcrushParam + 2147483648L) >> 29) & 0xFFFFFFFFL);
      if (positivePreset > 4) {
        postFXVolume[0] >>= (positivePreset - 4);
      }
      if (!srrEnabled) {
        int mask = 0xFFFFFFFF << (19 + positivePreset);
        for (int i = 0; i < numSamples; i++) {
          buffer[i].l &= mask;
          buffer[i].r &= mask;
        }
      } else {
        bitCrushMaskForSRR = 0xFFFFFFFF << (18 + positivePreset);
      }
    }

    // ── Sample rate reduction ──
    if (srrEnabled) {
      if (!srrOnLastTime) {
        srrOnLastTime = true;
        lastSampleL = lastSampleR = 0;
        grabbedSampleL = grabbedSampleR = 0;
        lowSampleRatePos = 0;
      }

      // 22 bits represent "1" (4194304).
      long positivePreset = (srrParam + 2147483648L);
      int lowSampleRateIncrement = FirmwareUtils.getExp(4194304, (int) (positivePreset >> 3));
      int highSampleRateIncrement =
          (int) (((0xFFFFFFFFL / (Integer.toUnsignedLong(lowSampleRateIncrement) >> 6)) << 6));

      for (int i = 0; i < numSamples; i++) {
        StereoSample sample = buffer[i];

        // Convert down: grab a new sample when the down-conversion spinner wraps.
        if (lowSampleRatePos < 4194304) {
          int strength2 = (int) lowSampleRatePos;
          int strength1 = 4194303 - strength2;

          lastGrabbedSampleL = grabbedSampleL;
          lastGrabbedSampleR = grabbedSampleR;
          grabbedSampleL = Q31.multiply_32x32_rshift32_rounded(lastSampleL, strength1 << 9)
              + Q31.multiply_32x32_rshift32_rounded(sample.l, strength2 << 9);
          grabbedSampleR = Q31.multiply_32x32_rshift32_rounded(lastSampleR, strength1 << 9)
              + Q31.multiply_32x32_rshift32_rounded(sample.r, strength2 << 9);
          grabbedSampleL &= bitCrushMaskForSRR;
          grabbedSampleR &= bitCrushMaskForSRR;

          lowSampleRatePos += Integer.toUnsignedLong(lowSampleRateIncrement);
          highSampleRatePos = ((long) Q31.multiply_32x32_rshift32_rounded(
                  (int) (lowSampleRatePos & 4194303), highSampleRateIncrement << 8)) << 2;
        }
        lowSampleRatePos -= 4194304;
        lastSampleL = sample.l;
        lastSampleR = sample.r;

        // Convert up (linear interpolation between the two most recent grabbed samples).
        int strength2 = (int) Math.min(highSampleRatePos, 4194303L);
        int strength1 = 4194303 - strength2;
        sample.l = (Q31.multiply_32x32_rshift32_rounded(lastGrabbedSampleL, strength1 << 9)
                + Q31.multiply_32x32_rshift32_rounded(grabbedSampleL, strength2 << 9)) << 2;
        sample.r = (Q31.multiply_32x32_rshift32_rounded(lastGrabbedSampleR, strength1 << 9)
                + Q31.multiply_32x32_rshift32_rounded(grabbedSampleR, strength2 << 9)) << 2;

        highSampleRatePos += Integer.toUnsignedLong(highSampleRateIncrement);
      }
    } else {
      srrOnLastTime = false;
    }
  }
}
