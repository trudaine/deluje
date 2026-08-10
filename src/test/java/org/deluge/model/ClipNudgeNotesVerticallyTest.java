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

    // Attempt to nudge up when top note is 127.
    // Assert against the map itself, never getOrCreateRow(): that helper constructs
    // new NoteRowModel(key) when the key is absent, so "getOrCreateRow(127).getPitch() == 127"
    // holds whether or not the nudge mutated anything. It cannot fail, and the earlier revision
    // of this test relied on it.
    boolean successUp = clip.nudgeNotesVertically(1, false, true, majorScale, 0);
    assertFalse(successUp, "Nudging up when max pitch is 127 must return false");
    assertEquals(
        java.util.Set.of(127, 60), clip.getNoteRowsMap().keySet(), "Rows must be untouched");
    assertEquals(127, clip.getNoteRowsMap().get(127).getPitch(), "Row 127 pitch must be untouched");
    assertEquals(60, clip.getNoteRowsMap().get(60).getPitch(), "Row 60 pitch must be untouched");

    // Add note row near bottom boundary (0)
    ClipModel lowClip = new ClipModel("Low Clip", 128, 16);
    lowClip.getOrCreateRow(0);
    lowClip.getOrCreateRow(60);

    boolean successDown = lowClip.nudgeNotesVertically(-1, false, true, majorScale, 0);
    assertFalse(successDown, "Nudging down when min pitch is 0 must return false");
    assertEquals(
        java.util.Set.of(0, 60), lowClip.getNoteRowsMap().keySet(), "Rows must be untouched");
    assertEquals(0, lowClip.getNoteRowsMap().get(0).getPitch(), "Row 0 pitch must be untouched");
    assertEquals(60, lowClip.getNoteRowsMap().get(60).getPitch(), "Row 60 pitch must be untouched");
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

    // Row identity (the map key, which addresses the step grid) is unchanged; only pitch moves.
    assertEquals(
        java.util.Set.of(60, 62, 64),
        clip.getNoteRowsMap().keySet(),
        "Row indices must survive a transpose — they address the step grid");
    assertEquals(62, clip.getNoteRowsMap().get(60).getPitch(), "C4 (60) -> D4 (62)");
    assertEquals(64, clip.getNoteRowsMap().get(62).getPitch(), "D4 (62) -> E4 (64)");
    assertEquals(65, clip.getNoteRowsMap().get(64).getPitch(), "E4 (64) -> F4 (65)");
  }

  @Test
  @DisplayName("Rows that land on the same pitch both survive the transpose")
  public void testCollidingRowsAreNotDestroyed() {
    ClipModel clip = new ClipModel("Collide Clip", 128, 16);
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals();

    // C (in scale, shifts +2) and C# (out of scale, shifts +1) both arrive at D. The C keeps
    // both rows because its noteRows is an array and y is a plain field; a map re-keyed by pitch
    // would silently drop one of them, taking every note in it.
    clip.getOrCreateRow(60);
    clip.getOrCreateRow(61);

    assertTrue(clip.nudgeNotesVertically(1, false, true, majorScale, 0));

    assertEquals(
        java.util.Set.of(60, 61), clip.getNoteRowsMap().keySet(), "Both rows must still exist");
    assertEquals(62, clip.getNoteRowsMap().get(60).getPitch(), "C -> D");
    assertEquals(62, clip.getNoteRowsMap().get(61).getPitch(), "C# -> D (same pitch, own row)");
  }

  @Test
  @DisplayName("Transposing rewrites the pitch carried by each active step")
  public void testStepPitchFollowsTheRow() {
    ClipModel clip = new ClipModel("Step Clip", 128, 16);
    int[] majorScale = Scales.ScaleType.MAJOR.getIntervals();

    clip.setStep(60, 0, new StepData(true, 0.9f, 1.0f, 1.0f, 60, 2, 0.5f, 0.25f));
    clip.setStep(60, 4, new StepData(true, 0.5f, 0.5f, 1.0f, 60, 0, 0.0f, 0.0f));
    assertEquals(60, clip.getStep(60, 0).pitch());

    assertTrue(clip.nudgeNotesVertically(1, false, true, majorScale, 0));

    // StepData carries its own pitch, and that copy — not the row's — is what reaches the audio
    // bridge and the saved file. A stale one transposes the model while the engine keeps playing
    // the old note.
    assertEquals(62, clip.getStep(60, 0).pitch(), "Active step must follow its row");
    assertEquals(62, clip.getStep(60, 4).pitch(), "Every active step, not just the first");
    // Everything other than pitch survives; StepData.of() would have zeroed these.
    assertEquals(2, clip.getStep(60, 0).iterance(), "Iterance must survive the transpose");
    assertEquals(0.5f, clip.getStep(60, 0).fill(), "Fill must survive the transpose");
    assertEquals(0.25f, clip.getStep(60, 0).nudge(), "Nudge must survive the transpose");
  }
}
