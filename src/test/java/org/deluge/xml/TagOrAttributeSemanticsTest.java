package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * Guards the C's {@code readTagOrAttributeValue*} contract (sound.cpp:3300-3400): the firmware's
 * reader surfaces an attribute and a child element as the same "tag", so every field is valid in
 * BOTH forms.
 *
 * <p>Reading only one form is silent data loss — the setting keeps its default and nothing errors.
 * No render or fidelity test can see it, because the file simply loads as a different (valid)
 * sound. That is precisely how it escaped four times already: osc2 {@code type}, {@code hpfMode},
 * {@code oscillatorSync} and the osc bool flags. The firmware writes one form per container, so the
 * shipped corpus never exercises the other one — hand-written files (our own CALIB presets, files
 * from other tools or firmware versions) are what break.
 */
class TagOrAttributeSemanticsTest {

  private static Element parse(String xml) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        .getDocumentElement();
  }

  @Test
  void attributeFormResolves() throws Exception {
    Element el = parse("<defaultParams volume=\"0x40000000\"/>");
    assertEquals("0x40000000", DelugeXmlUtil.tagOrAttributeValue(el, "volume"));
  }

  @Test
  void childTextFormResolves() throws Exception {
    Element el = parse("<defaultParams><volume>0x40000000</volume></defaultParams>");
    assertEquals("0x40000000", DelugeXmlUtil.tagOrAttributeValue(el, "volume"));
  }

  @Test
  void childValueAttributeFormResolves() throws Exception {
    Element el = parse("<defaultParams><volume value=\"0x40000000\"/></defaultParams>");
    assertEquals("0x40000000", DelugeXmlUtil.tagOrAttributeValue(el, "volume"));
  }

  @Test
  void absentFieldIsNullNotEmpty() throws Exception {
    Element el = parse("<defaultParams pan=\"0x0\"/>");
    assertNull(DelugeXmlUtil.tagOrAttributeValue(el, "volume"));
  }

  @Test
  void attributeWinsOverChildWhenBothPresent() throws Exception {
    // The C reads whichever the reader reaches first and does not merge; pinning attribute-first
    // so the precedence is at least deterministic and documented rather than incidental.
    Element el =
        parse("<defaultParams volume=\"0x11111111\"><volume>0x22222222</volume></defaultParams>");
    assertEquals("0x11111111", DelugeXmlUtil.tagOrAttributeValue(el, "volume"));
  }

  /**
   * The regression this whole sweep exists to prevent: the hex readers used to resolve the child
   * form ONLY, so a parent-attribute field silently kept its default. Both forms must now land the
   * same value through the real reader helpers, not just the raw resolver.
   */
  @Test
  void hexReadersAcceptBothForms() throws Exception {
    Element childForm =
        parse("<defaultParams><lpfResonance>0x40000000</lpfResonance></defaultParams>");
    Element attrForm = parse("<defaultParams lpfResonance=\"0x40000000\"/>");

    float[] fromChild = new float[1];
    float[] fromAttr = new float[1];
    DelugeXmlUtil.readHexFloatUnipolar(childForm, "lpfResonance", v -> fromChild[0] = v);
    DelugeXmlUtil.readHexFloatUnipolar(attrForm, "lpfResonance", v -> fromAttr[0] = v);

    assertEquals(fromChild[0], fromAttr[0], "attribute form must load identically to child form");
    assertEquals(0.75f, fromAttr[0], 1e-6f);
  }

  @Test
  void hexHzReaderAcceptsBothForms() throws Exception {
    Element childForm =
        parse("<defaultParams><lpfFrequency>0x40000000</lpfFrequency></defaultParams>");
    Element attrForm = parse("<defaultParams lpfFrequency=\"0x40000000\"/>");

    float[] fromChild = new float[1];
    float[] fromAttr = new float[1];
    DelugeXmlUtil.readHexHz(childForm, "lpfFrequency", v -> fromChild[0] = v);
    DelugeXmlUtil.readHexHz(attrForm, "lpfFrequency", v -> fromAttr[0] = v);

    assertEquals(fromChild[0], fromAttr[0], "attribute form must load identically to child form");
  }

  @Test
  void envTimeAndFloatValReadersAcceptBothForms() throws Exception {
    Element childForm = parse("<envelope><attack>0x40000000</attack></envelope>");
    Element attrForm = parse("<envelope attack=\"0x40000000\"/>");
    assertEquals(
        DelugeXmlUtil.readHexEnvTime(childForm, "attack", -1f),
        DelugeXmlUtil.readHexEnvTime(attrForm, "attack", -2f));

    Element cf2 = parse("<defaultParams><sustain>0x40000000</sustain></defaultParams>");
    Element af2 = parse("<defaultParams sustain=\"0x40000000\"/>");
    assertEquals(
        DelugeXmlUtil.readHexFloatVal(cf2, "sustain", -1f),
        DelugeXmlUtil.readHexFloatVal(af2, "sustain", -2f));
  }
}
