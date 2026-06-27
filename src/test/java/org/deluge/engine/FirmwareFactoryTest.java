package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.patch.PatchSource;
import org.junit.jupiter.api.Test;

public class FirmwareFactoryTest {

  @Test
  public void testCreateSongFromProject() {
    System.setProperty("chuck.audio.dummy", "true");

    SynthTrackModel synth = new SynthTrackModel("test");
    synth.setOsc1Type("SAW");
    synth.setLpfFreq(1000f);

    org.deluge.model.PatchCable cable =
        new org.deluge.model.PatchCable("lfo1", "lpfFrequency", 1.0f);
    synth.getModulation().getPatchCables().add(cable);

    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setStep(0, 0, true); // Active step at row 0, step 0
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(130.0f);

    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);

    assertEquals(130.0f, song.getBpm());
    assertEquals(1, song.getTracks().size());

    ClipModel fwClip = song.getTracks().get(0).getActiveClip();
    assertEquals(1, fwClip.getNoteRowsList().size());
    assertEquals(1, fwClip.getNoteRowsList().get(0).getNotes().size());
    assertEquals(60, fwClip.getNoteRowsList().get(0).getPitch());

    // Check patch cable mapping
    assertNotNull(fwClip.getSound());
    var destinations =
        ((FirmwareSound) fwClip.getSound()).paramManager.getPatchCableSet().destinations;
    assertFalse(destinations.isEmpty());
    assertEquals(Param.LOCAL_LPF_FREQ, destinations.get(0).paramId);
    // C sourceToString (functions.cpp:270-271): the patch-source "lfo1" is LFO_GLOBAL_1, not local
    // (this assertion previously encoded the source-mapping bug fixed in FirmwareFactory).
    assertEquals(PatchSource.LFO_GLOBAL_1, destinations.get(0).cables.get(0).from);
  }
}
