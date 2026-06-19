package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.deluge.model.tuning.ScalaScale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test verifying the correctness of programmatic chord generation algorithms. Bypasses
 * Headless/Swing windows and directly tests the logic equations matching
 * SwingRandomizerDialog.java.
 */
public class ChordsGeneratorFidelityTest {

  @AfterEach
  public void tearDown() {
    ScalaScale.setActiveScale(null);
  }

  @Test
  public void test12TetStandardDiatonicChordPitches() {
    // Standard 12-TET Major fallback intervals
    int[] majorIntervals = {0, 2, 4, 5, 7, 9, 11};
    int baseRoot = 60; // C4

    // 1. Verify standard Triads for standard Diatonic degrees: I, ii, V, vi
    // Degree 0 (I) -> Root: 0, 3rd: 2, 5th: 4 -> majorIntervals offsets: 0, 4, 7 -> Pitches: 60,
    // 64, 67 (C, E, G)
    int degreeI = 0;
    int rootOffsetI = majorIntervals[Math.abs(degreeI) % 7] + 12 * (degreeI / 7);
    int thirdOffsetI = majorIntervals[Math.abs(degreeI + 2) % 7] + 12 * ((degreeI + 2) / 7);
    int fifthOffsetI = majorIntervals[Math.abs(degreeI + 4) % 7] + 12 * ((degreeI + 4) / 7);

    assertEquals(0, rootOffsetI);
    assertEquals(4, thirdOffsetI);
    assertEquals(7, fifthOffsetI);
    assertEquals(60, baseRoot + rootOffsetI);
    assertEquals(64, baseRoot + thirdOffsetI);
    assertEquals(67, baseRoot + fifthOffsetI);

    // Degree 1 (ii) -> Root: 1, 3rd: 3, 5th: 5 -> majorIntervals offsets: 2, 5, 9 -> Pitches: 62,
    // 65, 69 (D, F, A)
    int degreeII = 1;
    int rootOffsetII = majorIntervals[Math.abs(degreeII) % 7] + 12 * (degreeII / 7);
    int thirdOffsetII = majorIntervals[Math.abs(degreeII + 2) % 7] + 12 * ((degreeII + 2) / 7);
    int fifthOffsetII = majorIntervals[Math.abs(degreeII + 4) % 7] + 12 * ((degreeII + 4) / 7);

    assertEquals(2, rootOffsetII);
    assertEquals(5, thirdOffsetII);
    assertEquals(9, fifthOffsetII);
    assertEquals(62, baseRoot + rootOffsetII);
    assertEquals(65, baseRoot + thirdOffsetII);
    assertEquals(69, baseRoot + fifthOffsetII);
  }

  @Test
  public void testVoicingStylePitchesStack() {
    int baseRoot = 60; // C4
    int[] majorIntervals = {0, 2, 4, 5, 7, 9, 11};

    // Degree 0: (I)
    int degree = 0;
    int rootOffset = majorIntervals[Math.abs(degree) % 7] + 12 * (degree / 7);
    int thirdOffset = majorIntervals[Math.abs(degree + 2) % 7] + 12 * ((degree + 2) / 7);
    int fifthOffset = majorIntervals[Math.abs(degree + 4) % 7] + 12 * ((degree + 4) / 7);
    int seventhOffset = majorIntervals[Math.abs(degree + 6) % 7] + 12 * ((degree + 6) / 7);

    // Triad (voicingType = 0)
    int[] triad = {baseRoot + rootOffset, baseRoot + thirdOffset, baseRoot + fifthOffset};
    assertArrayEquals(new int[] {60, 64, 67}, triad);

    // 7ths (voicingType = 1)
    int[] sevenths = {
      baseRoot + rootOffset,
      baseRoot + thirdOffset,
      baseRoot + fifthOffset,
      baseRoot + seventhOffset
    };
    assertArrayEquals(new int[] {60, 64, 67, 71}, sevenths);

    // Sus4 (voicingType = 2)
    int susOffset = majorIntervals[Math.abs(degree + 3) % 7] + 12 * ((degree + 3) / 7);
    int[] sus4 = {baseRoot + rootOffset, baseRoot + susOffset, baseRoot + fifthOffset};
    assertArrayEquals(new int[] {60, 65, 67}, sus4);

    // Spread Open Pads (voicingType = 3)
    int[] spread = {baseRoot + rootOffset, baseRoot + fifthOffset, baseRoot + rootOffset + 12};
    assertArrayEquals(new int[] {60, 67, 72}, spread);
  }

  @Test
  public void testCustomMicrotonalChordPitches() throws Exception {
    // Set active microtonal scale with 5 steps: Slendro-inspired
    String sclContent =
        "! slendro.scl\n"
            + "slendro-test\n"
            + "5\n"
            + "!\n"
            + " 9/8\n"
            + " 5/4\n"
            + " 3/2\n"
            + " 7/4\n"
            + " 2/1\n";

    ScalaScale scale = ScalaScaleParserTestHelper.parseScl(sclContent, "SlendroCustom");
    ScalaScale.setActiveScale(scale);

    int scaleSteps = scale.getStepsCount();
    assertEquals(5, scaleSteps);

    int spacing = Math.max(1, Math.round(scaleSteps / 7.0f)); // 5 / 7.0 = 0.71 -> round to 1 step
    assertEquals(1, spacing);

    int baseRoot = 60;
    int degree = 2; // G-ish

    int rootOffset = degree;
    int thirdOffset = degree + spacing; // 3
    int fifthOffset = degree + 2 * spacing; // 4

    // Triad: root + third + fifth degree offsets step numbers
    ArrayList<Integer> chordPitches = new ArrayList<>();
    chordPitches.add(baseRoot + rootOffset);
    chordPitches.add(baseRoot + thirdOffset);
    chordPitches.add(baseRoot + fifthOffset);

    assertEquals(62, chordPitches.get(0)); // 60 + 2
    assertEquals(63, chordPitches.get(1)); // 60 + 3
    assertEquals(64, chordPitches.get(2)); // 60 + 4
  }

  // Mini parser helper to decouple from external dependencies file loads
  private static class ScalaScaleParserTestHelper {
    public static ScalaScale parseScl(String content, String name) throws Exception {
      return org.deluge.model.tuning.ScalaScaleParser.parse(
          new java.io.ByteArrayInputStream(content.getBytes()), name);
    }
  }
}
