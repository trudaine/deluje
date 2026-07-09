package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies C++ hardware parity (`// C microtuning.cpp`) for Scala (.scl) custom microtuning parser,
 * interval ratio/cent decoding, and frequency calculation.
 */
public class ScalaTuningParityTest {

  @AfterEach
  public void cleanup() {
    ScalaTuning.setActiveTuning(null);
  }

  @Test
  public void testParseStandardScalaFile() {
    String scl =
        """
        ! test_just.scl
        5-limit Just Intonation Major Triad
        3
        !
        5/4
        3/2
        2/1
        """;

    ScalaTuning tuning = ScalaTuning.parseScl(scl);
    assertEquals("5-limit Just Intonation Major Triad", tuning.getDescription());
    assertEquals(3, tuning.getNotesPerPeriod());
    assertEquals(1200.0, tuning.getPeriodCents(), 0.001);

    double[] cents = tuning.getNoteCents();
    assertEquals(0.0, cents[0], 0.001);
    // 5/4 ratio is ~386.3137 cents
    assertEquals(386.3137, cents[1], 0.01);
    // 3/2 ratio is ~701.955 cents
    assertEquals(701.955, cents[2], 0.01);
    // 2/1 ratio is 1200.0 cents
    assertEquals(1200.0, cents[3], 0.01);
  }

  @Test
  public void testActiveTuningOverrides12TetFrequency() {
    ScalaTuning.setActiveTuning(null);
    // Standard 12-TET A4 (midi 69) = 440 Hz
    assertEquals(440.0, ScalaTuning.midiToFrequencyHz(69), 0.01);

    // Quarter-tone scale (24-TET): 24 steps per octave (50 cents per step)
    double[] cents = new double[25];
    for (int i = 0; i <= 24; i++) {
      cents[i] = i * 50.0;
    }
    ScalaTuning quarterTone = new ScalaTuning("24-TET Quarter Tone", cents);
    ScalaTuning.setActiveTuning(quarterTone);

    // Root C4 (60) is 261.63 Hz
    double rootHz = ScalaTuning.midiToFrequencyHz(60);
    assertEquals(261.6255, rootHz, 0.01);

    // +1 step (midi 61) in 24-TET is +50 cents = root * 2^(50/1200)
    double step1Hz = ScalaTuning.midiToFrequencyHz(61);
    assertEquals(rootHz * Math.pow(2.0, 50.0 / 1200.0), step1Hz, 0.01);
  }
}
