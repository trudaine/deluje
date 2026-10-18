package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.model.ClipModel;
import org.junit.jupiter.api.Test;

/** C Clip::launchStyle (clip.cpp:687-689, 764-766; functions.cpp:1283-1310). */
class ClipLaunchStyleXmlTest {

  @Test
  void stringToLaunchStyleMapsUnknownToDefault() {
    ClipModel c = new ClipModel("c", 1, 16);
    assertEquals("default", c.getLaunchStyle());
    c.setLaunchStyle("fill");
    assertEquals("fill", c.getLaunchStyle());
    c.setLaunchStyle("once");
    assertEquals("once", c.getLaunchStyle());
    c.setLaunchStyle("FILL"); // C strcmp is case-sensitive
    assertEquals("default", c.getLaunchStyle());
    c.setLaunchStyle(null);
    assertEquals("default", c.getLaunchStyle());
  }

  @Test
  void deepCopyKeepsLaunchStyle() {
    ClipModel c = new ClipModel("c", 1, 16);
    c.setLaunchStyle("once");
    assertEquals("once", c.deepCopy("d").getLaunchStyle());
  }
}
