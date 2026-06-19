package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Verifies the ported bass/treble EQ (GAP-10): flat is transparent, treble boost raises
 * high-frequency energy, bass boost raises low-frequency (DC-ish) energy. Was entirely absent from
 * the pure firmware engine before.
 */
public class EqParityTest {

  private static final int MAX = Integer.MAX_VALUE;
  private static final int A = 400_000_000;

  /** Alternating ±A (maximum high-frequency content). */
  private static int[][] nyquist(int n) {
    int[][] b = new int[n][2];
    for (int i = 0; i < n; i++) {
      int v = (i % 2 == 0) ? A : -A;
      b[i][0] = v;
      b[i][1] = v;
    }
    return b;
  }

  /** Constant DC level (pure low-frequency content). */
  private static int[][] dc(int n) {
    int[][] b = new int[n][2];
    for (int i = 0; i < n; i++) {
      b[i][0] = A;
      b[i][1] = A;
    }
    return b;
  }

  private static double tailRms(int[][] b, int from) {
    double s = 0;
    for (int i = from; i < b.length; i++) s += (double) b[i][0] * b[i][0];
    return Math.sqrt(s / (b.length - from));
  }

  @Test
  public void flatIsTransparent() {
    int n = 64;
    int[][] in = nyquist(n);
    int[] orig = new int[n];
    for (int i = 0; i < n; i++) orig[i] = in[i][0];
    new org.deluge.firmware2.Eq().process(in, n, 0, 0, 0, 0);
    for (int i = 0; i < n; i++)
      assertEquals(orig[i], in[i][0], "EQ flat must be transparent at " + i);
  }

  @Test
  public void trebleBoostRaisesHighFrequencies() {
    int n = 2000;
    int[][] flat = nyquist(n);
    int[][] boost = nyquist(n);
    new org.deluge.firmware2.Eq().process(boost, n, 0, MAX, 0, 0); // +full treble

    double flatRms = tailRms(flat, 1000);
    double boostRms = tailRms(boost, 1000);
    assertTrue(
        boostRms > flatRms * 1.2,
        "Treble boost should raise HF energy (boost=" + boostRms + ", flat=" + flatRms + ")");
  }

  @Test
  public void bassBoostRaisesLowFrequencies() {
    int n = 4000;
    int[][] flat = dc(n);
    int[][] boost = dc(n);
    new org.deluge.firmware2.Eq().process(boost, n, MAX, 0, 0, 0); // +full bass

    double flatRms = tailRms(flat, 3000);
    double boostRms = tailRms(boost, 3000);
    assertTrue(
        boostRms > flatRms * 1.2,
        "Bass boost should raise LF energy (boost=" + boostRms + ", flat=" + flatRms + ")");
  }
}
