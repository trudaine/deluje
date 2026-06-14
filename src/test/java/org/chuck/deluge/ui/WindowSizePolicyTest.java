package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Screen Resolution window-size policy: profiles are honored on big screens but always
 * clamped to fit the physical screen (down to a 1366x768 laptop), and floored to a usable minimum.
 */
public class WindowSizePolicyTest {

  @Test
  public void profilesAreHonoredOnLargeScreens() {
    assertEquals(new Dimension(1760, 980), SwingDelugeApp.windowSizeFor("FHD", 3840, 2160));
    assertEquals(new Dimension(2360, 1340), SwingDelugeApp.windowSizeFor("QHD", 3840, 2160));
    assertEquals(new Dimension(2560, 1500), SwingDelugeApp.windowSizeFor("Retina", 3840, 2160));
  }

  @Test
  public void defaultProfileFitsTheScreen() {
    // "Default" = fill the screen minus margins.
    assertEquals(new Dimension(2544, 1552), SwingDelugeApp.windowSizeFor("Default", 2560, 1600));
  }

  @Test
  public void highProfileStillFitsALowResLaptop() {
    // Even picking QHD on a 1366x768 laptop must not exceed the screen.
    Dimension d = SwingDelugeApp.windowSizeFor("QHD", 1366, 768);
    assertTrue(d.width <= 1366, "width fits screen: " + d.width);
    assertTrue(d.height <= 768, "height fits screen: " + d.height);
  }

  @Test
  public void neverSmallerThanTheUsableMinimum() {
    // A tiny screen still yields at least the minimum layout size.
    Dimension d = SwingDelugeApp.windowSizeFor("FHD", 1024, 600);
    assertTrue(d.width >= 1180, "floored width");
    assertTrue(d.height >= 680, "floored height");
  }

  @Test
  public void unknownProfileFallsBackToQhd() {
    assertEquals(
        SwingDelugeApp.windowSizeFor("QHD", 3000, 2000),
        SwingDelugeApp.windowSizeFor("bogus", 3000, 2000));
  }
}
