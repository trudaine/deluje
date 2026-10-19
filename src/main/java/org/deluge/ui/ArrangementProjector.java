package org.deluge.ui;

import java.awt.Color;
import java.util.List;
import org.deluge.model.ArrangerClip;

/**
 * Pure projection of the (unbounded) arranger timeline onto a fixed {@code rows × cols} pad
 * framebuffer. No Swing, no controller, no instance state — so the colour of every cell in a known
 * arrangement can be pinned by a unit test.
 *
 * <p>This is a faithful extraction of the arranger clip-instance render in {@link
 * ArrangerGridPanel#refreshInPlace} ({@code ArrangerGridPanel.java:600-645}), which mirrors C
 * {@code arranger_view.cpp:2360-2442} and {@code ClipInstance::getColour} ({@code
 * clip_instance.cpp:31-37}):
 *
 * <ul>
 *   <li>Cell colour is derived from the placement's clip <b>section</b> ({@code
 *       defaultClipSectionColours[section]}, via {@link DelugeColour#sectionColour}), NOT the
 *       clip's own {@code colourOffset}.
 *   <li>HEAD square (where the instance starts within this column) = full section colour.
 *   <li>Loop-boundary square (column tick lands on a {@code loopLength} multiple) = {@code
 *       dim(base, 3)}.
 *   <li>Mid-loop body square = {@code dim(forBlur(base), 3)}.
 *   <li>Arrangement-only placement (no backing clip) = {@code dim(base, 4)} preview.
 * </ul>
 *
 * <p>The Y axis (rows) is bounded — {@code rowToTrack} maps each display row to a finite model
 * track index (or -1 for an empty/padding row). The X axis (cols) is the unbounded one: column
 * {@code c} represents ticks {@code [scrollTicks + c*ticksPerColumn, +ticksPerColumn)}, so
 * scrolling right is just a larger {@code scrollTicks} — the array never grows.
 */
final class ArrangementProjector {
  private ArrangementProjector() {}

  /**
   * @param timeline the full, unbounded list of arranger placements (all tracks)
   * @param rowToTrack {@code rowToTrack[r]} = model track index for display row {@code r}, or -1
   *     for an empty row (top-to-bottom display order, already resolved by the caller's row model)
   * @param rows framebuffer height (e.g. 16 or 24) — comes from {@code gridMode.rows}
   * @param cols framebuffer width (main pad columns, excluding the MUTE/SOLO sidebar)
   * @param scrollTicks absolute tick at the left edge (song's {@code xScrollArrangementView})
   * @param ticksPerColumn ticks represented by one column (song's {@code xZoomArrangementView})
   */
  static PadCell[][] project(
      List<ArrangerClip> timeline,
      int[] rowToTrack,
      int rows,
      int cols,
      int scrollTicks,
      int ticksPerColumn) {
    PadCell[][] out = new PadCell[rows][cols];
    for (int r = 0; r < rows; r++) {
      int track = (r < rowToTrack.length) ? rowToTrack[r] : -1;
      for (int c = 0; c < cols; c++) {
        int colTick = scrollTicks + c * ticksPerColumn;
        ArrangerClip ac = clipAt(timeline, track, colTick, ticksPerColumn);
        out[r][c] = ac == null ? PadCell.EMPTY : cellFor(ac, colTick, ticksPerColumn);
      }
    }
    return out;
  }

  /**
   * The placement covering {@code [colTick, colTick + ticksPerColumn)} on {@code track}, or null.
   * Mirrors {@code arranger_view.cpp:2348-2389} ({@code searchMultiple(squareEndPos) - 1}): when a
   * placement starts mid-column ({@code colTick < startTicks < colTick + ticksPerColumn}), it still
   * covers the column and renders as a HEAD square; when two placements overlap the same column,
   * the latest {@code startTicks < colTick + ticksPerColumn} wins.
   */
  private static ArrangerClip clipAt(
      List<ArrangerClip> timeline, int track, int colTick, int ticksPerColumn) {
    if (track < 0) return null;
    int colEnd = colTick + Math.max(1, ticksPerColumn);
    ArrangerClip best = null;
    for (ArrangerClip ac : timeline) {
      if (ac.trackIndex() == track
          && ac.startTicks() < colEnd
          && ac.startTicks() + ac.durationTicks() > colTick) {
        if (best == null || ac.startTicks() >= best.startTicks()) {
          best = ac;
        }
      }
    }
    return best;
  }

  /** Resolves the final LED colour for a covered column (shared with ArrangerGridPanel.java). */
  private static PadCell cellFor(ArrangerClip ac, int colTick, int ticksPerColumn) {
    return PadCell.of(colourFor(ac, colTick, ticksPerColumn), true);
  }

  /**
   * The final LED colour for the clip instance {@code ac} at the column starting on {@code
   * colTick}: HEAD square = full section colour, loop boundary = {@code dim(base, 3)}, mid-loop
   * body = {@code dim(forBlur(base), 3)}, arrangement-only preview = {@code dim(base, 4)}. Shared
   * by {@link #project} (whole-grid, unit-tested) and the per-cell arranger render path so both
   * agree.
   */
  static Color colourFor(ArrangerClip ac, int colTick, int ticksPerColumn) {
    org.deluge.model.ClipModel clip = ac.clip();
    org.deluge.model.AudioTrackModel.AudioClip audioClip = ac.audioClip();
    boolean hasBackingClip = clip != null || audioClip != null;
    boolean arrOnly =
        clip != null
            ? clip.isArrangementOnly()
            : (audioClip != null && audioClip.isArrangementOnly());
    int section =
        clip != null ? clip.getSection() : (audioClip != null ? audioClip.getSection() : 255);
    // ClipInstance::getColour (clip_instance.cpp:31-37): grey monochrome(128) for a missing OR
    // arrangement-only clip, otherwise the section colour.
    boolean grey = !hasBackingClip || arrOnly;
    Color base = grey ? DelugeColour.monochrome(128) : DelugeColour.sectionColour(section);
    boolean isHead = ac.startTicks() >= colTick && ac.startTicks() < colTick + ticksPerColumn;

    // Head square = the full instance colour (arranger_view.cpp:2375-2376), whether or not it has a
    // clip.
    if (isHead) {
      return base;
    }
    // Follower squares (arranger_view.cpp:2388-2439):
    if (!hasBackingClip) {
      return DelugeColour.dim(base, 4); // no backing clip yet -> dim preview
    }
    int loopLen = clip != null ? clip.getLoopLength() : audioClip.getLength();
    int rel = colTick - ac.startTicks();
    boolean loopStart = loopLen > 0 && (rel % loopLen == 0);
    if (loopStart) {
      return DelugeColour.dim(base, arrOnly ? 2 : 3); // arrangement-only loop starts are dimmer
    }
    return arrOnly
        ? DelugeColour.dim(base, 4)
        : DelugeColour.dim(DelugePadButton.getBlurColor(base), 3);
  }
}
