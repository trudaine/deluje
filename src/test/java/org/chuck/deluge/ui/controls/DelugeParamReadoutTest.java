package org.chuck.deluge.ui.controls;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** Smoke tests: the readout renders and exposes the legacy API without throwing (headless-safe). */
public class DelugeParamReadoutTest {

  private static void paint(DelugeParamReadout r) {
    BufferedImage img = new BufferedImage(200, 48, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    r.setSize(184, 46);
    r.paint(g);
    g.dispose();
  }

  @Test
  public void printThenPaintRendersActiveValue() {
    DelugeParamReadout r = new DelugeParamReadout();
    assertDoesNotThrow(
        () -> {
          r.print("CUTOFF", "72%");
          paint(r);
        });
  }

  @Test
  public void resetThenPaintRendersIdle() {
    DelugeParamReadout r = new DelugeParamReadout();
    assertDoesNotThrow(
        () -> {
          r.print("RES", "30%");
          r.reset();
          paint(r);
        });
  }

  @Test
  public void transientAndScrollDoNotThrow() {
    DelugeParamReadout r = new DelugeParamReadout();
    assertDoesNotThrow(
        () -> {
          r.printTransient("VOL", "88%");
          paint(r);
          r.scrollMessage("UNSAVED   C-2 MAJOR   120 BPM");
          paint(r);
          r.scrollMessage("SHORT");
          paint(r);
        });
  }

  @Test
  public void nullArgsAreTolerated() {
    DelugeParamReadout r = new DelugeParamReadout();
    assertDoesNotThrow(
        () -> {
          r.print(null, null);
          paint(r);
          r.scrollMessage(null);
          paint(r);
        });
  }
}
