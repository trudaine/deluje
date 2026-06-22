package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.PatchCable;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.InstrumentClip;
import org.deluge.playback.Song;
import org.junit.jupiter.api.Test;

/**
 * FirmwareFactory.applyModelToLiveSound — the dialogs' live-apply path: re-mapping an edited model
 * onto an already-built (and possibly playing) sound must take effect and must be idempotent
 * (re-applies don't duplicate patch cables or disturb playing voices).
 */
public class LiveApplyTest {

  private static SynthTrackModel model() {
    SynthTrackModel m = new SynthTrackModel("live");
    m.setOsc1Type("SAW");
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(20000f);
    m.setVolume(0.3f);
    m.addClip(new ClipModel("c", 8, 16));
    return m;
  }

  private static FirmwareSound build(SynthTrackModel m) {
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = FirmwareFactory.createSong(p);
    return (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;
  }

  @Test
  void editedParamsReachTheLiveSound() {
    SynthTrackModel m = model();
    FirmwareSound sound = build(m);
    int lpfBefore = sound.paramNeutralValues[Param.LOCAL_LPF_FREQ];

    m.setLpfFreq(400f); // dialog edit: close the filter
    m.setVolume(0.9f);
    FirmwareFactory.applyModelToLiveSound(m, sound);

    assertTrue(
        sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] < lpfBefore,
        "LPF knob should drop after closing the cutoff");
    // And the per-block fw2 sync forwards it (this is what the audio thread runs every block).
    sound.syncParamsToFw2();
    assertEquals(
        sound.paramNeutralValues[Param.LOCAL_LPF_FREQ],
        sound.fw2Sound.patchedParamValues[org.deluge.firmware2.Param.LOCAL_LPF_FREQ]);
  }

  @Test
  void reapplyDoesNotDuplicatePatchCables() {
    SynthTrackModel m = model();
    m.addPatchCable(new PatchCable("lfo1", "lpfFrequency", 0.5f, PatchCable.Polarity.BIPOLAR));
    FirmwareSound sound = build(m);

    int cablesAfterBuild = countCables(sound);
    assertEquals(1, cablesAfterBuild, "factory build should map the one model cable");

    for (int i = 0; i < 5; i++) {
      FirmwareFactory.applyModelToLiveSound(m, sound);
    }
    assertEquals(1, countCables(sound), "re-applies must not duplicate cables");

    m.addPatchCable(new PatchCable("envelope2", "volume", 0.3f, PatchCable.Polarity.UNIPOLAR));
    FirmwareFactory.applyModelToLiveSound(m, sound);
    assertEquals(2, countCables(sound), "a newly added model cable must appear exactly once");
  }

  @Test
  void liveApplyKeepsPlayingVoicesAlive() {
    SynthTrackModel m = model();
    FirmwareSound sound = build(m);
    sound.triggerNote(60, 100);
    assertEquals(1, sound.getActiveVoiceCount());

    m.setLpfFreq(800f);
    FirmwareFactory.applyModelToLiveSound(m, sound);

    assertEquals(1, sound.getActiveVoiceCount(), "live-apply must not kill playing voices");
  }

  @Test
  void arpModelFieldsReachFw2Settings() {
    SynthTrackModel m = model();
    m.setArp(
        org.deluge.model.ArpModel.defaultConfig().toBuilder()
            .active(true)
            .noteMode("RAND")
            .seqLength(12)
            .velSpread(0.5f)
            .chordPolyphony(3)
            .rate(2.0f)
            .build());
    FirmwareSound sound = build(m);

    assertEquals(org.deluge.firmware2.Arpeggiator.ArpMode.ARP, sound.arpSettings.mode, "arp on");
    assertEquals(org.deluge.firmware2.Arpeggiator.ArpNoteMode.RANDOM, sound.arpSettings.noteMode);
    assertEquals(12, sound.arpSettings.sequenceLength);
    assertEquals(3, sound.arpSettings.chordPolyphony);
    // velSpread 0.5 → user 25 → raw 25 × 85899345 (value_scaling.cpp:18)
    assertEquals(25 * 85899345, sound.arpSettings.spreadVelocity);
    // noteProbability unset (0) must mean "always play", not "never"
    assertEquals(50 * 85899345, sound.arpSettings.noteProbability);
    assertEquals(2.0f, sound.arpRateMultiplier, 1e-6f);
  }

  @Test
  void lfoDepthAndTargetSynthesizeAPatchCable() {
    SynthTrackModel m = model();
    m.setLfo(
        0,
        new org.deluge.model.LfoModel(
            2.0f, org.deluge.model.LfoType.SINE, 0.5f, "Filter", false, 0, 0));
    FirmwareSound sound = build(m);

    boolean found = false;
    for (var d : sound.paramManager.getPatchCableSet().destinations) {
      if (d.paramId == org.deluge.firmware2.Param.LOCAL_LPF_FREQ) {
        for (var cable : d.cables) {
          if (cable.from == org.deluge.firmware.modulation.patch.PatchSource.LFO_GLOBAL_1) {
            found = true;
            assertEquals((int) (0.5f * 2147483647.0), cable.getAmount());
          }
        }
      }
    }
    assertTrue(found, "LFO1 depth/target should synthesize an LFO_GLOBAL_1 → LPF_FREQ cable");
  }

  @Test
  void lfoRateKnobInversionMatchesCurve() {
    for (double hz : new double[] {0.1, 0.5, 1.0, 2.0, 8.0, 20.0}) {
      int knob = FirmwareFactory.lfoRateKnobFromHz(hz);
      long inc =
          org.deluge.firmware2.Functions.getExp(
                  121739, org.deluge.firmware2.Functions.patchCombineExpStep(0, knob, 1073741824))
              & 0xFFFFFFFFL;
      double gotHz = inc * 44100.0 / 4294967296.0;
      assertEquals(hz, gotHz, hz * 0.02, "knob inversion should reproduce the rate within 2%");
    }
  }

  private static int countCables(FirmwareSound sound) {
    int n = 0;
    for (var d : sound.paramManager.getPatchCableSet().destinations) {
      n += d.cables.size();
    }
    return n;
  }

  private static KitTrackModel kitModel() {
    KitTrackModel m = new KitTrackModel("live-kit");
    org.deluge.model.SoundDrum sd = new org.deluge.model.SoundDrum("drum0");
    sd.setLpfFreq(20000f);
    sd.setLpfRes(0.5f);
    sd.setSamplePath("808 Kick.wav");
    m.addDrum(sd);
    m.addClip(new ClipModel("c", 8, 16));
    return m;
  }

  private static FirmwareKit buildKit(KitTrackModel m) {
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = FirmwareFactory.createSong(p);
    return (FirmwareKit) ((InstrumentClip) s.clips.get(0)).sound;
  }

  @Test
  void kitEditedParamsReachTheLiveSound() {
    KitTrackModel m = kitModel();
    FirmwareKit kit = buildKit(m);
    FirmwareSound drumSound = kit.drumSounds.get(0);
    int lpfBefore = drumSound.paramNeutralValues[Param.LOCAL_LPF_FREQ];

    ((SoundDrum) m.getDrums().get(0)).setLpfFreq(400f); // dialog edit: close the filter
    FirmwareFactory.applyModelToLiveSound(m, kit);

    assertTrue(
        drumSound.paramNeutralValues[Param.LOCAL_LPF_FREQ] < lpfBefore,
        "LPF knob should drop after closing the cutoff");
    // And the per-block fw2 sync forwards it
    drumSound.syncParamsToFw2();
    assertEquals(
        drumSound.paramNeutralValues[Param.LOCAL_LPF_FREQ],
        drumSound.fw2Sound.patchedParamValues[org.deluge.firmware2.Param.LOCAL_LPF_FREQ]);
  }
}
