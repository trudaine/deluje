package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/**
 * Verifies that step conditional visual cues (Iterance condition dot, Probability dot, Fill gate
 * pill bar) are correctly bound and rendered on high-fidelity DelugePadButton instances.
 */
public class DelugePadButtonConditionalVisualsTest {

  @Test
  public void testPadConditionalFlagsSetAndRepaintCleanly() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(64, 64);
    pad.setActive(true);
    pad.setBaseColor(new Color(0x00, 0xd2, 0xff));

    assertFalse(pad.hasCondition());
    assertFalse(pad.hasProbability());
    assertFalse(pad.isFillOnly());

    pad.setHasCondition(true);
    pad.setHasProbability(true);
    pad.setFillOnly(true);

    assertTrue(pad.hasCondition(), "Pad must reflect hasCondition=true");
    assertTrue(pad.hasProbability(), "Pad must reflect hasProbability=true");
    assertTrue(pad.isFillOnly(), "Pad must reflect isFillOnly=true");

    // Verify paintComponent renders overlays without throwing exceptions
    BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();
    assertDoesNotThrow(() -> pad.paintComponent(g2));
    g2.dispose();
  }
}
