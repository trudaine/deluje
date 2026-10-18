package org.deluge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.deluge.midi.MidiTakeover;
import org.deluge.model.ChordModel;
import org.deluge.model.ChordModel.ChordType;
import org.deluge.model.ChordModel.Quality;
import org.deluge.model.Scales;
import org.deluge.model.tuning.ScalaScale;
import org.deluge.model.tuning.ScalaScaleParser;
import org.junit.jupiter.api.Test;

/**
 * Tests C++ firmware parity for {@link ChordModel}, {@link MidiTakeover}, and Scala {@code .scl}
 * spec compliance for {@link ScalaScaleParser}.
 */
class ChordMidiScalaParityTest {

  private static ChordType chordByName(String name) {
    for (ChordType ct : ChordModel.CHORD_TYPES) {
      if (ct.name().equals(name)) {
        return ct;
      }
    }
    throw new IllegalArgumentException("Unknown chord: " + name);
  }

  private static String[] names(ChordType[] types) {
    return Arrays.stream(types).map(ChordType::name).toArray(String[]::new);
  }

  @Test
  void chordQualityMatchesCppGetChordQuality() {
    // C chords.cpp:28-52 — MAJ3 + P5 without MIN7 is MAJOR; with MIN7 is DOMINANT.
    for (String name : new String[] {"M", "M7", "M9", "6", "add9", "M7#11"}) {
      assertEquals(Quality.MAJOR, ChordModel.getQuality(chordByName(name)), name);
    }
    for (String name : new String[] {"7", "7#9", "7b9", "9", "7#11", "13"}) {
      assertEquals(Quality.DOMINANT, ChordModel.getQuality(chordByName(name)), name);
    }
    for (String name : new String[] {"-", "-7", "-9", "-6", "-add9"}) {
      assertEquals(Quality.MINOR, ChordModel.getQuality(chordByName(name)), name);
    }
    for (String name : new String[] {"DIM", "DIM7", "-7b5"}) {
      assertEquals(Quality.DIMINISHED, ChordModel.getQuality(chordByName(name)), name);
    }
    for (String name : new String[] {"AUG", "M7#5"}) {
      assertEquals(Quality.AUGMENTED, ChordModel.getQuality(chordByName(name)), name);
    }
    for (String name : new String[] {"SUS2", "SUS4", "7b5", "7sus4"}) {
      assertEquals(Quality.OTHER, ChordModel.getQuality(chordByName(name)), name);
    }

    // Fewer than 3 distinct pitch classes returns OTHER (C chords.cpp:29-31).
    ChordType dyad = new ChordType("5", "Power", new int[] {0, 7, 12});
    assertEquals(Quality.OTHER, ChordModel.getQuality(dyad));
  }

  @Test
  void chordsForScaleDegreeReturnsExactChordTypes() {
    assertArrayEquals(
        new String[] {"M", "M7", "M9", "6", "add9", "M7#11"},
        names(ChordModel.chordsForScaleDegree(0)));
    assertArrayEquals(
        new String[] {"-", "-7", "-6", "-add9", "-7b5"}, names(ChordModel.chordsForScaleDegree(1)));
    assertArrayEquals(new String[] {"-", "-7", "AUG"}, names(ChordModel.chordsForScaleDegree(2)));
    assertArrayEquals(
        new String[] {"M", "M7", "6", "M7#11"}, names(ChordModel.chordsForScaleDegree(3)));
    assertArrayEquals(
        new String[] {"7", "7b5", "7#9", "7b9", "9", "13", "7sus4", "7#11"},
        names(ChordModel.chordsForScaleDegree(4)));
    assertArrayEquals(
        new String[] {"-", "-7", "-6", "-9"}, names(ChordModel.chordsForScaleDegree(5)));
    assertArrayEquals(
        new String[] {"DIM", "-7b5", "DIM7"}, names(ChordModel.chordsForScaleDegree(6)));

    // Negative degrees wrap cleanly with floorMod (degree -1 == degree 6).
    assertArrayEquals(
        names(ChordModel.chordsForScaleDegree(6)), names(ChordModel.chordsForScaleDegree(-1)));
  }

  @Test
  void chordLibraryRowsCoverAllChordTypesWithExactRowOrder() {
    assertNull(ChordModel.CHORD_LIBRARY_ROWS[0]);
    String[] expectedRows = {
      null, "M", "-", "DIM", "AUG", "6", "-6", "7", "-7", "M7", "SUS2", "SUS4", "-7b5", "DIM7",
      "7#9", "9", "M9", "7b5", "7#11", "7sus4", "7b9", "M7#5", "-add9", "M7#11", "add9", "13", "-9"
    };
    assertEquals(expectedRows.length, ChordModel.CHORD_LIBRARY_ROWS.length);
    Set<String> seen = new HashSet<>();
    for (int i = 1; i < expectedRows.length; i++) {
      assertNotNull(ChordModel.CHORD_LIBRARY_ROWS[i], "Row " + i);
      assertEquals(expectedRows[i], ChordModel.CHORD_LIBRARY_ROWS[i].name(), "Row " + i);
      seen.add(ChordModel.CHORD_LIBRARY_ROWS[i].name());
    }
    assertEquals(ChordModel.CHORD_TYPES.length, seen.size(), "Every CHORD_TYPES entry is present");
  }

  @Test
  void buildChordForNegativeScaleDegreeAndNoteName() {
    assertEquals("B", ChordModel.noteName(-1));
    List<Integer> notes =
        ChordModel.buildChordForScaleDegree(60, Scales.ScaleType.MAJOR, -1, chordByName("DIM"));
    assertEquals(List.of(59, 62, 65), notes);
  }

  @Test
  void midiTakeoverScaleModeFirstTurnExactMatchPicksUpAndRunwayMatchesCpp() {
    // 1. Exact match on first CC turn picks up immediately (C midi_takeover.cpp:94-103).
    MidiTakeover takeover = new MidiTakeover();
    takeover.setMode(MidiTakeover.Mode.SCALE);
    assertEquals(64, takeover.process(74, 64, 64));

    // 2. Positive runway divides by (127 - hardwareValue), not (127 - previous).
    // C midi_takeover.cpp:117, 134:
    // previous = 27, hardwareValue = 77 -> hardwareChange = 50, hardwareMaxDelta = 127 - 77 = 50.
    // changePercentage = 50 / 50 = 1.0 -> newVirtual = 100 + 27 * 1.0 = 127.
    // (The buggy denominator 50 / (50 + 50) = 0.5 gave 100 + 13.5 = 114.)
    takeover.reset();
    assertEquals(-1, takeover.process(10, 27, 100));
    assertEquals(127, takeover.process(10, 77, 100));

    // 3. Negative runway divides by hardwareValue, not previous.
    // C midi_takeover.cpp:118, 147:
    // virtual = 20, previous = 100, hardwareValue = 50 -> hardwareChange = -50,
    // hardwareMinDelta = 50, changePercentage = -50 / 50 = -1.0 -> newVirtual = 20 - 20 = 0.
    // (The buggy denominator -50 / (50 - (-50)) = -0.5 gave 20 - 10 = 10.)
    takeover.reset();
    assertEquals(-1, takeover.process(11, 100, 20));
    assertEquals(0, takeover.process(11, 50, 20));
  }

  @Test
  void scalaParserParsesWholeIntegersAsRatiosAndCentsWithDot() throws Exception {
    String scl =
        "! octave_and_cents.scl\n"
            + "Test integer ratio vs cents\n"
            + "3\n"
            + "!\n"
            + " 700.0 fifth in cents\n"
            + " 2\n"
            + " 3 ! tritave integer ratio\n";
    ScalaScale scale =
        ScalaScaleParser.parse(
            new ByteArrayInputStream(scl.getBytes(StandardCharsets.UTF_8)), "test");
    assertEquals("Test integer ratio vs cents", scale.getDescription());
    assertEquals(3, scale.getStepsCount());
    assertEquals(1.0, scale.getStepRatios()[0], 1e-12);
    assertEquals(Math.pow(2.0, 700.0 / 1200.0), scale.getStepRatios()[1], 1e-12);
    assertEquals(2.0, scale.getStepRatios()[2], 1e-12);
    assertEquals(3.0, scale.getOctaveRatio(), 1e-12);
  }

  @Test
  void scalaParserAllowsEmptyDescriptionLine() throws Exception {
    String scl = "! empty_desc.scl\n" + "\n" + "2\n" + "3/2\n" + "2\n";
    ScalaScale scale =
        ScalaScaleParser.parse(
            new ByteArrayInputStream(scl.getBytes(StandardCharsets.UTF_8)), "empty_desc");
    assertEquals("", scale.getDescription());
    assertEquals(2, scale.getStepsCount());
    assertEquals(1.5, scale.getStepRatios()[1], 1e-12);
    assertEquals(2.0, scale.getOctaveRatio(), 1e-12);
    assertTrue(scale.mtof(62) > scale.mtof(60));
  }
}
