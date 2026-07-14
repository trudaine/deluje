package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Stutterer;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link FirmwareSound#beginStutter}/{@link FirmwareSound#endStutter}, the wrapper methods a
 * gold-knob press (modKnobMode==6, knob index 1) calls in {@code SwingHardwareTopPanel} — C: {@code
 * ModControllableAudio::beginStutter}/{@code endStutter} (mod_controllable_audio.cpp:1299-1329).
 */
public class FirmwareSoundStutterTest {

  @Test
  void beginAndEndStutterTogglesStuttererState() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("Stutter Test Synth");
    synth.addClip(new ClipModel("c", 1, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

    assertFalse(Stutterer.GLOBAL.isStuttering(fs.fw2Sound));

    Stutterer.Config cfg = new Stutterer.Config();
    cfg.useSongStutter = false;
    fs.beginStutter(cfg);
    assertTrue(Stutterer.GLOBAL.isStuttering(fs.fw2Sound));

    fs.endStutter();
    assertFalse(Stutterer.GLOBAL.isStuttering(fs.fw2Sound));
  }
}
