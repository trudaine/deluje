package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Arpeggiator;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;
import org.junit.jupiter.api.Test;

/**
 * C++ parity tests for {@link FirmwareKit}, {@link FirmwareSound#shouldRender}, and {@link
 * FirmwareAudioEngine}'s reverb auto-ducking across Kit drums.
 */
class FirmwareKitTailAndReverbDuckingTest {

  @Test
  void firmwareKitDrumDelayTailRendersAfterVoiceEnds() {
    // C kit.cpp:541-586 / sound.cpp:2178-2215: a SoundDrum with pending delay repeats
    // (delay.repeatsUntilAbandon != 0) keeps rendering after voices_.empty() becomes true.
    FirmwareKit kit = new FirmwareKit();
    FirmwareSound drum = kit.drumSounds.get(0);
    drum.oscTypes[0] = OscType.SINE;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    drum.paramNeutralValues[Param.GLOBAL_DELAY_FEEDBACK] = 0x60000000;
    drum.paramNeutralValues[Param.GLOBAL_DELAY_RATE] = 0;
    drum.delaySyncLevel = 9; // file 9 -> internal 8 (128th note, ~689 samples = ~6 blocks)
    drum.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] = Integer.MIN_VALUE; // instant release

    kit.triggerDrum(0, 127);

    int[] buf = new int[256];
    for (int b = 0; b < 4; b++) {
      kit.renderOutput(buf, 128, null);
    }

    kit.releaseDrum(0);
    for (int b = 0; b < 4 && !drum.fw2Sound.voices.isEmpty(); b++) {
      kit.renderOutput(buf, 128, null);
    }

    assertTrue(
        drum.fw2Sound.voices.isEmpty(), "Drum voice must have finished after instant release");
    assertTrue(
        drum.fw2Sound.delay.repeatsUntilAbandon != 0,
        "Per-drum delay must still have repeats pending");
    assertTrue(drum.shouldRender(128), "Drum with active delay tail must continue rendering");

    long tailEnergy = 0;
    for (int b = 0; b < 32; b++) {
      kit.renderOutput(buf, 128, null);
      for (int v : buf) {
        tailEnergy += Math.abs((long) v);
      }
    }
    assertTrue(
        tailEnergy > 0, "FirmwareKit must keep rendering per-drum delay tail after voice ends");
  }

  @Test
  void firmwareKitDrumArpeggiatorRendersAcrossGatedGaps() {
    // C sound.cpp:2178-2181: an arpeggiated SoundDrum keeps rendering while
    // getArp()->hasAnyInputNotesActive() is true, even between gated steps when voices_.empty().
    FirmwareKit kit = new FirmwareKit();
    FirmwareSound drum = kit.drumSounds.get(0);
    drum.oscTypes[0] = OscType.SINE;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    drum.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] = Integer.MIN_VALUE; // fast release
    drum.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    drum.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_NONE;
    drum.arpSettings.gate = -1_800_000_000; // short gate so voice ends before next step
    drum.paramNeutralValues[Param.GLOBAL_ARP_RATE] = 0x20000000;

    kit.triggerDrum(0, 127);

    int[] buf = new int[256];
    boolean sawEmptyVoicesDuringArpHold = false;
    boolean sawRetriggerAfterEmptyVoices = false;

    for (int b = 0; b < 80; b++) {
      kit.renderOutput(buf, 128, null);
      boolean empty = drum.fw2Sound.voices.isEmpty();
      if (empty && drum.fw2Sound.arpeggiator.hasAnyInputNotesActive()) {
        sawEmptyVoicesDuringArpHold = true;
      } else if (sawEmptyVoicesDuringArpHold && !empty) {
        sawRetriggerAfterEmptyVoices = true;
        break;
      }
    }

    assertTrue(
        sawEmptyVoicesDuringArpHold,
        "Short arp gate must produce a gap where voices are empty while arp input stays held");
    assertTrue(
        sawRetriggerAfterEmptyVoices,
        "FirmwareKit must keep rendering arp drum across gated voice gaps so next arp step fires");
  }

  @Test
  void firmwareKitLateTriggerAndMutedCheckSyncParams() {
    FirmwareKit kit = new FirmwareKit();
    FirmwareSound drum = kit.drumSounds.get(2);
    drum.oscTypes[0] = OscType.SINE;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;

    kit.muted = true;
    kit.triggerDrum(2, 127, 16);
    assertTrue(drum.fw2Sound.voices.isEmpty(), "Muted FirmwareKit must not trigger drum voices");

    kit.muted = false;
    kit.triggerDrum(2, 127, 16);
    assertEquals(1, drum.getActiveVoiceCount(), "Unmuted late drum trigger must start a voice");
    assertEquals(
        OscType.SINE,
        drum.fw2Sound.oscTypes[0],
        "triggerDrum(row, vel, samplesLate) must run syncParamsToFw2 on the FirmwareSound drum");

    kit.releaseAllNotes();
  }

  @Test
  void engineAutoDuckingInspectsKitDrumsAndSkipsAutomatedReverb() {
    // C audio_engine.cpp:1496-1534 / kit.cpp:1397-1417 / sound.cpp:2251-2257:
    // Kit SoundDrums compete in getThingWithMostReverb, while automated GLOBAL_REVERB_AMOUNT is
    // excluded.
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    FirmwareKit kit = new FirmwareKit();
    FirmwareSound snare = kit.drumSounds.get(1);
    snare.paramNeutralValues[Param.GLOBAL_REVERB_AMOUNT] = 1_200_000_000;
    snare.sidechain.syncType = 2; // DOTTED
    PatchCable cable = new PatchCable();
    cable.from = PatchSource.SIDECHAIN;
    cable.amount = 600_000_000;
    snare.paramManager.getPatchCableSet().addCable(Param.GLOBAL_VOLUME_POST_REVERB_SEND, cable);

    // Add a synth with an even higher reverb send, but mark its GLOBAL_REVERB_AMOUNT automated so
    // it must be ignored (C sound.cpp:2252).
    FirmwareSound automatedSynth = new FirmwareSound();
    automatedSynth.paramNeutralValues[Param.GLOBAL_REVERB_AMOUNT] = 1_800_000_000;
    automatedSynth.paramManager.recordParamValue(Param.GLOBAL_REVERB_AMOUNT, 1_800_000_000, 0);

    engine.sounds.add(kit);
    engine.sounds.add(automatedSynth);

    engine.renderBlock(128);

    assertNotEquals(
        0,
        engine.reverbSidechainVolumeInEffect,
        "Kit drum with highest non-automated reverb send must engage reverb auto-ducking");
    assertEquals(
        2,
        engine.reverbSidechain.syncType,
        "Reverb sidechain must copy syncType from the winning SoundDrum");
  }
}
