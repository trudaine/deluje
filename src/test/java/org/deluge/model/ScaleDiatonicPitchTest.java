package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the faithful {@code Song::getYNoteFromYVisual} port: scale mode must use the song's actual
 * scale intervals and root, not a hardcoded C-major table. C major must be unchanged (no regression
 * for existing songs); minor / pentatonic must map their real intervals.
 */
class ScaleDiatonicPitchTest {

  private static final int[] MAJOR = Scales.ScaleType.MAJOR.getIntervals(); // 0,2,4,5,7,9,11
  private static final int[] MINOR = Scales.ScaleType.MINOR.getIntervals(); // 0,2,3,5,7,8,10
  private static final int[] MINOR_PENT = Scales.ScaleType.MINOR_PENTATONIC.getIntervals(); // 0,3,5,7,10

  @Test
  void cMajor_unchangedFromLegacyAnchor() {
    // Row 67 = C4 (60), stepping up the C-major scale as rows decrease.
    assertEquals(60, ScaleMapper.getDiatonicPitch(67, MAJOR, 0)); // C4
    assertEquals(62, ScaleMapper.getDiatonicPitch(66, MAJOR, 0)); // D4
    assertEquals(64, ScaleMapper.getDiatonicPitch(65, MAJOR, 0)); // E4
    assertEquals(65, ScaleMapper.getDiatonicPitch(64, MAJOR, 0)); // F4
    assertEquals(72, ScaleMapper.getDiatonicPitch(60, MAJOR, 0)); // C5 (7 rows up = octave)
    // Legacy no-arg entry point stays C major.
    assertEquals(60, ScaleMapper.getDiatonicPitch(67));
    assertEquals(72, ScaleMapper.getDiatonicPitch(60));
  }

  @Test
  void cMinor_usesMinorThirdAndSixth() {
    assertEquals(60, ScaleMapper.getDiatonicPitch(67, MINOR, 0)); // C4
    assertEquals(62, ScaleMapper.getDiatonicPitch(66, MINOR, 0)); // D4
    assertEquals(63, ScaleMapper.getDiatonicPitch(65, MINOR, 0)); // Eb4 (minor 3rd, was E4 under major)
    assertEquals(68, ScaleMapper.getDiatonicPitch(62, MINOR, 0)); // Ab4 (minor 6th)
    assertEquals(72, ScaleMapper.getDiatonicPitch(60, MINOR, 0)); // C5
  }

  @Test
  void aMinor_rootShifted() {
    int rootA = 9;
    assertEquals(69, ScaleMapper.getDiatonicPitch(67, MINOR, rootA)); // A4 at the reference row
    assertEquals(71, ScaleMapper.getDiatonicPitch(66, MINOR, rootA)); // B4
    assertEquals(72, ScaleMapper.getDiatonicPitch(65, MINOR, rootA)); // C5
  }

  @Test
  void minorPentatonic_fiveNotesPerOctave() {
    // 5-note scale: 5 rows == one octave (the old hardcoded 7 would have been wrong here).
    assertEquals(60, ScaleMapper.getDiatonicPitch(67, MINOR_PENT, 0)); // C4
    assertEquals(63, ScaleMapper.getDiatonicPitch(66, MINOR_PENT, 0)); // Eb4
    assertEquals(72, ScaleMapper.getDiatonicPitch(62, MINOR_PENT, 0)); // C5 after 5 rows
  }
}
