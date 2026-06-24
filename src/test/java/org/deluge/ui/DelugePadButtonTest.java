package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.deluge.ui.controls.UiAnimator;
import org.junit.jupiter.api.Test;

/** Verifies the playhead blink registers on the shared clock and is leak-safe. */
public class DelugePadButtonTest {

  @Test
  public void playheadRegistersAndUnregistersOnTheSharedClock() {
    int base = UiAnimator.get().listenerCount();
    DelugePadButton pad = new DelugePadButton();

    pad.setPlayhead(true);
    assertEquals(base + 1, UiAnimator.get().listenerCount(), "playhead pad adds one tick listener");

    pad.setPlayhead(false);
    assertEquals(base, UiAnimator.get().listenerCount(), "clearing playhead removes it");
  }

  @Test
  public void removeNotifyUnregistersToAvoidLeakOnRefresh() {
    int base = UiAnimator.get().listenerCount();
    DelugePadButton pad = new DelugePadButton();
    pad.setPlayhead(true);
    assertEquals(base + 1, UiAnimator.get().listenerCount());

    // Grid rebuilds pads on refresh -> the discarded pad must drop its registration.
    pad.removeNotify();
    assertEquals(base, UiAnimator.get().listenerCount(), "removeNotify unregisters the tick");
  }

  @Test
  public void tiedTailPadPaintsWithoutThrowing() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(40, 40);
    pad.setActive(true);
    pad.setBaseColor(new Color(0x33, 0xcc, 0xff));
    pad.setTail(true);
    assertEquals(true, pad.isTail(), "pad tail state must be true");
    BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    pad.paint(g);
    g.dispose();
  }

  @Test
  public void playheadPadPaintsWithoutThrowing() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(40, 40);
    pad.setActive(true);
    pad.setBaseColor(new Color(0x33, 0xcc, 0xff));
    pad.setPlayhead(true);
    BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    pad.paint(g);
    g.dispose();
    pad.setPlayhead(false); // cleanup registration
  }

  @Test
  public void glowingStatesAndThemesPaintWithoutThrowing() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(40, 40);

    // 1. Verify property setters and getters
    pad.setScaleRoot(true);
    assertEquals(true, pad.isScaleRoot());
    pad.setScaleNote(true);
    assertEquals(true, pad.isScaleNote());
    pad.setBeatMarker(true);
    assertEquals(true, pad.isBeatMarker());
    pad.setTheme(org.deluge.project.PreferencesManager.GridColorTheme.HARDWARE);
    assertEquals(org.deluge.project.PreferencesManager.GridColorTheme.HARDWARE, pad.getTheme());

    // 2. Paint in Hardware Theme (active = false)
    pad.setActive(false);
    BufferedImage img1 = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g1 = img1.createGraphics();
    pad.paint(g1);
    g1.dispose();

    // 3. Paint in Neon Theme (active = false)
    pad.setTheme(org.deluge.project.PreferencesManager.GridColorTheme.NEON);
    BufferedImage img2 = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = img2.createGraphics();
    pad.paint(g2);
    g2.dispose();
  }

  @Test
  public void testNonLinearVelocityBrightnessScaling() {
    // 1. Full velocity: 1.0f -> 1.0f brightness
    float fullBright = DelugePadButton.adjustVelocityBrightness(1.0f);
    assertEquals(1.0f, fullBright, 0.01f, "Full velocity must scale to 1.0 brightness");

    // 2. Zero velocity: 0.0f -> 65/255 minimum brightness glow
    float zeroBright = DelugePadButton.adjustVelocityBrightness(0.0f);
    assertEquals(
        65.0f / 255.0f,
        zeroBright,
        0.01f,
        "Zero velocity must scale to minimum brightness (65/255)");

    // 3. Mid velocity: 0.5f -> 159.5/255 non-linear brightness glow (approx 0.625)
    float midBright = DelugePadButton.adjustVelocityBrightness(0.5f);
    assertEquals(
        159.5f / 255.0f,
        midBright,
        0.01f,
        "0.5 velocity must scale to non-linear 159.5/255 brightness");
  }

  @Test
  public void testBlurColorDesaturation() {
    DelugePadButton pad = new DelugePadButton();
    assertFalse(pad.isBlur(), "Should default to not blurred");

    pad.setBlur(true);
    assertTrue(pad.isBlur(), "Should update isBlur to true");

    // Test desaturation math with a bright Green color
    Color green = new Color(0, 255, 0);
    Color blurGreen = DelugePadButton.getBlurColor(green);

    // Green is (0, 255, 0).
    // factor = (0*5 + 255*9 + 0*9) >> 5 = 2295 >> 5 = 71.
    // nr = (0*3 + 71*5)/8 = 355/8 = 44.
    // ng = (255*3 + 71*5)/8 = (765 + 355)/8 = 1120/8 = 140.
    // nb = (0*3 + 71*5)/8 = 44.
    // So expected color is (44, 140, 44)!
    assertEquals(44, blurGreen.getRed(), "Red channel must match C++ blur math");
    assertEquals(140, blurGreen.getGreen(), "Green channel must match C++ blur math");
    assertEquals(44, blurGreen.getBlue(), "Blue channel must match C++ blur math");
  }
}
