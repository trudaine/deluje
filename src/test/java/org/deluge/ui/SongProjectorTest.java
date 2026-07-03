package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import org.deluge.model.ClipModel;
import org.deluge.model.StepData;
import org.junit.jupiter.api.Test;

/**
 * Pins the SONG (session) view render (session_view.cpp renderAsSingleRow / note_row.cpp
 * renderRow): each lit column is the note's per-pitch main colour (velocity-scaled for a head,
 * {@code forTail} for a held-note tail), the highest note-row winning; unlit columns are
 * null/black.
 */
class SongProjectorTest {

  /** The head colour: pitch hue scaled by the C velocity fraction (65 + v + v/2)/255, v=0..127. */
  private static Color head(int pitch, int offset, float velocity) {
    Color main = DelugeColour.fromHue((pitch + offset) * -8 / 3);
    int v = Math.round(velocity * 127f);
    int num = 65 + v + v / 2;
    return new Color(
        Math.min(255, main.getRed() * num / 255),
        Math.min(255, main.getGreen() * num / 255),
        Math.min(255, main.getBlue() * num / 255));
  }

  // setStep(r,s,true) uses velocity 0.8, gate 1.0 (ClipModel).
  private static final float DEF_VEL = 0.8f;

  @Test
  void litColumnsAreVelocityScaledNoteHues_unlitAreNull() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(0, 60); // C4
    clip.setRowYNote(3, 67); // G4
    clip.setStep(0, 0, true);
    clip.setStep(3, 4, true);

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 16, 16, 0);

    assertEquals(head(60, 0, DEF_VEL), cells[0][0].colour(), "step 0 = C4 head");
    assertEquals(head(67, 0, DEF_VEL), cells[0][4].colour(), "step 4 = G4 head");
    assertFalse(cells[0][1].active());
    assertNull(cells[0][1].colour());
  }

  @Test
  void higherNoteRowWinsWhenTwoHeadsShareAColumn() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(1, 60);
    clip.setRowYNote(5, 72); // higher index
    clip.setStep(1, 2, true);
    clip.setStep(5, 2, true);

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 0);
    assertEquals(head(72, 0, DEF_VEL), cells[0][2].colour(), "highest-index row wins the column");
  }

  @Test
  void heldNoteDrawsForTailInFollowingColumns() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(0, 60);
    // A note at step 2 held for 3 steps (gate 3.0): head at 2, tails at 3 and 4, nothing at 5.
    clip.setStep(0, 2, StepData.of(true, 1.0f, 3.0f, 1.0f, 60));

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 0);
    Color tail = DelugePadButton.getTailColor(DelugeColour.fromHue((60) * -8 / 3));

    assertEquals(head(60, 0, 1.0f), cells[0][2].colour(), "head at the note start");
    assertEquals(tail, cells[0][3].colour(), "tail one step in");
    assertEquals(tail, cells[0][4].colour(), "tail two steps in (2+3=5 > 4)");
    assertNull(cells[0][5].colour(), "note ended (2+3=5 not > 5)");
  }

  @Test
  void colourOffsetShiftsTheHue() {
    ClipModel clip = new ClipModel("A", 8, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, true);

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 24)), 1, 16, 0);
    assertEquals(head(60, 24, DEF_VEL), cells[0][0].colour(), "colourOffset added before fromHue");
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

    PadCell[][] scrolled = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 5);
    assertEquals(head(60, 0, DEF_VEL), scrolled[0][0].colour());
  }

  @Test
  void columnsBeyondClipLengthAreGrey() {
    ClipModel clip = new ClipModel("A", 8, 8); // 8-step clip shown in a 16-wide row
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, true);

    PadCell[][] cells = SongProjector.project(List.of(new SongProjector.Row(clip, 0)), 1, 16, 0);

    assertEquals(head(60, 0, DEF_VEL), cells[0][0].colour(), "note within the pattern");
    assertNull(cells[0][3].colour(), "defined-but-empty column is black (null)");
    assertEquals(
        SongProjector.UNDEFINED_AREA, cells[0][8].colour(), "past the 8-step clip -> grey");
    assertEquals(SongProjector.UNDEFINED_AREA, cells[0][15].colour(), "still grey at the edge");
  }

  @Test
  void emptyAndNullRows_areBlank() {
    List<SongProjector.Row> rows = Arrays.asList(new SongProjector.Row(null, 24), null);
    PadCell[][] cells = SongProjector.project(rows, 2, 16, 0);
    assertNull(cells[0][0].colour());
    assertNull(cells[1][0].colour());
  }
}
