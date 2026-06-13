package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Guards the 2026-06-13 hardware-comparison finding: a minimum-resonance ladder LPF must NOT
 * distort a clean low sine. The bug was upstream of the filter — a song's minimum resonance (raw
 * Q31 {@code 0x80000000}) round-tripped through the preset float path floored at -2^29 instead of
 * INT_MIN, becoming a moderate resonance that pushed {@code processedResonance} past the ladder's
 * tanh-saturation threshold (76% 2nd harmonic on a quiet 65 Hz sine). Here we feed the ladder its
 * true minimum-resonance config and assert it stays clean.
 */
public class LadderFilterCleanlinessTest {

  private static double secondHarmonicRatio(FilterSet.FilterMode mode) {
    LpLadderFilter f = new LpLadderFilter();
    // INT_MIN resonance = the firmware's true minimum (what a song's 0x80000000 must produce),
    // cutoff wide open, no morph.
    f.setConfig(2_000_000_000, Integer.MIN_VALUE, mode, Integer.MIN_VALUE, Functions.ONE_Q31);

    int sr = 44100;
    double f0 = 65.41; // C2 — the note that exposed the bug
    int n = sr / 2;
    int[] buf = new int[n];
    for (int i = 0; i < n; i++) {
      buf[i] = (int) (0.05 * 2147483647.0 * Math.sin(2 * Math.PI * f0 * i / sr));
    }
    f.doFilter(buf, 0, n, 1);

    double[] mag = new double[2];
    for (int h = 0; h < 2; h++) {
      double fr = f0 * (h + 1);
      double k = 2 * Math.PI * fr / sr, c = 2 * Math.cos(k), s1 = 0, s2 = 0;
      for (int i = n / 4; i < n; i++) {
        double v = buf[i] / 2147483648.0;
        double s0 = v + c * s1 - s2;
        s2 = s1;
        s1 = s0;
      }
      mag[h] = Math.sqrt(Math.max(s1 * s1 + s2 * s2 - c * s1 * s2, 0));
    }
    return mag[1] / mag[0];
  }

  @Test
  void minResonanceLadderDoesNotDistortCleanSine() {
    // Pre-fix this was ~0.76 for the song path; the ladder itself with true-min resonance is clean.
    assertTrue(
        secondHarmonicRatio(FilterSet.FilterMode.TRANSISTOR_12DB) < 0.05,
        "12dB ladder must not add a 2nd harmonic to a clean sine at minimum resonance");
    assertTrue(
        secondHarmonicRatio(FilterSet.FilterMode.TRANSISTOR_24DB) < 0.05,
        "24dB ladder must not add a 2nd harmonic to a clean sine at minimum resonance");
  }
}
