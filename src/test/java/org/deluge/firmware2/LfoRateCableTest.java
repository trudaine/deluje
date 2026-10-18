package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * An unsynced global LFO runs at paramFinalValues[GLOBAL_LFO_FREQ_n] (sound.cpp:2723-2729), which
 * includes patch cables whose destination is the LFO rate. Java recomputed the rate from the knob
 * alone, so such a cable had no effect.
 */
class LfoRateCableTest {

  @Test
  void aCableToTheLfoRateChangesTheUnsyncedIncrementLikeTheC() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    Lfo.LfoConfig config = sound.lfoConfig[0];
    config.syncLevel = Lfo.SyncLevel.NONE;

    int knobOnly = sound.getGlobalLFOPhaseIncrement(config, Param.GLOBAL_LFO_FREQ_1);
    assertEquals(
        Patcher.computeFinalValueForParam(
            Param.GLOBAL_LFO_FREQ_1, sound.patchedParamValues[Param.GLOBAL_LFO_FREQ_1]),
        knobOnly);

    // LFO2 (a global source) → LFO1 rate.
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.LFO_GLOBAL_2.ordinal();
    cable.amount = 0x20000000;
    sound.patchCableSet.addCable(Param.GLOBAL_LFO_FREQ_1, cable);
    sound.globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] = 0x40000000;

    int cabled = sound.getGlobalLFOPhaseIncrement(config, Param.GLOBAL_LFO_FREQ_1);
    // patcher.cpp:240-272: cables, then the preset value, both plain adds; then the exp curve.
    int combo =
        Functions.multiply_32x32_rshift32(0x40000000, 0x20000000)
            + Functions.multiply_32x32_rshift32(
                sound.patchedParamValues[Param.GLOBAL_LFO_FREQ_1],
                Functions.getParamRange(Param.GLOBAL_LFO_FREQ_1));
    assertEquals(
        Functions.getFinalParameterValueExp(
            Functions.getParamNeutralValue(Param.GLOBAL_LFO_FREQ_1), combo),
        cabled);
    assertTrue(cabled > knobOnly, "a positive cable speeds the LFO up");
  }
}
