package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * C: each instrument clip carries its own arpSettings (instrument_clip.cpp:2404-2412, 2676-2689)
 * and the sound plays the active clip's (Sound::getArpSettings).
 */
class PerClipSynthArpTest {

  @Test
  void eachClipKeepsItsArpAndTheActiveOnePlays() throws Exception {
    ProjectModel model = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    ClipModel a = new ClipModel("a", 1, 16);
    ClipModel b = new ClipModel("b", 1, 16);
    a.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(8).build());
    b.setArp(ArpModel.defaultConfig().toBuilder().active(false).build());
    synth.addClip(a);
    synth.addClip(b);
    model.addTrack(synth);

    File f = File.createTempFile("perclip", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    SynthTrackModel back = (SynthTrackModel) DelugeXmlParser.parseSong(f).getTracks().get(0);
    assertTrue(back.getClips().get(0).getArp().active());
    assertEquals(8, back.getClips().get(0).getArp().syncLevel());
    assertFalse(back.getClips().get(1).getArp().active());

    FirmwareSound fs = new FirmwareSound();
    back.getClips().forEach(c -> c.setSound(fs));
    back.setActiveClipIndex(0);
    assertTrue(back.getArp().active(), "the active clip's arp is the synth's");
    back.setActiveClipIndex(1);
    assertFalse(back.getArp().active());
    assertEquals(Arpeggiator.ArpMode.OFF, fs.arpSettings.mode, "switching clips reconfigures");
    back.setActiveClipIndex(0);
    assertEquals(Arpeggiator.ArpMode.ARP, fs.arpSettings.mode);
  }
}
