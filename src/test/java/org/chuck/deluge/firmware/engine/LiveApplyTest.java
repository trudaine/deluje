package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.PatchCable;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
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
        sound.fw2Sound.patchedParamValues[org.chuck.deluge.firmware2.Param.LOCAL_LPF_FREQ]);
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

  private static int countCables(FirmwareSound sound) {
    int n = 0;
    for (var d : sound.paramManager.getPatchCableSet().destinations) {
      n += d.cables.size();
    }
    return n;
  }
}
