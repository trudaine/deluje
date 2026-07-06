package org.deluge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * FM-family hardware calibration scorecard. Renders the 7 native-format ludocard FM presets that
 * make up FM_CAL.XML (generated via AllSynthsFidelityTest#generateAllSynthsSong) through the clean
 * single-note path (FidelityScorecardTest.renderSynth — one C4, unclipped) and scores each against
 * the matching segment of a clean, alignment-unambiguous hardware recording of that song.
 *
 * <p>Purpose: the main scorecard's per-synth alignment is fragile exactly for FM bells (see
 * FIDELITY_GAP_ANALYSIS §4.1bis), and the C5 FM fixtures are invalid (§4.1quater). This isolated
 * recording (one FM preset per note, 4 s note + 4 s gap so bell tails fully decay) gives the FIRST
 * trustworthy per-preset FM ground truth, to finally settle whether our FM index is faithful-to-C
 * or genuinely too hot.
 *
 * <p>Recording at {@code -Dfm.wav} (default {@code ~/a/FM_CAL/output_000.wav}); presets read from
 * the card at {@code -Dfm.synths} (default {@code ~/ludocard/SYNTHS}). Self-skips if either is
 * absent. The preset list + order MUST match FM_CAL.XML (filename sort).
 */
public class FmCalibrationScorecardTest {

  // Exactly the presets in FM_CAL.XML, in filename-sorted (= playback) order.
  static final String[] FM_PRESETS = {
    "050 FM Basic Bass.XML", // anchor: known-faithful (~0.88) — validates alignment
    "068 FM Bells 1.XML", // worst scorer (time 0.100) — high modulator transpose
    "069 FM Bells 2.XML",
    "084 FM Narrow Band.XML",
    "093 FM Distorted Bells.XML",
    "095 Harsh FM Feedback.XML", // clean feedback-path signal
    "107 FM LPG Percussion.XML" // renders silent in-engine — does it sound on HW?
  };

  @Test
  void calibrate() throws Exception {
    File rec =
        new File(
            System.getProperty(
                "fm.wav", System.getProperty("user.home") + "/a/FM_CAL/output_000.wav"));
    Assumptions.assumeTrue(rec.isFile(), "no FM_CAL hardware recording at " + rec);

    File synthDir =
        new File(
            System.getProperty("fm.synths", System.getProperty("user.home") + "/ludocard/SYNTHS"));
    Assumptions.assumeTrue(synthDir.isDirectory(), "no FM preset dir at " + synthDir);

    List<File> presets = new ArrayList<>();
    for (String name : FM_PRESETS) {
      File f = new File(synthDir, name);
      Assumptions.assumeTrue(f.isFile(), "missing FM preset " + f);
      presets.add(f);
    }

    List<Double> win = new ArrayList<>();
    List<String> na = new ArrayList<>();
    List<Double> ts = new ArrayList<>();

    // Reuse the exact scorecard comparison (onset-grid fit + single-window + time-resolved cosine).
    FidelityScorecardTest sc = new FidelityScorecardTest();
    sc.scoreSong(presets, rec, "FM_CAL", win, na, ts);

    FidelityScorecardTest.summarize("FM SINGLE-WINDOW", win);
    FidelityScorecardTest.summarize("FM TIME-RESOLVED", ts);
    if (!na.isEmpty()) System.out.println("  not-measurable (our render silent): " + na);

    org.junit.jupiter.api.Assertions.assertFalse(ts.isEmpty(), "no FM segments scored");
  }
}
