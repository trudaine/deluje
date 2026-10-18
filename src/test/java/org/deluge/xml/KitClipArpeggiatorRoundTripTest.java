package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * A kit clip's own &lt;arpeggiator&gt; (the kit arp, C InstrumentClip::arpSettings) survives a
 * load/save: read with the clip's INTERNAL syncLevel (instrument_clip.cpp:2676-2689), written back
 * the same way (instrument_clip.cpp:2404-2412).
 */
class KitClipArpeggiatorRoundTripTest {

  private static ProjectModel roundTrip(ProjectModel model) throws Exception {
    File f = File.createTempFile("kitarp", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    return DelugeXmlParser.parseSong(f);
  }

  @Test
  void kitArpSurvivesASaveAndLoad() throws Exception {
    ProjectModel model = new ProjectModel();
    KitTrackModel kit = new KitTrackModel("KIT");
    kit.setArp(
        ArpModel.defaultConfig().toBuilder().active(true).syncLevel(8).noteMode("DOWN").build());
    kit.addClip(new ClipModel("k", 4, 16));
    model.addTrack(kit);

    KitTrackModel back = (KitTrackModel) roundTrip(model).getTracks().get(0);
    assertNotNull(back.getArp());
    assertTrue(back.getArp().active());
    assertEquals(8, back.getArp().syncLevel());
    assertEquals("DOWN", back.getArp().noteMode());
  }

  @Test
  void kitWithoutArpWritesNone() throws Exception {
    ProjectModel model = new ProjectModel();
    KitTrackModel kit = new KitTrackModel("KIT");
    kit.addClip(new ClipModel("k", 4, 16));
    model.addTrack(kit);
    File f = File.createTempFile("kitnoarp", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    String xml = Files.readString(f.toPath());
    String clip = xml.substring(xml.indexOf("<instrumentClip"), xml.indexOf("</instrumentClip>"));
    assertTrue(!clip.contains("<arpeggiator"), clip);
    assertNull(((KitTrackModel) DelugeXmlParser.parseSong(f).getTracks().get(0)).getArp());
  }
}
