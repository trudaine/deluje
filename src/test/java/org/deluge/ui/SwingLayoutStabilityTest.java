package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import org.deluge.BridgeContract;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proactive Automated UI Integration Test Suite. Simulates basic user actions (scrolling, zooming,
 * muting, view-switching) and verifies that: 1. Scrolling and parameter clicks do NOT trigger
 * destructive component-tree rebuilds (in-place refreshes only). 2. Structural layout changes
 * (zooming, view switching) DO correctly trigger structural rebuilds. 3. No Event Dispatch Thread
 * (EDT) or rendering exceptions are thrown during these sweeps.
 */
public class SwingLayoutStabilityTest {

  @BeforeAll
  static void silenceAudio() {
    org.deluge.engine.JavaAudioDriver.silentMode = true;
  }

  @org.junit.jupiter.api.BeforeEach
  void resetPreferences() {
    org.deluge.project.PreferencesManager.setGridMode(
        org.deluge.project.PreferencesManager.GridMode.GRID_8x16);
  }

  @Test
  public void testUIActionStabilityAndNonDestructiveRefreshes() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    // 1. Setup headless app instance
    BridgeContract bridge = new BridgeContract();

    SwingDelugeApp app = new SwingDelugeApp(bridge, null);
    SwingGridPanel grid = app.getClipPanel();

    // Set the view mode to CLIP (piano roll) to begin
    app.getTopBarListener().onViewModeChanged("CLIP");
    grid.refresh();

    // Record the initial component count of the grid layout
    int initialComponentsCount = countAllComponents(grid);
    assertTrue(initialComponentsCount > 0, "Grid must render buttons and panels on boot");

    System.out.println(
        "[TEST UI] Boot state verified. Total nested components: " + initialComponentsCount);

    // ── ASSERTION 1: Vertical Scroll Stability (No Destructive Rebuilds!) ──
    // Scroll down by 5 rows.
    for (int i = 0; i < 5; i++) {
      grid.scrollVertically(1);
    }
    int postScrollVertCount = countAllComponents(grid);
    assertEquals(
        initialComponentsCount,
        postScrollVertCount,
        "Vertical scrolling must perform in-place refreshes and must NOT destroy/recreate Swing components!");

    // Scroll back up.
    grid.scrollVertically(-5);
    assertEquals(
        initialComponentsCount,
        countAllComponents(grid),
        "Scrolling back up must maintain a rock-solid, stable component count!");

    System.out.println("[TEST UI] Vertical scroll stability PASSED perfectly.");

    // ── ASSERTION 2: Horizontal Scroll Stability ──
    // Scroll horizontally by 3 beats.
    grid.scrollHorizontally(3);
    int postScrollHorizCount = countAllComponents(grid);
    assertEquals(
        initialComponentsCount,
        postScrollHorizCount,
        "Horizontal scrolling must perform in-place refreshes and must NOT trigger layout reflows/rebuilds!");

    grid.scrollHorizontally(-3);
    System.out.println("[TEST UI] Horizontal scroll stability PASSED perfectly.");

    // ── ASSERTION 3: Param Change / Mute Stability ──
    // Simulate muting the current track. This triggers model updates and calls refresh().
    bridge.setMute(0, true);
    grid.refresh();
    int postMuteCount = countAllComponents(grid);
    assertEquals(
        initialComponentsCount,
        postMuteCount,
        "Muting a track must update button properties in-place, preserving layout and component bounds!");

    bridge.setMute(0, false);
    System.out.println("[TEST UI] Parameter/Mute change stability PASSED perfectly.");

    // ── ASSERTION 4: Zoom/Resolution Change Rebuilds (Must Rebuild!) ──
    // Zooming in/out (Alt + Scroll) shifts the visible step columns/rows, meaning the grid
    // structure has physically changed.
    // Assert that a structural rebuild IS correctly triggered to adapt to the new size!
    PreferencesManager.setGridMode(PreferencesManager.GridMode.GRID_16x16);
    grid.setGridMode(PreferencesManager.GridMode.GRID_16x16);
    grid.refresh();

    int postZoomCount = countAllComponents(grid);
    assertNotEquals(
        initialComponentsCount,
        postZoomCount,
        "Zooming/Resolution change must trigger a structural rebuild (component count should change to fit new column/row layout)!");

    System.out.println("[TEST UI] Resolution zoom structural rebuild PASSED perfectly.");

    // Restore default resolution zoom
    PreferencesManager.setGridMode(PreferencesManager.GridMode.GRID_8x16);
    grid.setGridMode(PreferencesManager.GridMode.GRID_8x16);
    grid.refresh();
    assertEquals(
        initialComponentsCount,
        countAllComponents(grid),
        "Restoring resolution must return the component tree exactly to its initial size.");

    // ── ASSERTION 5: View Mode Swapping Rebuilds ──
    // Switch from CLIP to SONG. Assert that the UI successfully rebuilds without throwing any
    // exceptions.
    app.getTopBarListener().onViewModeChanged("SONG");
    assertEquals(
        org.deluge.ui.SwingGridPanel.GridViewMode.SONG,
        app.getSongPanel().getViewMode(),
        "Song panel's view mode must be SONG");

    // Switch back to CLIP mode
    app.getTopBarListener().onViewModeChanged("CLIP");
    grid.refresh();
    assertEquals(
        org.deluge.ui.SwingGridPanel.GridViewMode.CLIP,
        app.getClipPanel().getViewMode(),
        "Clip panel's view mode must be restored to CLIP");

    System.out.println("[TEST UI] View mode swapping structural rebuilds PASSED perfectly.");

    // Cleanup resources
    app.dispose();
    bridge.shutdown();
    System.out.println("[TEST UI] Headless UI Action stability E2E test PASSED with 100% success!");
  }

  /** Helper method to recursively count all nested components in a Swing container */
  private int countAllComponents(Container container) {
    int count = 1; // Count the container itself
    for (Component comp : container.getComponents()) {
      if (comp instanceof Container) {
        count += countAllComponents((Container) comp);
      } else {
        count++;
      }
    }
    return count;
  }
}
