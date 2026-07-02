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
  void clipView_nonKit_isIdentityPlusScroll() {
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 0, false, 0, 8));
    assertEquals(3, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 3, false, 0, 8));
    assertEquals(9, GridRowMapper.modelRow(GridViewMode.CLIP, 4, 5, false, 0, 8));
  }

  @Test
  void kit_isBottomUp_clipOnly() {
    assertEquals(15, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 0, true, 16, 8));
    assertEquals(14, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 1, true, 16, 8));
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 15, true, 16, 8));
    assertEquals(11, GridRowMapper.modelRow(GridViewMode.CLIP, 4, 0, true, 16, 8));
  }

  @Test
  void songAndArranger_currentlyTopDown() {
    // Bottom-up ordering is deferred (see GridRowMapper) until the left-column render path is
    // routed through the mapper; today SONG/ARR remain identity+scroll like CLIP.
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.SONG, 0, 0, false, 0, 5));
    assertEquals(1, GridRowMapper.modelRow(GridViewMode.SONG, 0, 1, false, 0, 5));
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.ARRANGEMENT, 0, 0, false, 0, 5));
  }

  @Test
  void kit_flipIsClipViewOnly() {
    // In SONG/ARRANGEMENT the rows are tracks, not drums: no kit flip.
    assertEquals(0, GridRowMapper.modelRow(GridViewMode.SONG, 0, 0, true, 10, 10));
    assertEquals(9, GridRowMapper.modelRow(GridViewMode.CLIP, 0, 0, true, 10, 10));
  }
}
