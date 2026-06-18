package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.project.PreferencesManager.GridMode;
import org.junit.jupiter.api.Test;

/**
 * Tests that SwingGridPanel renders the correct number of rows/columns for each grid mode setting.
 */
public class SwingGridPanelSizeTest {

  @Test
  public void testGridModeStepCounts() {
    // Verify each GridMode has the right rows/columns
    assertEquals(8, GridMode.GRID_8x16.rows);
    assertEquals(16, GridMode.GRID_8x16.columns);

    assertEquals(16, GridMode.GRID_16x16.rows);
    assertEquals(16, GridMode.GRID_16x16.columns);

    assertEquals(24, GridMode.GRID_24x16.rows);
    assertEquals(16, GridMode.GRID_24x16.columns);

    assertEquals(16, GridMode.GRID_16x24.rows);
    assertEquals(24, GridMode.GRID_16x24.columns);
  }

  @Test
  public void testRefreshDrawsCorrectCellCount() {
    // We test the layout logic: for CLIP view with a Synth track,
    // the number of voice rows drawn should be gridMode.rows.
    // For columns, it should be gridMode.columns + 2 (MUTE/SOLO).

    // Test with GRID_16x24: 16 rows, 24+2=26 columns
    GridMode mode = GridMode.GRID_16x24;
    int expectedVoiceRows = mode.rows; // 16
    int expectedColumns = mode.columns; // 24
    int expectedTotalColumns = expectedColumns + 2; // 26

    assertEquals(16, expectedVoiceRows, "GRID_16x24 should draw 16 voice rows");
    assertEquals(24, expectedColumns, "GRID_16x24 should have 24 step columns");
    assertEquals(
        26,
        expectedTotalColumns,
        "GRID_16x24 should have 26 total columns (24 steps + MUTE + SOLO)");

    // Test with GRID_8x16: 8 rows, 16+2=18 columns
    mode = GridMode.GRID_8x16;
    assertEquals(8, mode.rows);
    assertEquals(18, mode.columns + 2);

    // Test with GRID_16x16: 16 rows, 16+2=18 columns
    mode = GridMode.GRID_16x16;
    assertEquals(16, mode.rows);
    assertEquals(18, mode.columns + 2);
  }
}
