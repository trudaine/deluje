package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;
import org.deluge.model.ArrangerClip;
import org.deluge.model.ClipModel;
import org.junit.jupiter.api.Test;

/**
 * Pins the arranger clip-instance colours the way the C {@code arranger_view.cpp renderRow} draws
 * them, on a fixed {@code 24×16} framebuffer projected from a known unbounded timeline. Colour
 * parity that used to require eyeballing hardware photos is now a unit assertion.
 */
class ArrangementProjectorTest {

  /** Section-0 clip, loopLength = 1 column of ticks, placed at tick 0 on the bottom track. */
  private static ArrangerClip section0Clip(int track, int startTicks, int durationTicks, int loop) {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setSection(0);
    clip.setLoopLength(loop);
    return new ArrangerClip(track, clip, startTicks, durationTicks);
  }

  @Test
  void head_isFullSectionColour_bodyIsDimmed_dimensionsAreVariable() {
    // One placement on track 0, starting at tick 0, spanning 4 columns (zoom=192 ticks/col),
    // looping
    // every column so each body square lands on a loop boundary.
    int zoom = 192;
    ArrangerClip ac = section0Clip(0, 0, 4 * zoom, zoom);
    int[] rowToTrack = new int[24];
    java.util.Arrays.fill(rowToTrack, -1);
    rowToTrack[23] = 0; // bottom display row shows track 0

    PadCell[][] cells = ArrangementProjector.project(List.of(ac), rowToTrack, 24, 16, 0, zoom);

    // Fixed 24×16 framebuffer regardless of the unbounded timeline.
    assertEquals(24, cells.length);
    assertEquals(16, cells[0].length);

    Color section0 = DelugeColour.sectionColour(0);
    PadCell head = cells[23][0];
    assertTrue(head.active());
    assertEquals(section0, head.colour(), "HEAD square = full section colour");

    // Column 1 starts at tick 192 == 1*loopLen -> loop boundary -> dim(base, 3).
    PadCell loopBoundary = cells[23][1];
    assertTrue(loopBoundary.active());
    assertEquals(
        DelugeColour.dim(section0, 3), loopBoundary.colour(), "loop boundary = dim(base,3)");

    // Empty display rows and columns beyond the placement are dark/inactive.
    assertFalse(cells[0][0].active());
    assertNull(cells[0][0].colour());
    assertFalse(cells[23][4].active(), "column 4 (tick 768) is past the 4-column placement");
  }

  @Test
  void midLoopBody_isBlurredThenDimmed() {
    // loopLength longer than a column so column 1 is mid-loop (not on a boundary) -> forBlur+dim.
    int zoom = 192;
    int loop = 5 * zoom; // boundary only every 5 columns
    ArrangerClip ac = section0Clip(0, 0, 4 * zoom, loop);
    int[] rowToTrack = {0};

    PadCell[][] cells = ArrangementProjector.project(List.of(ac), rowToTrack, 1, 16, 0, zoom);

    Color section0 = DelugeColour.sectionColour(0);
    assertEquals(section0, cells[0][0].colour(), "col 0 is HEAD");
    Color expectedBody = DelugeColour.dim(DelugePadButton.getBlurColor(section0), 3);
    assertEquals(expectedBody, cells[0][1].colour(), "mid-loop body = dim(forBlur(base),3)");
  }

  @Test
  void horizontalScroll_slidesWindow_withoutGrowingArray() {
    int zoom = 192;
    ArrangerClip ac = section0Clip(0, 10 * zoom, 4 * zoom, zoom); // starts far to the right
    int[] rowToTrack = {0};

    // Unscrolled: the placement is off-screen (starts at column 10, only 16 cols but tick 10*192).
    PadCell[][] atZero = ArrangementProjector.project(List.of(ac), rowToTrack, 1, 16, 0, zoom);
    assertFalse(atZero[0][0].active());

    // Scroll so tick 10*zoom is at the left edge -> HEAD reappears in column 0, same 16-wide array.
    PadCell[][] scrolled =
        ArrangementProjector.project(List.of(ac), rowToTrack, 1, 16, 10 * zoom, zoom);
    assertEquals(16, scrolled[0].length);
    assertTrue(scrolled[0][0].active());
    assertEquals(DelugeColour.sectionColour(0), scrolled[0][0].colour());
  }

  @Test
  void arrangementOnlyPlacement_isDimPreview() {
    int zoom = 192;
    ArrangerClip ac = new ArrangerClip(0, null, 0, 4 * zoom); // no backing clip
    int[] rowToTrack = {0};

    PadCell[][] cells = ArrangementProjector.project(List.of(ac), rowToTrack, 1, 16, 0, zoom);

    Color grey = DelugeColour.sectionColour(-1); // monochrome(128)
    assertEquals(DelugeColour.dim(grey, 4), cells[0][0].colour(), "arrangement-only = dim(base,4)");
  }
}
