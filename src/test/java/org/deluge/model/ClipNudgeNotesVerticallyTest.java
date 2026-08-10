package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class ClipNudgeNotesVerticallyTest {

  @Test
  @DisplayName("DegreeOf and DegreesBelow correctly index in-scale and out-of-scale intervals")
  public void testScaleDegreeIndexing() {
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals(); // [0, 2, 4, 5, 7, 9, 11]

    // In-scale degrees
    assertEquals(0, Scales.degreeOf(majorScale, 0)); // C
    assertEquals(1, Scales.degreeOf(majorScale, 2)); // D
    assertEquals(2, Scales.degreeOf(majorScale, 4)); // E
    assertEquals(3, Scales.degreeOf(majorScale, 5)); // F
    assertEquals(6, Scales.degreeOf(majorScale, 11)); // B

    // Out-of-scale degrees return -1
    assertEquals(-1, Scales.degreeOf(majorScale, 1)); // C#
    assertEquals(-1, Scales.degreeOf(majorScale, 3)); // D#
    assertEquals(-1, Scales.degreeOf(majorScale, 6)); // F#

    // DegreesBelow counts scale notes strictly below interval
    assertEquals(0, Scales.degreesBelow(majorScale, 0));
    assertEquals(1, Scales.degreesBelow(majorScale, 1)); // C is below C#
    assertEquals(1, Scales.degreesBelow(majorScale, 2)); // C is below D
    assertEquals(2, Scales.degreesBelow(majorScale, 3)); // C, D are below D#
    assertEquals(4, Scales.degreesBelow(majorScale, 6)); // C, D, E, F are below F#
  }

  @Test
  @DisplayName("ComputeTransposeShiftTable matches C++ InstrumentClip::nudgeNotesVertically table")
  public void testTransposeShiftTable() {
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals();

    // 1. Single scale-step up (+1) in Major scale
    int[] shiftUp = Scales.computeTransposeShiftTable(majorScale, 1, true);
    assertEquals(2, shiftUp[0]); // C (0) -> D (2)
    assertEquals(1, shiftUp[1]); // C# (1) -> D (2) (out-of-scale lands on D)
    assertEquals(2, shiftUp[2]); // D (2) -> E (4)
    assertEquals(1, shiftUp[3]); // D# (3) -> E (4) (out-of-scale lands on E)
    assertEquals(1, shiftUp[4]); // E (4) -> F (5)

    // 2. Single scale-step down (-1) in Major scale
    int[] shiftDown = Scales.computeTransposeShiftTable(majorScale, -1, true);
    assertEquals(-1, shiftDown[0]); // C (0) -> B (-1 octave wrap -> pitch 11, shift -1)
    assertEquals(-1, shiftDown[1]); // C# (1) -> C (0) (out-of-scale lands on C)
    assertEquals(-2, shiftDown[2]); // D (2) -> C (0)
    assertEquals(-1, shiftDown[3]); // D# (3) -> D (2) (out-of-scale lands on D)

    // 3. Octave step (+7 scale steps for 7-note major scale)
    int[] shiftOctave = Scales.computeTransposeShiftTable(majorScale, 7, true);
    for (int i = 0; i < 12; i++) {
      assertEquals(12, shiftOctave[i], "Octave shift must be +12 semitones for all intervals");
    }
  }

  @Test
  @DisplayName(
      "ClipModel.nudgeNotesVertically enforces 0-127 MIDI range bounds and rejects overflows")
  public void testRangeBoundsEnforcement() {
    ClipModel clip = new ClipModel("Test Clip", 128, 16);
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals();

    // Add note row near top boundary (127)
    clip.getOrCreateRow(127);
    clip.getOrCreateRow(60);

    // Attempt to nudge up when top note is 127
    boolean successUp = clip.nudgeNotesVertically(1, false, true, majorScale, 0);
    assertFalse(successUp, "Nudging up when max pitch is 127 must return false");
    assertEquals(127, clip.getOrCreateRow(127).getPitch(), "Row 127 pitch must remain untouched");
    assertEquals(60, clip.getOrCreateRow(60).getPitch(), "Row 60 pitch must remain untouched");

    // Add note row near bottom boundary (0)
    ClipModel lowClip = new ClipModel("Low Clip", 128, 16);
    lowClip.getOrCreateRow(0);
    lowClip.getOrCreateRow(60);

    boolean successDown = lowClip.nudgeNotesVertically(-1, false, true, majorScale, 0);
    assertFalse(successDown, "Nudging down when min pitch is 0 must return false");
    assertEquals(0, lowClip.getOrCreateRow(0).getPitch(), "Row 0 pitch must remain untouched");
    assertEquals(60, lowClip.getOrCreateRow(60).getPitch(), "Row 60 pitch must remain untouched");
  }

  @Test
  @DisplayName("ClipModel.nudgeNotesVertically rejects drum Kits and empty clips")
  public void testRejectsKitsAndEmptyClips() {
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals();

    // Drum kit clip
    ClipModel kitClip = new ClipModel("Kit Clip", 16, 16);
    kitClip.setIsKit(true);
    kitClip.getOrCreateRow(36);
    assertFalse(
        kitClip.nudgeNotesVertically(1, false, true, majorScale, 0),
        "Kit clip transpose must be rejected");

    // Empty synth clip
    ClipModel emptyClip = new ClipModel("Empty Clip", 128, 16);
    assertFalse(
        emptyClip.nudgeNotesVertically(1, false, true, majorScale, 0),
        "Empty clip transpose must be rejected");

    // Zero direction
    ClipModel synthClip = new ClipModel("Synth Clip", 128, 16);
    synthClip.getOrCreateRow(60);
    assertFalse(
        synthClip.nudgeNotesVertically(0, false, true, majorScale, 0),
        "Zero direction transpose must be rejected");
  }

  @Test
  @DisplayName("ClipModel.nudgeNotesVertically transposes valid clips accurately")
  public void testValidTransposition() {
    ClipModel clip = new ClipModel("Diatonic Clip", 128, 16);
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals(); // C major

    // C4 (60), D4 (62), E4 (64)
    clip.getOrCreateRow(60);
    clip.getOrCreateRow(62);
    clip.getOrCreateRow(64);

    boolean moved = clip.nudgeNotesVertically(1, false, true, majorScale, 0);
    assertTrue(moved, "Valid transposition must return true");

    // Check new pitches (C->D=62, D->E=64, E->F=65)
    assertNotNull(clip.getNoteRowsMap().get(62), "C4 (60) transposed +2 -> D4 (62)");
    assertNotNull(clip.getNoteRowsMap().get(64), "D4 (62) transposed +2 -> E4 (64)");
    assertNotNull(clip.getNoteRowsMap().get(65), "E4 (64) transposed +1 -> F4 (65)");
    assertEquals(3, clip.getNoteRowsMap().size(), "Total note row count must remain 3");
  }
}
