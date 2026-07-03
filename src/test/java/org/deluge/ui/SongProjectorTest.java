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
 * Pins the SONG (session) view render: a track row shows its clip's step PATTERN, and every lit
 * column is coloured by the per-pitch rainbow of the note there ({@code getMainColourFromY}), the
 * highest note-row winning — matching session_view.cpp renderAsSingleRow. Unlit columns are black.
 */
class SongProjectorTest {

  private static Color hue(int pitch, int offset) {
    return DelugeColour.fromHue((pitch + offset) * -8 / 3);
  }

  @Test
  void litColumnsAreColouredByNotePitch_unlitAreBlack() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(0, 60); // row 0 = C4
    clip.setRowYNote(3, 67); // row 3 = G4
    clip.setStep(0, 0, true); // C4 note at step 0
    clip.setStep(3, 4, true); // G4 note at step 4

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 16, 16, 0);

    assertEquals(16, cells.length);
    assertTrue(cells[0][0].active());
    assertEquals(hue(60, 0), cells[0][0].colour(), "step 0 shows C4's hue");
    assertTrue(cells[0][4].active());
    assertEquals(hue(67, 0), cells[0][4].colour(), "step 4 shows G4's hue");
    // No note at step 1 -> unlit.
    assertFalse(cells[0][1].active());
    assertNull(cells[0][1].colour());
  }

  @Test
  void higherNoteRowWinsWhenTwoNotesShareAColumn() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(1, 60);
    clip.setRowYNote(5, 72); // higher index
    clip.setStep(1, 2, true);
    clip.setStep(5, 2, true); // both at step 2 -> row 5 (C5) wins

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 0);
    assertEquals(hue(72, 0), cells[0][2].colour(), "highest-index note row wins the column");
  }

  @Test
  void colourOffsetShiftsTheHue() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, true);

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 24)), 1, 16, 0);
    assertEquals(hue(60, 24), cells[0][0].colour(), "colourOffset added before fromHue");
  }

  @Test
  void stepScroll_shiftsPatternLeft() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 5, true);

    PadCell[][] unscrolled =
        SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 0);
    assertTrue(unscrolled[0][5].active());
    assertFalse(unscrolled[0][0].active());

    // Scroll by 5 steps: step 5 now lands in column 0.
    PadCell[][] scrolled = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 5);
    assertTrue(scrolled[0][0].active());
    assertEquals(hue(60, 0), scrolled[0][0].colour());
  }

  @Test
  void emptyAndNullRows_areBlank() {
    List<SongProjector.Row> rows =
        Arrays.asList(new SongProjector.Row(null, 24), null); // no clip, then padding

    PadCell[][] cells = SongProjector.project(rows, 2, 16, 0);
    assertFalse(cells[0][0].active());
    assertNull(cells[0][0].colour());
    assertFalse(cells[1][0].active());
    assertNull(cells[1][0].colour());
  }
}
