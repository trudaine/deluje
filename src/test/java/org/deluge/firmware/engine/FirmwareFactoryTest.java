package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware.model.InstrumentClip;
import org.deluge.firmware.model.Song;
import org.deluge.firmware.modulation.patch.PatchSource;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class FirmwareFactoryTest {

  @Test
  public void testCreateSongFromModel() {
    ProjectModel project = new ProjectModel();
    project.setBpm(130.0f);

    SynthTrackModel synth = new SynthTrackModel("TestSynth");
    ClipModel clip = new ClipModel("TestClip", 1, 16);
    // Add one active note at step 0
    clip.setStep(0, 0, StepData.of(true, 100, 0.5f, 1.0f, 60));
    synth.addClip(clip);

    // Add a patch cable
    synth.addPatchCable(new org.deluge.model.PatchCable("LFO1", "lpfFrequency", 0.5f));

    project.addTrack(synth);

    Song song = FirmwareFactory.createSong(project);

    assertEquals(130.0f, song.tempoBPM);
    assertEquals(1, song.clips.size());

    InstrumentClip fwClip = (InstrumentClip) song.clips.get(0);
    assertEquals(1, fwClip.noteRows.size());
    assertEquals(1, fwClip.noteRows.get(0).notes.size());
    assertEquals(60, fwClip.noteRows.get(0).y);

    // Check patch cable mapping
    assertNotNull(fwClip.sound);
    var destinations = ((FirmwareSound) fwClip.sound).paramManager.getPatchCableSet().destinations;
    assertFalse(destinations.isEmpty());
    assertEquals(Param.LOCAL_LPF_FREQ, destinations.get(0).paramId);
    // C sourceToString (functions.cpp:270-271): the patch-source "lfo1" is LFO_GLOBAL_1, not local
    // (this assertion previously encoded the source-mapping bug fixed in FirmwareFactory).
    assertEquals(PatchSource.LFO_GLOBAL_1, destinations.get(0).cables.get(0).from);
  }
}
