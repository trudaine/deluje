package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.deluge.model.ArpModel;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** C arpeggiator.cpp:1693, 1701, 1709 read both "locked…ProbArray" and "locke…ProbArray". */
class ArpLockeAliasTest {

  @Test
  void oldLockeSpellingIsRead() throws Exception {
    String hex = "0102030405060708090A0B0C0D0E0F10";
    String xml =
        "<arpeggiator arpMode=\"arp\" lockeReverseProbArray=\""
            + hex
            + "\" lockeChordProbArray=\""
            + hex
            + "\" lockeRatchetProbArray=\""
            + hex
            + "\"/>";
    Element el =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
            .getDocumentElement();
    ArpModel arp = InstrumentXmlParser.parseArpElement(el, 1, false);
    assertEquals(hex, arp.lockedReverseProbArray());
    assertEquals(hex, arp.lockedChordProbArray());
    assertEquals(hex, arp.lockedRatchetProbArray());
  }
}
