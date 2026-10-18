package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** C sound.cpp:433-441 ("mode") and :744-750 (legacy "ringMod"). */
class SynthModeLegacyRingModTest {

  private static int mode(String xml) throws Exception {
    Element el =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            .getDocumentElement();
    return DelugeXmlUtil.synthModeFromSound(el);
  }

  @Test
  void modeAndLegacyRingMod() throws Exception {
    assertEquals(-1, mode("<sound/>"));
    assertEquals(1, mode("<sound mode=\"fm\"/>"));
    assertEquals(1, mode("<sound><mode>fm</mode></sound>"));
    assertEquals(0, mode("<sound mode=\"FM\"/>")); // stringToSynthMode is case-sensitive
    assertEquals(2, mode("<sound><ringMod>1</ringMod></sound>"));
    assertEquals(2, mode("<sound mode=\"subtractive\"><ringMod>1</ringMod></sound>"));
    assertEquals(2, mode("<sound><ringMod>1</ringMod><mode>fm</mode></sound>"));
    assertEquals(-1, mode("<sound><ringMod>0</ringMod></sound>")); // :746 — only 1 acts
    // Only direct children count: an arpeggiator's own <mode> is not the sound's.
    assertEquals(-1, mode("<sound><arpeggiator><mode>fm</mode></arpeggiator></sound>"));
  }
}
