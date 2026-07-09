package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.BridgeContract;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a real sizing bug: cell size (padSz) in ClipGridPanel, SongGridPanel, and
 * ArrangerGridPanel used to be computed purely from window width (faceScale = width / 2256),
 * completely ignoring gridMode.rows or the available viewport height. Switching to a taller grid
 * mode (e.g. 16x16, 24x16) therefore never shrank cells to compensate -- the grid just got taller
 * and clipped, since SwingDelugeApp's centerScroll has both scrollbars disabled (no way to scroll
 * to the clipped content). Fixed by capping the width-derived padSz with cachedPadSz, the
 * height-fitting value recomputePadSize() already solves for, and by capping
 * SwingHardwareTopPanel's height (previously unbounded, scaling with window width) so more vertical
 * room goes to the grid.
 */
public class GridSizingNoClippingTest {

  private void assertNoClipping(SwingGridPanel grid, String label) throws Exception {
    // Force a real resize event (matching what an interactive window resize triggers), since a
    // one-shot setSize() before the frame settles can leave stale, pre-final-layout measurements.
    SwingDelugeApp app = (SwingDelugeApp) javax.swing.SwingUtilities.getWindowAncestor(grid);
    javax.swing.SwingUtilities.invokeAndWait(
        () -> {
          app.setSize(app.getWidth() - 1, app.getHeight());
          app.validate();
          app.setSize(app.getWidth() + 1, app.getHeight());
          app.validate();
        });
    Thread.sleep(150);

    java.awt.Dimension gridSize = grid.getSize();
    java.awt.Container p = grid.getParent();
    java.awt.Dimension vpSize = null;
    while (p != null) {
      if (p instanceof javax.swing.JViewport vp) {
        vpSize = vp.getSize();
        break;
      }
      p = p.getParent();
    }
    assertNotNull(vpSize, label + ": grid must be inside a JViewport");
    assertTrue(
        gridSize.height <= vpSize.height,
        label
            + ": grid height "
            + gridSize.height
            + " must not exceed viewport height "
            + vpSize.height
            + " (cachedPadSz="
            + grid.cachedPadSz
            + ")");
    assertTrue(
        gridSize.width <= vpSize.width,
        label
            + ": grid width "
            + gridSize.width
            + " must not exceed viewport width "
            + vpSize.width);
  }

  @Test
  public void testNoClippingAcrossGridModesAndWindowSizes() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      SwingGridPanel grid = app.getClipPanel();
      PreferencesManager.GridMode[] modes = {
        PreferencesManager.GridMode.GRID_8x16,
        PreferencesManager.GridMode.GRID_16x16,
        PreferencesManager.GridMode.GRID_24x16
      };

      app.setSize(1920, 1080);
      app.setVisible(true);
      for (PreferencesManager.GridMode mode : modes) {
        app.updateGlobalGridMode(mode);
        assertNoClipping(grid, "1920x1080 " + mode);
      }

      javax.swing.SwingUtilities.invokeAndWait(() -> app.setSize(1280, 800));
      for (PreferencesManager.GridMode mode : modes) {
        app.updateGlobalGridMode(mode);
        assertNoClipping(grid, "1280x800 " + mode);
      }
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testZoomMenuActuallyChangesCellSize() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    PreferencesManager.setGridMode(PreferencesManager.GridMode.GRID_8x16);
    BridgeContract bridge = new BridgeContract();
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      app.setSize(1600, 1000);
      app.setVisible(true);

      java.lang.reflect.Method zoomGrid =
          SwingDelugeApp.class.getDeclaredMethod("zoomGrid", boolean.class);
      zoomGrid.setAccessible(true);

      int padAt8x16 = app.getClipPanel().cachedPadSz;
      zoomGrid.invoke(app, false); // zoom out -> 16x16
      assertEquals(PreferencesManager.GridMode.GRID_16x16, PreferencesManager.getGridMode());
      int padAt16x16 = app.getClipPanel().cachedPadSz;
      assertTrue(
          padAt16x16 < padAt8x16,
          "Zooming out to a denser grid mode must shrink cell size (was "
              + padAt8x16
              + ", now "
              + padAt16x16
              + ")");

      zoomGrid.invoke(app, true); // zoom back in -> 8x16
      assertEquals(PreferencesManager.GridMode.GRID_8x16, PreferencesManager.getGridMode());
      int padBackAt8x16 = app.getClipPanel().cachedPadSz;
      assertTrue(
          padBackAt8x16 > padAt16x16,
          "Zooming back in to a sparser grid mode must grow cell size again (was "
              + padAt16x16
              + " at 16x16, now "
              + padBackAt8x16
              + " back at 8x16)");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testTopPanelHeightIsCappedOnWideWindows() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    try {
      app.setSize(1920, 1080);
      app.setVisible(true);
      javax.swing.SwingUtilities.invokeAndWait(
          () -> {
            app.setSize(1919, 1079);
            app.validate();
            app.setSize(1920, 1080);
            app.validate();
          });
      Thread.sleep(150);

      int topHeight = app.hardwareTopPanel.getHeight();
      assertTrue(
          topHeight <= 400,
          "Top faceplate panel height must be capped on wide windows, was " + topHeight);
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }
}
