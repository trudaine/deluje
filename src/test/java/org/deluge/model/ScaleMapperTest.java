package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScaleMapperTest {

  @Test
  void testDiatonicPitch() {
    // Row 67 is C4 (MIDI 60)
    assertEquals(60, ScaleMapper.getDiatonicPitch(67));
    // Row 66 is D4 (MIDI 62)
    assertEquals(62, ScaleMapper.getDiatonicPitch(66));
    // Row 65 is E4 (MIDI 64)
    assertEquals(64, ScaleMapper.getDiatonicPitch(65));
    // Row 64 is F4 (MIDI 65)
    assertEquals(65, ScaleMapper.getDiatonicPitch(64));
    // Row 63 is G4 (MIDI 67)
    assertEquals(67, ScaleMapper.getDiatonicPitch(63));
    // Row 62 is A4 (MIDI 69)
    assertEquals(69, ScaleMapper.getDiatonicPitch(62));
    // Row 61 is B4 (MIDI 71)
    assertEquals(71, ScaleMapper.getDiatonicPitch(61));
    // Row 60 is C5 (MIDI 72)
    assertEquals(72, ScaleMapper.getDiatonicPitch(60));

    // Bounds checking
    assertTrue(ScaleMapper.getDiatonicPitch(0) <= 127);
    assertTrue(ScaleMapper.getDiatonicPitch(127) >= 0);
  }

  @Test
  void testChromaticPitch() {
    // Row 67 is C4 (MIDI 60)
    assertEquals(60, ScaleMapper.getChromaticPitch(67));
    // Row 66 is C#4 (MIDI 61)
    assertEquals(61, ScaleMapper.getChromaticPitch(66));
    // Row 60 is F#4 (MIDI 67)
    assertEquals(67, ScaleMapper.getChromaticPitch(60));

    // Bounds checking
    assertEquals(127, ScaleMapper.getChromaticPitch(0));
    assertEquals(0, ScaleMapper.getChromaticPitch(127));
  }

  @Test
  void testIsAccidental() {
    // White keys
    assertFalse(ScaleMapper.isAccidental(60)); // C
    assertFalse(ScaleMapper.isAccidental(62)); // D
    assertFalse(ScaleMapper.isAccidental(64)); // E
    assertFalse(ScaleMapper.isAccidental(65)); // F
    assertFalse(ScaleMapper.isAccidental(67)); // G
    assertFalse(ScaleMapper.isAccidental(69)); // A
    assertFalse(ScaleMapper.isAccidental(71)); // B
    assertFalse(ScaleMapper.isAccidental(72)); // C

    // Black keys (accidentals)
    assertTrue(ScaleMapper.isAccidental(61)); // C#
    assertTrue(ScaleMapper.isAccidental(63)); // D#
    assertTrue(ScaleMapper.isAccidental(66)); // F#
    assertTrue(ScaleMapper.isAccidental(68)); // G#
    assertTrue(ScaleMapper.isAccidental(70)); // A#
  }

  @Test
  void testGetRowPitchNormal() {
    // Chromatic mode (scaleModeEnabled = false)
    assertEquals(60, ScaleMapper.getRowPitch(67, true, false, false, null));
    assertEquals(61, ScaleMapper.getRowPitch(66, true, false, false, null));

    // Diatonic mode (scaleModeEnabled = true)
    assertEquals(60, ScaleMapper.getRowPitch(67, true, true, false, null));
    assertEquals(62, ScaleMapper.getRowPitch(66, true, true, false, null));

    // Non-synth track should fallback to 60
    assertEquals(60, ScaleMapper.getRowPitch(66, false, true, false, null));
  }

  @Test
  void testGetRowPitchFolded() {
    List<Integer> folded = Arrays.asList(72, 67, 60); // C5, G4, C4

    // In fold mode, rows map directly to the list elements
    assertEquals(72, ScaleMapper.getRowPitch(0, true, true, true, folded));
    assertEquals(67, ScaleMapper.getRowPitch(1, true, true, true, folded));
    assertEquals(60, ScaleMapper.getRowPitch(2, true, true, true, folded));

    // Out of bounds row in fold mode should fallback to normal scale mapping
    assertEquals(127, ScaleMapper.getRowPitch(3, true, true, true, folded));
  }

  @Test
  void testGetRowFromPitchNormal() {
    // Chromatic mode
    assertEquals(67, ScaleMapper.getRowFromPitch(60, true, false, false, null));
    assertEquals(66, ScaleMapper.getRowFromPitch(61, true, false, false, null));

    // Diatonic mode
    assertEquals(67, ScaleMapper.getRowFromPitch(60, true, true, false, null));
    assertEquals(66, ScaleMapper.getRowFromPitch(62, true, true, false, null));
  }

  @Test
  void testGetRowFromPitchFolded() {
    List<Integer> folded = Arrays.asList(72, 67, 60); // C5, G4, C4

    // In fold mode, we search the list
    assertEquals(0, ScaleMapper.getRowFromPitch(72, true, true, true, folded));
    assertEquals(1, ScaleMapper.getRowFromPitch(67, true, true, true, folded));
    assertEquals(2, ScaleMapper.getRowFromPitch(60, true, true, true, folded));

    // Pitch not in the folded list should return -1
    assertEquals(-1, ScaleMapper.getRowFromPitch(64, true, true, true, folded));
  }

  @Test
  void testCalculateFoldedPitches() {
    // Create a mock clip with 4 rows and 8 steps
    ClipModel clip = new ClipModel("TestClip", 4, 8);
    clip.setRowYNote(0, 60); // C4
    clip.setRowYNote(1, 64); // E4
    clip.setRowYNote(2, 67); // G4
    clip.setRowYNote(3, 72); // C5

    // No active notes yet
    assertTrue(ScaleMapper.calculateFoldedPitches(clip).isEmpty());

    // Add some active notes
    // Row 0 (C4) has an active note at step 0
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 60));
    // Row 2 (G4) has an active note at step 4
    clip.setStep(2, 4, StepData.of(true, 1.0f, 1.0f, 1.0f, 67));
    // Row 3 (C5) has an inactive note (should be ignored)
    clip.setStep(3, 2, StepData.of(false, 1.0f, 1.0f, 1.0f, 72));

    // Should only contain G4 (67) and C4 (60), sorted descending
    List<Integer> expected = Arrays.asList(67, 60);
    assertEquals(expected, ScaleMapper.calculateFoldedPitches(clip));

    // Now activate row 3 (C5)
    clip.setStep(3, 2, StepData.of(true, 1.0f, 1.0f, 1.0f, 72));
    // Sorted descending: [72, 67, 60]
    List<Integer> expected2 = Arrays.asList(72, 67, 60);
    assertEquals(expected2, ScaleMapper.calculateFoldedPitches(clip));
  }

  @Test
  void testGetKeyMidiOffset() {
    assertEquals(0, ScaleMapper.getKeyMidiOffset("C"));
    assertEquals(1, ScaleMapper.getKeyMidiOffset("C#"));
    assertEquals(1, ScaleMapper.getKeyMidiOffset("DF"));
    assertEquals(2, ScaleMapper.getKeyMidiOffset("D"));
    assertEquals(3, ScaleMapper.getKeyMidiOffset("D#"));
    assertEquals(3, ScaleMapper.getKeyMidiOffset("EF"));
    assertEquals(4, ScaleMapper.getKeyMidiOffset("E"));
    assertEquals(5, ScaleMapper.getKeyMidiOffset("F"));
    assertEquals(6, ScaleMapper.getKeyMidiOffset("F#"));
    assertEquals(6, ScaleMapper.getKeyMidiOffset("GF"));
    assertEquals(7, ScaleMapper.getKeyMidiOffset("G"));
    assertEquals(8, ScaleMapper.getKeyMidiOffset("G#"));
    assertEquals(8, ScaleMapper.getKeyMidiOffset("AF"));
    assertEquals(9, ScaleMapper.getKeyMidiOffset("A"));
    assertEquals(10, ScaleMapper.getKeyMidiOffset("A#"));
    assertEquals(10, ScaleMapper.getKeyMidiOffset("BF"));
    assertEquals(11, ScaleMapper.getKeyMidiOffset("B"));
    assertEquals(0, ScaleMapper.getKeyMidiOffset(null));
    assertEquals(0, ScaleMapper.getKeyMidiOffset("INVALID"));
  }

  @Test
  void testScaleTypeFromName() {
    assertEquals(Scales.ScaleType.MAJOR, ScaleMapper.scaleTypeFromName("Major"));
    assertEquals(Scales.ScaleType.MINOR, ScaleMapper.scaleTypeFromName("Minor"));
    assertEquals(Scales.ScaleType.MAJOR_PENTATONIC, ScaleMapper.scaleTypeFromName("Pentatonic"));
    assertEquals(
        Scales.ScaleType.MAJOR_PENTATONIC, ScaleMapper.scaleTypeFromName("Pentatonic Major"));
    assertEquals(
        Scales.ScaleType.MINOR_PENTATONIC, ScaleMapper.scaleTypeFromName("Pentatonic Minor"));
    assertEquals(Scales.ScaleType.DORIAN, ScaleMapper.scaleTypeFromName("Dorian"));
    assertEquals(Scales.ScaleType.MAJOR, ScaleMapper.scaleTypeFromName(null));
    assertEquals(Scales.ScaleType.MAJOR, ScaleMapper.scaleTypeFromName("INVALID"));
  }

  @Test
  void testIsNoteInScale() {
    // In C Major (root C, MIDI 60, scale degrees: C, D, E, F, G, A, B)
    // C (60) - Yes
    assertTrue(ScaleMapper.isNoteInScale(60, "C", "Major"));
    // D (62) - Yes
    assertTrue(ScaleMapper.isNoteInScale(62, "C", "Major"));
    // C# (61) - No
    assertFalse(ScaleMapper.isNoteInScale(61, "C", "Major"));

    // In A Minor (root A, MIDI 57, scale degrees: A, B, C, D, E, F, G)
    // A (57) - Yes
    assertTrue(ScaleMapper.isNoteInScale(57, "A", "Minor"));
    // C (60) - Yes
    assertTrue(ScaleMapper.isNoteInScale(60, "A", "Minor"));
    // G# (68) - No (natural minor does not contain G#)
    assertFalse(ScaleMapper.isNoteInScale(68, "A", "Minor"));

    // Null scale should return true (pass-through)
    assertTrue(ScaleMapper.isNoteInScale(61, "C", null));
  }

  @Test
  void testIsRootNote() {
    // Key of C (offset 0)
    assertTrue(ScaleMapper.isRootNote(60, "C")); // C4
    assertTrue(ScaleMapper.isRootNote(72, "C")); // C5
    assertTrue(ScaleMapper.isRootNote(48, "C")); // C3
    assertFalse(ScaleMapper.isRootNote(61, "C")); // C#4

    // Key of F# (offset 6)
    assertTrue(ScaleMapper.isRootNote(66, "F#")); // F#4
    assertTrue(ScaleMapper.isRootNote(54, "F#")); // F#3
    assertFalse(ScaleMapper.isRootNote(60, "F#")); // C4
  }

  @Test
  void testCalculateNoteHue() {
    // Test that the hue shift math works as expected and wraps around [0.0, 1.0]
    float baseHue = 0.5f;
    int colorOffset = 24;
    int pitch = 60;

    float expectedHue = (0.5f + (((60 + 24) * -8.0f / 3.0f) / 192.0f)) % 1.0f;
    if (expectedHue < 0) expectedHue += 1.0f;

    assertEquals(expectedHue, ScaleMapper.calculateNoteHue(pitch, baseHue, colorOffset), 1e-6f);

    // Check boundaries and wrap-around
    float hue = ScaleMapper.calculateNoteHue(127, 0.9f, 180);
    assertTrue(hue >= 0.0f && hue <= 1.0f);
  }

  @Test
  void testGetNoteName() {
    assertEquals("C4", ScaleMapper.getNoteName(60));
    assertEquals("C#4", ScaleMapper.getNoteName(61));
    assertEquals("A4", ScaleMapper.getNoteName(69));
    assertEquals("C-1", ScaleMapper.getNoteName(0));
    assertEquals("G9", ScaleMapper.getNoteName(127));
    assertEquals("---", ScaleMapper.getNoteName(-1));
  }
}
