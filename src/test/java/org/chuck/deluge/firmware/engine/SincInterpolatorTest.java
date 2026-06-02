package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.interpolate.SincInterpolator;
import org.chuck.deluge.firmware.dsp.interpolate.WindowedSincKernel;
import org.junit.jupiter.api.Test;

/**
 * Verifies the windowed-sinc sample interpolation (GAP-11): the kernel table loaded correctly, a
 * phase-aligned read is a passthrough, the kernel has unity DC gain (so levels are preserved), and
 * higher playback rates select stronger anti-aliasing kernels.
 */
public class SincInterpolatorTest {

  @Test
  public void kernel0PhaseZeroIsImpulse() {
    // Kernel 0 (no pitch shift) phase 0 is a unit impulse at tap 8. Kernels 1-6 are anti-aliasing
    // low-pass kernels (e.g. tap-8 peak ~23170 ≈ 32767/√2), not impulses — that's expected.
    assertEquals(32767, WindowedSincKernel.KERNEL[0][0][8], "kernel 0 phase0 = impulse at tap 8");
    assertTrue(
        WindowedSincKernel.KERNEL[1][0][8] < 32767,
        "kernel 1 is a band-limiting kernel, not an impulse");
  }

  @Test
  public void allKernelsHaveUnityDcGain() {
    // Each phase row must sum to ~32768 (DC gain 1) so interpolation preserves levels.
    for (int k = 0; k < WindowedSincKernel.NUM_KERNELS; k++) {
      for (int p = 0; p < WindowedSincKernel.NUM_PHASES; p++) {
        int sum = 0;
        for (int t = 0; t < WindowedSincKernel.NUM_TAPS; t++) sum += WindowedSincKernel.KERNEL[k][p][t];
        assertTrue(
            Math.abs(sum - 32768) < 2000,
            "kernel " + k + " phase " + p + " DC gain off (sum=" + sum + ")");
      }
    }
  }

  @Test
  public void phaseAlignedReadIsPassthrough() {
    float[] data = new float[64];
    for (int i = 0; i < data.length; i++) data[i] = (float) Math.sin(i * 0.3);
    int pos = 30;
    float out = SincInterpolator.interpolate(data, 1, 0, pos, 0L, 0);
    assertEquals(data[pos], out, 1e-3, "phase-0 read should return the sample unchanged");
  }

  @Test
  public void unityDcGain() {
    // Constant signal must come out (near) constant at any fractional phase (kernel sums to ~1).
    float[] data = new float[64];
    java.util.Arrays.fill(data, 0.5f);
    long[] fracs = {0L, 0x40000000L, 0x80000000L, 0xC0000000L};
    for (long f : fracs) {
      float out = SincInterpolator.interpolate(data, 1, 0, 32, f, 0);
      assertEquals(0.5f, out, 0.01f, "DC gain ~1 at frac=" + Long.toHexString(f));
    }
  }

  @Test
  public void higherRateSelectsStrongerKernel() {
    int noPitch = 16777216; // 1.0 in Q24
    int octaveUp = 16777216 * 2;
    assertEquals(0, SincInterpolator.getWhichKernel(noPitch), "no pitch shift -> sharpest kernel");
    assertTrue(
        SincInterpolator.getWhichKernel(octaveUp) > SincInterpolator.getWhichKernel(noPitch),
        "pitching up should select a stronger anti-aliasing kernel");
  }
}
