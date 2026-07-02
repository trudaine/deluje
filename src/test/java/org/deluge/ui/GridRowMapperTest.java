package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.ui.SwingGridPanel.GridViewMode;
import org.junit.jupiter.api.Test;

/**
 * Characterisation tests pinning the current row-mapping behaviour, so the upcoming session-view
 * bottom-up ordering change can be made in one place ({@link GridRowMapper}) without silently
 * altering the other views.
 */
class GridRowMapperTest {

  @Test
  void nonKit_isIdentityPlusScroll() {
    for (GridViewMode view : GridViewMode.values()) {
      assertEquals(0, GridRowMapper.modelRow(view, 0, 0, false, 0));
      assertEquals(3, GridRowMapper.modelRow(view, 0, 3, false, 0));
      assertEquals(9, GridRowMapper.modelRow(view, 4, 5, false, 0), "scroll adds to visual row");
    }
  }

  @Test
  void kit_isBottomUp() {
    // display row 0 (top) -> last drum; matches original getModelRow for kit tracks.
    assertEquals(15, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 0, true, 16));
    assertEquals(14, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 1, true, 16));
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 15, true, 16));
    // scroll offset shifts the window
    assertEquals(11, GridRowMapper.modelRow(GridViewMode.CLIP, 4, 0, true, 16));
  }

  @Test
  void kit_flipIsClipViewOnly() {
    // In SONG/ARRANGEMENT the rows are tracks, not drums, so a kit must NOT flip (fixes the old
    // left-header-vs-pad-grid mismatch). Only CLIP view flips.
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.SONG, 0, 0, true, 10));
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.ARRANGEMENT, 0, 0, true, 10));
    assertEquals(9, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 0, true, 10));
  }
}
