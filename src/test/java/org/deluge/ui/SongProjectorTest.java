package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import org.deluge.model.ClipModel;
import org.junit.jupiter.api.Test;

/**
 * Pins the SONG (session) view render: a track row shows its clip's step PATTERN across the row in
 * the clip's colour, on a variable-size framebuffer. Replaces eyeballing "why is only column 1 lit"
 * against hardware with a unit assertion.
 */
class SongProjectorTest {

  @Test
  void litColumnsFollowClipStepPattern_inTrackColour() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setStep(0, 0, true); // note in row 0 at step 0
    clip.setStep(3, 4, true); // note in row 3 at step 4
    Color purple = DelugeColour.clipColour(-60); // TR-808-ish

    PadCell[][] cells =
        SongProjector.project(List.of(new SongProjector.Row(clip, purple)), 16, 16, 0);

    assertEquals(16, cells.length);
    // Steps 0 and 4 lit, everything else in-range dark; colour is the track colour throughout.
    assertTrue(cells[0][0].active());
    assertTrue(cells[0][4].active());
    assertFalse(cells[0][1].active());
    assertEquals(purple, cells[0][0].colour(), "lit pad carries the clip colour");
    assertEquals(purple, cells[0][1].colour(), "unlit pad still carries the clip colour");
  }

  @Test
  void stepScroll_shiftsPatternLeft() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setStep(0, 5, true);
    Color c = DelugeColour.clipColour(0);

    // No scroll: step 5 lands in column 5.
    PadCell[][] unscrolled =
        SongProjector.project(List.of(new SongProjector.Row(clip, c)), 1, 16, 0);
    assertTrue(unscrolled[0][5].active());
    assertFalse(unscrolled[0][0].active());

    // Scroll by 5 steps: step 5 now lands in column 0.
    PadCell[][] scrolled = SongProjector.project(List.of(new SongProjector.Row(clip, c)), 1, 16, 5);
    assertTrue(scrolled[0][0].active());
  }

  @Test
  void emptyRow_isBlank_nullClipStaysDarkButColoured() {
    Color c = DelugeColour.clipColour(24);
    List<SongProjector.Row> rows =
        Arrays.asList(new SongProjector.Row(null, c), null); // row 1 has no clip, row 2 is padding

    PadCell[][] cells = SongProjector.project(rows, 2, 16, 0);

    // Track present but no clip -> dark, but keeps its colour (matches getTrackColour + setActive).
    assertFalse(cells[0][0].active());
    assertEquals(c, cells[0][0].colour());
    // Padding row above the tracks -> fully empty.
    assertFalse(cells[1][0].active());
    assertNull(cells[1][0].colour());
  }
}
