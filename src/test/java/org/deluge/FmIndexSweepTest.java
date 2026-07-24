package org.deluge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Voice;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Sweeps the native-FM modulation-index multiplier ({@link Voice#testFmIndexScale}) for each FM_CAL
 * preset and scores every multiplier against the VALID, alignment-confirmed FM_CAL hardware
 * recording (anchor 050 FM Basic Bass ~0.86 proves alignment). Tells us, per patch, whether a lower
 * index matches hardware better (index-scaling error) or whether no multiplier helps (structural FM
 * gap) — the question the invalid C5 fixtures could never answer (FIDELITY_GAP_ANALYSIS
 * §4.1quater).
 */
public class FmIndexSweepTest {

  static final double[] SCALES = {0.0625, 0.125, 0.25, 0.375, 0.5, 1.0};

  @Test
  void sweep() throws Exception {
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
    for (String name : FmCalibrationScorecardTest.FM_PRESETS) {
      File f = new File(synthDir, name);
      Assumptions.assumeTrue(f.isFile(), "missing FM preset " + f);
      presets.add(f);
    }

    FidelityScorecardTest sc = new FidelityScorecardTest();
    // scoresByScale[s] = time-resolved cosine per preset at SCALES[s].
    double[][] scoresByScale = new double[SCALES.length][];
    try {
      for (int s = 0; s < SCALES.length; s++) {
        Voice.testFmIndexScale = SCALES[s];
        List<Double> win = new ArrayList<>();
        List<String> na = new ArrayList<>();
        List<Double> ts = new ArrayList<>();
        sc.scoreSong(
            presets.stream().map(FidelityScorecardTest::fromPresetFile).toList(),
            rec,
            "FM_CAL x" + SCALES[s],
            win,
            na,
            ts);
        scoresByScale[s] = ts.stream().mapToDouble(Double::doubleValue).toArray();
      }
    } finally {
      Voice.testFmIndexScale = 1.0; // never leak into other tests
    }

    System.out.println("\n=== FM INDEX SWEEP (time-resolved cosine vs valid FM_CAL hardware) ===");
    StringBuilder hdr = new StringBuilder(String.format("%-26s", "preset"));
    for (double v : SCALES) hdr.append(String.format("x%-6.2f", v));
    hdr.append("  best");
    System.out.println(hdr);
    for (int p = 0; p < presets.size(); p++) {
      StringBuilder row =
          new StringBuilder(String.format("%-26s", presets.get(p).getName().replace(".XML", "")));
      double best = -2;
      double bestScale = 1.0;
      for (int s = 0; s < SCALES.length; s++) {
        double v = scoresByScale[s][p];
        row.append(String.format("%-7.3f", v));
        if (v > best) {
          best = v;
          bestScale = SCALES[s];
        }
      }
      row.append(String.format("  x%.2f (%.3f)%s", bestScale, best, bestScale == 1.0 ? " =C" : ""));
      System.out.println(row);
    }
  }
}
