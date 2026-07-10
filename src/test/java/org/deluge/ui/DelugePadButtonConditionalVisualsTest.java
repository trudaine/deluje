package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/**
 * Verifies that step conditional visual cues (Iterance condition dot, Probability dot, Fill gate
 * pill bar) are correctly bound and rendered on high-fidelity DelugePadButton instances.
 *
 * <p>Regression: the original version of this test only checked that the boolean getters echoed
 * back what the setters had just been given, plus assertDoesNotThrow on paintComponent -- it would
 * pass even if the flags were dead fields with no rendering effect at all. Now renders each flag on
 * vs. off and confirms the actual pixels differ, and that the documented color
 * (DelugePadButton.java's amber/cyan/magenta overlay colors) shows up near the documented corner.
 */
public class DelugePadButtonConditionalVisualsTest {

  private static final int SIZE = 64;

  @Test
  public void testPadConditionalFlagsSetAndRepaintCleanly() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(SIZE, SIZE);
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

    assertDoesNotThrow(() -> renderPad(pad));
  }

  @Test
  public void testConditionDotActuallyRendersAmberNearUpperRightCorner() {
    BufferedImage baseline = renderPad(freshPad());
    DelugePadButton withCondition = freshPad();
    withCondition.setHasCondition(true);
    BufferedImage withDot = renderPad(withCondition);

    assertFalse(
        imagesEqual(baseline, withDot),
        "hasCondition=true must actually change the rendered pixels, not just the flag");
    // DelugePadButton.java:594-599 -- fillOval(xPad+rw-9, yPad+4, 5, 5) with xPad=2, rw=w-4=60.
    assertTrue(
        regionContainsColorNear(withDot, 50, 3, 62, 14, 0xff, 0xb7, 0x03, 40),
        "expected the amber conditional dot near the upper-right corner");
  }

  @Test
  public void testProbabilityDotActuallyRendersCyanNearUpperLeftCorner() {
    BufferedImage baseline = renderPad(freshPad());
    DelugePadButton withProbability = freshPad();
    withProbability.setHasProbability(true);
    BufferedImage withDot = renderPad(withProbability);

    assertFalse(
        imagesEqual(baseline, withDot),
        "hasProbability=true must actually change the rendered pixels, not just the flag");
    // DelugePadButton.java:601-606 -- fillOval(xPad+4, yPad+4, 5, 5) with xPad=2.
    assertTrue(
        regionContainsColorNear(withDot, 2, 3, 14, 14, 0x00, 0xd2, 0xff, 40),
        "expected the cyan probability dot near the upper-left corner");
  }

  @Test
  public void testFillOnlyActuallyRendersMagentaPillAtBottom() {
    BufferedImage baseline = renderPad(freshPad());
    DelugePadButton fillOnly = freshPad();
    fillOnly.setFillOnly(true);
    BufferedImage withPill = renderPad(fillOnly);

    assertFalse(
        imagesEqual(baseline, withPill),
        "isFillOnly=true must actually change the rendered pixels, not just the flag");
    // DelugePadButton.java:608-611 -- fillRoundRect(xPad+3, yPad+rh-5, rw-6, 3, 2, 2) with rh=60.
    assertTrue(
        regionContainsColorNear(withPill, 3, 54, SIZE - 3, 62, 0xff, 0x00, 0x7f, 60),
        "expected the magenta fill-gate pill bar near the bottom edge");
  }

  private static DelugePadButton freshPad() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(SIZE, SIZE);
    pad.setActive(true);
    pad.setBaseColor(new Color(0x00, 0xd2, 0xff));
    return pad;
  }

  private static BufferedImage renderPad(DelugePadButton pad) {
    BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img.createGraphics();
    pad.paintComponent(g2);
    g2.dispose();
    return img;
  }

  private static boolean imagesEqual(BufferedImage a, BufferedImage b) {
    for (int y = 0; y < SIZE; y++) {
      for (int x = 0; x < SIZE; x++) {
        if (a.getRGB(x, y) != b.getRGB(x, y)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean regionContainsColorNear(
      BufferedImage img, int x0, int y0, int x1, int y1, int r, int g, int b, int tolerance) {
    for (int y = Math.max(0, y0); y < Math.min(SIZE, y1); y++) {
      for (int x = Math.max(0, x0); x < Math.min(SIZE, x1); x++) {
        int argb = img.getRGB(x, y);
        int pr = (argb >> 16) & 0xFF;
        int pg = (argb >> 8) & 0xFF;
        int pb = argb & 0xFF;
        if (Math.abs(pr - r) <= tolerance
            && Math.abs(pg - g) <= tolerance
            && Math.abs(pb - b) <= tolerance) {
          return true;
        }
      }
    }
    return false;
  }
}
