package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
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
    synth.addPatchCable(new org.chuck.deluge.model.PatchCable("LFO1", "lpfFrequency", 0.5f));

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
    var destinations = fwClip.sound.paramManager.getPatchCableSet().destinations;
    assertFalse(destinations.isEmpty());
    assertEquals(Param.LOCAL_LPF_FREQ, destinations.get(0).paramId);
    assertEquals(PatchSource.LFO_LOCAL_1, destinations.get(0).cables.get(0).from);
  }
}
