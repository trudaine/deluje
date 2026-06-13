package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.ONE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SidechainRoutingParityTest {

  @BeforeEach
  void resetNoiseBefore() {
    FirmwareUtils.resetNoise();
  }

  @AfterEach
  void resetNoiseAfter() {
    FirmwareUtils.resetNoise();
  }

  @org.junit.jupiter.api.Disabled(
      "Sidechain routing uses old FirmwareAudioEngine — needs firmware2 port")
  @Test
  void sidechainSourceDoesNotDuckWithoutPatchCable() {
    GlobalSidechainBus.reset();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    FirmwareSound synth = createReceivingSynth(false);
    engine.sounds.add(synth);
    synth.triggerNote(60, 100);
    settle(engine, 10);

    double preHitPeak = peak(engine);

    FirmwareSound kick = createKick();
    engine.sounds.add(kick);
    kick.triggerNote(36, 127);
    engine.renderBlock(128);

    double postHitPeak = peak(engine);
    assertTrue(
        synth.globalSourceValues[PatchSource.SIDECHAIN.ordinal()] < 0,
        "sidechain patch source should go negative when ducking");
    assertTrue(
        postHitPeak / preHitPeak > 0.8,
        "audio should stay near steady-state when no sidechain cable is patched");
  }

  @org.junit.jupiter.api.Disabled(
      "Sidechain routing uses old FirmwareAudioEngine — needs firmware2 port")
  @Test
  void sidechainPatchCableDucksGlobalVolume() {
    GlobalSidechainBus.reset();
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    FirmwareSound synth = createReceivingSynth(true);
    engine.sounds.add(synth);
    synth.triggerNote(60, 100);
    settle(engine, 10);

    double preHitPeak = peak(engine);

    FirmwareSound kick = createKick();
    engine.sounds.add(kick);
    kick.triggerNote(36, 127);
    engine.renderBlock(128);

    double postHitPeak = peak(engine);
    assertTrue(
        synth.globalSourceValues[PatchSource.SIDECHAIN.ordinal()] < 0,
        "sidechain patch source should drive the routed ducking block");
    assertTrue(
        postHitPeak / preHitPeak < 0.35,
        "patched sidechain should drop the signal peak by at least 65%");
  }

  private static FirmwareSound createReceivingSynth(boolean patchSidechain) {
    FirmwareSound synth = new FirmwareSound();
    synth.oscTypes[0] = org.chuck.deluge.firmware2.Oscillator.OscType.SAW;
    synth.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = ONE;
    synth.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] = 100000000;
    synth.paramNeutralValues[Param.UNPATCHED_SIDECHAIN_SHAPE] = 0;
    synth.sidechainSend = 0;
    if (patchSidechain) {
      PatchCable cable = new PatchCable();
      cable.from = PatchSource.SIDECHAIN;
      cable.amount = ONE;
      synth.paramManager.getPatchCableSet().addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, cable);
    }
    return synth;
  }

  private static FirmwareSound createKick() {
    FirmwareSound kick = new FirmwareSound();
    kick.sidechainSend = ONE;
    kick.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    kick.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    kick.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    return kick;
  }

  private static void settle(FirmwareAudioEngine engine, int blocks) {
    for (int i = 0; i < blocks; i++) {
      engine.renderBlock(128);
    }
  }

  private static double peak(FirmwareAudioEngine engine) {
    double peak = 0.0;
    for (int i = 0; i < 128; i++) {
      peak = Math.max(peak, Math.abs(engine.masterBuffer[i].l / 2147483648.0));
    }
    return peak;
  }
}
