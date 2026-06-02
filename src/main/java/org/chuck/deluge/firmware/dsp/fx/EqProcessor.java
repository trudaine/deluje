package org.chuck.deluge.firmware.dsp.fx;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of ModControllableAudio::doEQ (mod_controllable_audio.cpp): a stereo bass/treble shelving EQ
 * built from two one-pole splits with persistent state. Bass and treble amounts are bipolar Q31
 * params where 0 == flat (no change). Frequencies use the firmware defaults (the EQ-frequency knobs
 * aren't carried by the model), i.e. getExp(120000000, 0) for bass and getExp(700000000, 0) for
 * treble.
 */
public final class EqProcessor {
  private int withoutTrebleL, withoutTrebleR;
  private int bassOnlyL, bassOnlyR;

  private static final int BASS_FREQ = FirmwareUtils.getExp(120000000, 0);
  private static final int TREBLE_FREQ = FirmwareUtils.getExp(700000000, 0);

  /**
   * @param bassParam bipolar Q31 bass gain (0 = flat)
   * @param trebleParam bipolar Q31 treble gain (0 = flat)
   */
  public void process(StereoSample[] buffer, int numSamples, int bassParam, int trebleParam) {
    boolean doBass = bassParam != 0;
    boolean doTreble = trebleParam != 0;
    if (!doBass && !doTreble) {
      return;
    }

    // Bass: no-change at 0, fully off at -536870912.
    int positive = (bassParam >> 1) + 1073741824;
    int bassAmount = (Q31.multiply_32x32_rshift32_rounded(positive, positive) << 1) - 536870912;

    // Treble: no-change at 536870912.
    positive = (trebleParam >> 1) + 1073741824;
    int trebleAmount = Q31.multiply_32x32_rshift32_rounded(positive, positive) << 1;

    for (int i = 0; i < numSamples; i++) {
      StereoSample s = buffer[i];
      int inL = s.l, inR = s.r;
      int trebleOnlyL = 0, trebleOnlyR = 0;

      if (doTreble) {
        int distL = inL - withoutTrebleL;
        int distR = inR - withoutTrebleR;
        withoutTrebleL += Q31.multiply_32x32_rshift32(distL, TREBLE_FREQ) << 1;
        withoutTrebleR += Q31.multiply_32x32_rshift32(distR, TREBLE_FREQ) << 1;
        trebleOnlyL = inL - withoutTrebleL;
        trebleOnlyR = inR - withoutTrebleR;
        inL = withoutTrebleL; // input now has treble removed
        inR = withoutTrebleR;
      }

      if (doBass) {
        int distL = inL - bassOnlyL;
        int distR = inR - bassOnlyR;
        bassOnlyL += Q31.multiply_32x32_rshift32(distL, BASS_FREQ);
        bassOnlyR += Q31.multiply_32x32_rshift32(distR, BASS_FREQ);
      }

      if (doTreble) {
        inL += Q31.multiply_32x32_rshift32(trebleOnlyL, trebleAmount) << 3;
        inR += Q31.multiply_32x32_rshift32(trebleOnlyR, trebleAmount) << 3;
      }
      if (doBass) {
        inL += Q31.multiply_32x32_rshift32(bassOnlyL, bassAmount) << 3;
        inR += Q31.multiply_32x32_rshift32(bassOnlyR, bassAmount) << 3;
      }

      s.l = inL;
      s.r = inR;
    }
  }
}
