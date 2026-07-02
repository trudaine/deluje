package org.deluge.ui;

/**
 * Single source of truth for mapping a grid's visual (display) row to a model row index.
 *
 * <p>The Swing grid historically computed this mapping inline at 40+ sites (some via {@code
 * getModelRow}, some as a bare {@code scrollOffset + v}), which is why a one-line "reverse the row
 * order" change came out inconsistent across the left header, the pad grid and the clip labels.
 * Centralising it here makes the mapping pure and unit-testable, and lets view-order changes (e.g.
 * the Deluge's bottom-up session grid) be made in exactly one place.
 *
 * <p>Pure functions only — no Swing, no instance state — so behaviour can be pinned by tests
 * (see {@code GridRowMapperTest}) before and after any ordering change.
 */
final class GridRowMapper {
  private GridRowMapper() {}

  /**
   * Maps a visual row (0 = top pad row) to the model/engine row index it represents.
   *
   * @param view the active grid view
   * @param scrollOffset current vertical scroll offset
   * @param visualRow the on-screen row, 0 at the top
   * @param editedTrackIsKit whether the currently edited track is a kit (drives CLIP-view flip)
   * @param kitDrumCount number of drums in the edited kit (only used when {@code editedTrackIsKit})
   * @return the model row index; may be negative for empty slots, callers guard for that
   */
  static int modelRow(
      SwingGridPanel.GridViewMode view,
      int scrollOffset,
      int visualRow,
      boolean editedTrackIsKit,
      int kitDrumCount,
      int trackCount) {
    // A kit CLIP is a bottom-up piano-roll of its drums (display row 0 = last drum). This only
    // applies in CLIP view; in SONG/ARRANGEMENT the rows are tracks, not drums, so the flip must
    // not happen there. (The original getModelRow flipped for any view when a kit was edited, which
    // left the SONG pad grid — which never flipped — inconsistent with its own left header; scoping
    // the flip to CLIP fixes that latent mismatch.)
    if (editedTrackIsKit && view == SwingGridPanel.GridViewMode.CLIP) {
      return kitDrumCount - 1 - (scrollOffset + visualRow);
    }
    // NOTE: the Deluge session/arranger grids are bottom-up (last track at the TOP). Enabling that
    // here is a one-line change — `return trackCount - 1 - (scrollOffset + visualRow)` for
    // SONG/ARRANGEMENT — BUT the left track-name column still renders via a separate path that
    // doesn't yet consume this mapper, so flipping here alone desyncs the labels from the pads.
    // Deferred until that left-column path is routed through GridRowMapper too. trackCount is
    // already threaded through for when it's enabled.
    return scrollOffset + visualRow;
  }
}
