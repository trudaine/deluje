package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.tuning.ScalaScale;
import org.chuck.deluge.model.tuning.ScalaScaleParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * High-fidelity regressions testing for alternative microtonal tuning Scala systems. Asserts
 * comment filters, fraction ratios, tritave repeats, and transpositions offsets.
 */
public class TuningFidelityTest {

  @AfterEach
  public void tearDown() {
    DelugeEngineDSL.setScalaScale(null); // Reset global scale state after each test
  }

  @Test
  public void testScalaParserRatiosAndCents() throws Exception {
    // 5-tone Slendro-inspired mock scale (ratios and cents mix!)
    String sclContent =
        "! slendro.scl\n"
            + "! Comment\n"
            + "Mock Slendro scale with mixed fractions and cents\n"
            + "5\n"
            + "!\n"
            + " 9/8\n"
            + " 5/4\n"
            + " 700.0\n"
            + " 27/16\n"
            + " 2/1\n";

    ScalaScale scale =
        ScalaScaleParser.parse(new ByteArrayInputStream(sclContent.getBytes()), "Slendro");

    assertEquals("Slendro", scale.getName());
    assertEquals("Mock Slendro scale with mixed fractions and cents", scale.getDescription());
    assertEquals(5, scale.getStepsCount());

    // Verify octave wrap ratio is standard 2.0
    assertEquals(2.0, scale.getOctaveRatio());

    // Verify step ratios
    double[] ratios = scale.getStepRatios();
    assertEquals(1.0, ratios[0]); // Unison
    assertEquals(9.0 / 8.0, ratios[1]); // Step 1: 9/8 ratio
    assertEquals(5.0 / 4.0, ratios[2]); // Step 2: 5/4 ratio
    assertEquals(Math.pow(2.0, 700.0 / 1200.0), ratios[3]); // Step 3: 700.0 cents
    assertEquals(27.0 / 16.0, ratios[4]); // Step 4: 27/16 ratio
  }

  @Test
  public void testScalaParserWithTrailingComments() throws Exception {
    // 3-tone Bohlen-Pierce-inspired scale (custom tritave wrap!)
    String sclContent =
        "! bp.scl\n"
            + "Bohlen-Pierce 3-tone subset\n"
            + "3\n"
            + " 1200.0 ! Trailing comment text here!\n"
            + " 2400.0 ! Another comment!\n"
            + " 3/1 ! Tritave wrap ratio is 3.0 instead of 2.0!\n";

    ScalaScale scale =
        ScalaScaleParser.parse(new ByteArrayInputStream(sclContent.getBytes()), "BP");

    assertEquals(3, scale.getStepsCount());
    assertEquals(3.0, scale.getOctaveRatio()); // Tritave wrap ratio is 3.0!

    double[] ratios = scale.getStepRatios();
    assertEquals(1.0, ratios[0]);
    assertEquals(Math.pow(2.0, 1200.0 / 1200.0), ratios[1]); // 2.0 ratio
    assertEquals(Math.pow(2.0, 2400.0 / 1200.0), ratios[2]); // 4.0 ratio
  }

  @Test
  public void testTuningMtofCalculations() throws Exception {
    String sclContent =
        "! pythagorean.scl\n"
            + "Pythagorean 12-Tone Scale\n"
            + "12\n"
            + " 256/243\n"
            + " 9/8\n"
            + " 32/27\n"
            + " 81/64\n"
            + " 4/3\n"
            + " 1024/729\n"
            + " 3/2\n"
            + " 128/81\n"
            + " 27/16\n"
            + " 16/9\n"
            + " 243/128\n"
            + " 2/1\n";

    ScalaScale scale =
        ScalaScaleParser.parse(new ByteArrayInputStream(sclContent.getBytes()), "Pythagorean");

    // Set A440 reference standard: Middle C is Midi Note 60 (at 261.625565 Hz)
    scale.setReferenceMidiNote(60);
    scale.setReferenceFrequency(261.625565);

    // Assert root key is exactly the reference frequency
    assertEquals(261.625565, scale.mtof(60), 1e-5);

    // Assert step 2 (MIDI 62) is exactly C * 9/8
    assertEquals(261.625565 * 9.0 / 8.0, scale.mtof(62), 1e-5);

    // Assert step 7 (MIDI 67 - Perfect Fifth) is exactly C * 3/2
    assertEquals(261.625565 * 3.0 / 2.0, scale.mtof(67), 1e-5);

    // Assert octave transpositions: note 72 (C5) is double Middle C
    assertEquals(261.625565 * 2.0, scale.mtof(72), 1e-5);

    // Assert negative transpositions: note 48 (C3) is half Middle C
    assertEquals(261.625565 * 0.5, scale.mtof(48), 1e-5);

    // Assert fractional detuning: Middle C + 50 cents detuning
    double expectedCentsDetuned = 261.625565 * Math.pow(2.0, 0.5 / 12.0);
    assertEquals(expectedCentsDetuned, scale.mtof(60.5), 1e-5);
  }

  @Test
  public void testKeyboardMapParsing() throws Exception {
    // Standard white keys only mapping for 7-note scale (C to C octaves)
    String kbmContent =
        "! white_keys.kbm\n"
            + "12 ! map size\n"
            + "0 ! first note\n"
            + "127 ! last note\n"
            + "60 ! middle/reference note\n"
            + "261.625565 ! reference frequency\n"
            + "7 ! octave degree scale repeat step\n"
            + "!\n"
            + "0\n"
            + "x ! C# silent\n"
            + "1\n"
            + "x ! D# silent\n"
            + "2\n"
            + "3\n"
            + "x ! F# silent\n"
            + "4\n"
            + "x ! G# silent\n"
            + "5\n"
            + "x ! A# silent\n"
            + "6\n";

    org.chuck.deluge.model.tuning.ScalaKeyboardMap kbm =
        org.chuck.deluge.model.tuning.ScalaKeyboardMapParser.parse(
            new ByteArrayInputStream(kbmContent.getBytes()), "WhiteKeysOnly");

    assertEquals("WhiteKeysOnly", kbm.getName());
    assertEquals(12, kbm.getMapSize());
    assertEquals(0, kbm.getFirstMidiNote());
    assertEquals(127, kbm.getLastMidiNote());
    assertEquals(60, kbm.getMiddleMidiNote());
    assertEquals(261.625565, kbm.getReferenceFrequency());
    assertEquals(7, kbm.getOctaveDegree());

    int[] mapping = kbm.getKeyMapping();
    assertNotNull(mapping);
    assertEquals(0, mapping[0]); // C
    assertEquals(-1, mapping[1]); // C# (silent/unmapped)
    assertEquals(1, mapping[2]); // D
    assertEquals(-1, mapping[3]); // D# (silent/unmapped)
    assertEquals(2, mapping[4]); // E
    assertEquals(3, mapping[5]); // F
    assertEquals(-1, mapping[6]); // F# (silent/unmapped)
    assertEquals(4, mapping[7]); // G
    assertEquals(-1, mapping[8]); // G# (silent/unmapped)
    assertEquals(5, mapping[9]); // A
    assertEquals(-1, mapping[10]); // A# (silent/unmapped)
    assertEquals(6, mapping[11]); // B
  }

  @Test
  public void testKeyboardMapMtofCalculations() throws Exception {
    String sclContent =
        "! pythagorean_7note.scl\n"
            + "7-Note Pythagorean diatonic scale\n"
            + "7\n"
            + " 9/8\n"
            + " 81/64\n"
            + " 4/3\n"
            + " 3/2\n"
            + " 27/16\n"
            + " 243/128\n"
            + " 2/1\n";

    ScalaScale scale =
        ScalaScaleParser.parse(new ByteArrayInputStream(sclContent.getBytes()), "Pythagorean7");

    String kbmContent =
        "12\n"
            + "0\n"
            + "127\n"
            + "60\n"
            + "261.625565\n"
            + "7\n"
            + "0\n"
            + "x\n"
            + "1\n"
            + "x\n"
            + "2\n"
            + "3\n"
            + "x\n"
            + "4\n"
            + "x\n"
            + "5\n"
            + "x\n"
            + "6\n";

    org.chuck.deluge.model.tuning.ScalaKeyboardMap kbm =
        org.chuck.deluge.model.tuning.ScalaKeyboardMapParser.parse(
            new ByteArrayInputStream(kbmContent.getBytes()), "WhiteKeysOnly");
    scale.setKeyboardMap(kbm);

    // Middle C (note 60) maps to unison (degree 0)
    assertEquals(261.625565, scale.mtof(60), 1e-5);

    // C# (note 61) is silent/unmapped -> returns 0.0 frequency!
    assertEquals(0.0, scale.mtof(61), 1e-5);

    // D (note 62) maps to degree 1 (ratio 9/8)
    assertEquals(261.625565 * 9.0 / 8.0, scale.mtof(62), 1e-5);

    // E (note 64) maps to degree 2 (ratio 81/64)
    assertEquals(261.625565 * 81.0 / 64.0, scale.mtof(64), 1e-5);

    // F (note 65) maps to degree 3 (ratio 4/3)
    assertEquals(261.625565 * 4.0 / 3.0, scale.mtof(65), 1e-5);

    // F# (note 66) is silent/unmapped -> 0.0!
    assertEquals(0.0, scale.mtof(66), 1e-5);

    // G (note 67) maps to degree 4 (ratio 3/2)
    assertEquals(261.625565 * 3.0 / 2.0, scale.mtof(67), 1e-5);

    // Octave transposition: C5 (note 72) maps to 1 octave up (degree 0 * 2.0)
    assertEquals(261.625565 * 2.0, scale.mtof(72), 1e-5);
  }
}
