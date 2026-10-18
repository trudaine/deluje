package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * C InstrumentClip::writeDataToFile (instrument_clip.cpp:2404-2412): every instrument clip writes
 * its own &lt;arpeggiator&gt; with writeCommonParamsToFile(writer, nullptr) — the clip's arp
 * settings come only from it, and syncLevel is the internal value.
 */
class ClipLevelArpeggiatorTest {

  @Test
  void synthClipCarriesItsArpeggiator() throws Exception {
    ProjectModel model = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(16).build());
    synth.addClip(new ClipModel("c", 1, 16));
    model.addTrack(synth);
    File f = File.createTempFile("cliparp", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);

    Element clip =
        (Element)
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(f)
                .getElementsByTagName("instrumentClip")
                .item(0);
    Element arp = null;
    for (Node n = clip.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && "arpeggiator".equals(e.getTagName())) {
        arp = e;
      }
    }
    assertNotNull(arp, "instrumentClip must carry its own <arpeggiator>");
    assertEquals("arp", arp.getAttribute("arpMode"));
    assertEquals("arp", arp.getAttribute("mode"));
    // A 16th is internal 8 - inputTickMagnitude (C ArpeggiatorSettings constructor default).
    assertEquals(String.valueOf(8 - model.getInputTickMagnitude()), arp.getAttribute("syncLevel"));
  }
}
