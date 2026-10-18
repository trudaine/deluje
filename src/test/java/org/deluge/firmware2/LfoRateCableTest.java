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

  @Test
  void rangeCableModulatesGlobalLfoRateCableDepth() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    Lfo.LfoConfig config = sound.lfoConfig[0];
    config.syncLevel = Lfo.SyncLevel.NONE;

    int knobOnly = sound.getGlobalLFOPhaseIncrement(config, Param.GLOBAL_LFO_FREQ_1);

    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.LFO_GLOBAL_2.ordinal();
    cable.amount = 0x30000000;
    sound.patchCableSet.addCable(Param.GLOBAL_LFO_FREQ_1, cable);
    sound.globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] = 0x40000000;
    int fullDepth = sound.getGlobalLFOPhaseIncrement(config, Param.GLOBAL_LFO_FREQ_1);

    // Sidechain attenuates the depth of (LFO_GLOBAL_2 -> GLOBAL_LFO_FREQ_1).
    Patcher.PatchCable rangeCable = new Patcher.PatchCable();
    rangeCable.source = PatchSource.SIDECHAIN.ordinal();
    rangeCable.amount = -0x20000000;
    sound.patchCableSet.addRangeCable(
        Param.GLOBAL_LFO_FREQ_1, PatchSource.LFO_GLOBAL_2.ordinal(), rangeCable);
    sound.globalSourceValues[PatchSource.SIDECHAIN.ordinal()] = Integer.MAX_VALUE;

    int attenuated = sound.getGlobalLFOPhaseIncrement(config, Param.GLOBAL_LFO_FREQ_1);
    assertTrue(
        attenuated > knobOnly && attenuated < fullDepth,
        "negative range cable must attenuate the global LFO rate cable depth");
  }

  @Test
  void globalLfosRenderBeforeSidechainUpdatesRateForNextBlock() {
    GlobalSidechainBus.reset();
    try {
      Sound sound = new Sound();
      Sound.initParams(sound.patchedParamValues);
      Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
      sound.lfoConfig[0].syncLevel = Lfo.SyncLevel.NONE;
      sound.lfoConfig[0].waveType = Lfo.LfoType.SAW;
      sound.globalLfos[0].phase = 0;

      // Sidechain ducks negative (< 0) on a hit, so a negative cable speeds up GLOBAL_LFO_FREQ_1.
      Patcher.PatchCable cable = new Patcher.PatchCable();
      cable.source = PatchSource.SIDECHAIN.ordinal();
      cable.amount = -0x30000000;
      sound.patchCableSet.addCable(Param.GLOBAL_LFO_FREQ_1, cable);

      int unmodulatedInc =
          Patcher.computeFinalValueForParam(
              Param.GLOBAL_LFO_FREQ_1, sound.patchedParamValues[Param.GLOBAL_LFO_FREQ_1]);

      // Register a sidechain hit for block 0. C sound.cpp:2395-2446 renders globalLFO1 BEFORE
      // sidechain, so block 0 advances LFO1 by unmodulatedInc, and block 1 sees the sidechain hit.
      GlobalSidechainBus.registerHit(Integer.MAX_VALUE);
      GlobalSidechainBus.beginAudioFrame();
      int[] buf = new int[256];
      sound.renderInternal(buf, 128, null);

      assertEquals(
          unmodulatedInc * 128,
          sound.globalLfos[0].phase,
          "Block 0 must advance LFO1 using pre-sidechain globalSourceValues (sound.cpp:2405, 2438)");
      assertTrue(
          sound.globalSourceValues[PatchSource.SIDECHAIN.ordinal()] < 0,
          "Sidechain must duck negative after LFO1 renders in block 0");

      GlobalSidechainBus.beginAudioFrame();
      int phaseAfterBlock0 = sound.globalLfos[0].phase;
      int expectedBlock1Inc =
          sound.getGlobalLFOPhaseIncrement(sound.lfoConfig[0], Param.GLOBAL_LFO_FREQ_1);
      assertTrue(
          expectedBlock1Inc > unmodulatedInc,
          "Block 1 LFO1 increment must reflect block 0's sidechain output");
      sound.renderInternal(buf, 128, null);
      assertEquals(
          phaseAfterBlock0 + expectedBlock1Inc * 128,
          sound.globalLfos[0].phase,
          "Block 1 must advance LFO1 at the sidechain-modulated rate");
    } finally {
      GlobalSidechainBus.reset();
    }
  }

  @Test
  void globalArpRateCableSpeedsUpUnsyncedArpeggiator() {
    Sound unmodulated = new Sound();
    Sound modulated = new Sound();
    for (Sound s : new Sound[] {unmodulated, modulated}) {
      Sound.initParams(s.patchedParamValues);
      Sound.setupAsBlankSynth(s.patchedParamValues, s, false);
      s.patchedParamValues[Param.GLOBAL_ARP_RATE] = 0;
      s.arpSettings.mode = Arpeggiator.ArpMode.ARP;
      s.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_NONE;
      s.arpSettings.gate = 0;
      s.lfoConfig[0].syncLevel = Lfo.SyncLevel.NONE;
      s.lfoConfig[0].waveType = Lfo.LfoType.SQUARE;
      s.globalLfos[0].phase = 0; // SQUARE at phase 0 outputs +INT_MAX
      s.patchedParamValues[Param.GLOBAL_LFO_FREQ_1] = Integer.MIN_VALUE;
    }

    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.LFO_GLOBAL_1.ordinal();
    cable.amount = 0x3FFFFFFF;
    modulated.patchCableSet.addCable(Param.GLOBAL_ARP_RATE, cable);

    unmodulated.triggerNote(60, 100);
    modulated.triggerNote(60, 100);

    int[] buf = new int[256];
    for (int block = 0; block < 4; block++) {
      unmodulated.renderInternal(buf, 128, null);
      modulated.renderInternal(buf, 128, null);
    }

    assertTrue(
        modulated.arpeggiator.gatePos > unmodulated.arpeggiator.gatePos,
        "A positive global cable to GLOBAL_ARP_RATE must advance unsynced arp gatePos faster (sound.cpp:2470)");
  }

  @Test
  void globalPatchingRoutesDelayAndModFxCables() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    sound.lfoConfig[0].syncLevel = Lfo.SyncLevel.NONE;
    sound.lfoConfig[0].waveType = Lfo.LfoType.SQUARE;
    sound.globalLfos[0].phase = 0; // SQUARE outputs +INT_MAX
    sound.patchedParamValues[Param.GLOBAL_LFO_FREQ_1] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.GLOBAL_DELAY_FEEDBACK] = 0;
    sound.patchedParamValues[Param.GLOBAL_DELAY_RATE] = 0;

    Patcher.PatchCable fbCable = new Patcher.PatchCable();
    fbCable.source = PatchSource.LFO_GLOBAL_1.ordinal();
    fbCable.amount = 0x30000000;
    sound.patchCableSet.addCable(Param.GLOBAL_DELAY_FEEDBACK, fbCable);

    Patcher.PatchCable rateCable = new Patcher.PatchCable();
    rateCable.source = PatchSource.LFO_GLOBAL_1.ordinal();
    rateCable.amount = 0x30000000;
    sound.patchCableSet.addCable(Param.GLOBAL_DELAY_RATE, rateCable);

    sound.triggerNote(60, 100);
    int[] buf = new int[256];
    sound.renderInternal(buf, 128, null);

    int uncabledFeedback = Patcher.computeFinalValueForParam(Param.GLOBAL_DELAY_FEEDBACK, 0);
    int uncabledDelayRate = Patcher.computeFinalValueForParam(Param.GLOBAL_DELAY_RATE, 0);
    assertTrue(
        sound.delayState.delayFeedbackAmount > uncabledFeedback,
        "Cabled GLOBAL_DELAY_FEEDBACK must reach delayState.delayFeedbackAmount (sound.cpp:2499)");
    assertTrue(
        sound.delayState.userDelayRate > uncabledDelayRate,
        "Cabled GLOBAL_DELAY_RATE must reach delayState.userDelayRate (sound.cpp:2504)");
  }
}
