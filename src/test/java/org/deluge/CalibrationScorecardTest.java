package org.deluge;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * CAL_SONG hardware calibration scorecard. Renders the 28 purpose-built calibration presets
 * (src/test/resources/calibration/SYNTHS) through the engine's clean single-note path
 * (FidelityScorecardTest.renderSynth — one C4, unclipped) and scores each against the matching
 * segment of a hardware recording of CAL_SONG.
 *
 * <p>Unlike FidelityTestRunner.renderSongToWav (whose master chain over-drives full-velocity notes
 * into the 0.5 saturation clamp, corrupting the spectrum), renderSynth reproduces the amplitude the
 * scorecard is validated against, so the per-segment cosine measures TIMBRE, not clipping.
 *
 * <p>Recording must be at {@code -Dcal.wav} (default {@code ~/a/CAL_SONG/output_000.wav}), stereo
 * 44.1kHz, made from a firmware bin matching ../DelugeFirmware HEAD. See
 * docs/HARDWARE_CALIBRATION_RECORDING.md.
 */
public class CalibrationScorecardTest {

  static final String CAL_DIR = "src/test/resources/calibration/SYNTHS";

  @Test
  void calibrate() throws Exception {
    File rec =
        new File(
            System.getProperty(
                "cal.wav", System.getProperty("user.home") + "/a/CAL_SONG/output_000.wav"));
    Assumptions.assumeTrue(rec.isFile(), "no CAL_SONG hardware recording at " + rec);

    File[] files = new File(CAL_DIR).listFiles((d, n) -> n.endsWith(".XML") || n.endsWith(".xml"));
    Assumptions.assumeTrue(
        files != null && files.length > 0, "no calibration presets in " + CAL_DIR);
    Arrays.sort(files, Comparator.comparing(File::getName));
    List<File> presets = new ArrayList<>(Arrays.asList(files));

    List<Double> win = new ArrayList<>();
    List<String> na = new ArrayList<>();
    List<Double> ts = new ArrayList<>();

    // Reuse the exact scorecard comparison (onset-grid fit + single-window + time-resolved cosine).
    FidelityScorecardTest sc = new FidelityScorecardTest();
    sc.scoreSong(
        presets.stream().map(FidelityScorecardTest::fromPresetFile).toList(),
        rec,
        "CAL_SONG",
        win,
        na,
        ts);

    FidelityScorecardTest.summarize("CAL SINGLE-WINDOW", win);
    FidelityScorecardTest.summarize("CAL TIME-RESOLVED", ts);
    if (!na.isEmpty()) System.out.println("  not-measurable (our render silent): " + na);

    // Sanity only: this harness must actually have compared something.
    org.junit.jupiter.api.Assertions.assertFalse(ts.isEmpty(), "no segments scored");
  }
}
