package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.EqProcessor;
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
  private static StereoSample[] nyquist(int n) {
    StereoSample[] b = new StereoSample[n];
    for (int i = 0; i < n; i++) {
      int v = (i % 2 == 0) ? A : -A;
      b[i] = new StereoSample(v, v);
    }
    return b;
  }

  /** Constant DC level (pure low-frequency content). */
  private static StereoSample[] dc(int n) {
    StereoSample[] b = new StereoSample[n];
    for (int i = 0; i < n; i++) b[i] = new StereoSample(A, A);
    return b;
  }

  private static double tailRms(StereoSample[] b, int from) {
    double s = 0;
    for (int i = from; i < b.length; i++) s += (double) b[i].l * b[i].l;
    return Math.sqrt(s / (b.length - from));
  }

  @Test
  public void flatIsTransparent() {
    int n = 64;
    StereoSample[] in = nyquist(n);
    int[] orig = new int[n];
    for (int i = 0; i < n; i++) orig[i] = in[i].l;
    new EqProcessor().process(in, n, 0, 0);
    for (int i = 0; i < n; i++)
      assertEquals(orig[i], in[i].l, "EQ flat must be transparent at " + i);
  }

  @Test
  public void trebleBoostRaisesHighFrequencies() {
    int n = 2000;
    StereoSample[] flat = nyquist(n);
    StereoSample[] boost = nyquist(n);
    new EqProcessor().process(boost, n, 0, MAX); // +full treble

    double flatRms = tailRms(flat, 1000);
    double boostRms = tailRms(boost, 1000);
    assertTrue(
        boostRms > flatRms * 1.2,
        "Treble boost should raise HF energy (boost=" + boostRms + ", flat=" + flatRms + ")");
  }

  @Test
  public void bassBoostRaisesLowFrequencies() {
    int n = 4000;
    StereoSample[] flat = dc(n);
    StereoSample[] boost = dc(n);
    new EqProcessor().process(boost, n, MAX, 0); // +full bass

    double flatRms = tailRms(flat, 3000);
    double boostRms = tailRms(boost, 3000);
    assertTrue(
        boostRms > flatRms * 1.2,
        "Bass boost should raise LF energy (boost=" + boostRms + ", flat=" + flatRms + ")");
  }
}
