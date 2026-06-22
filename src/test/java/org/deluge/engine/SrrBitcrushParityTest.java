package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Verifies the ported bitcrush + sample-rate-reduction DSP (GAP-09). Bitcrushing must zero the low
 * bits of each sample; sample-rate reduction must hold samples (decimation staircase). Both were
 * entirely absent from the pure firmware engine before.
 */
public class SrrBitcrushParityTest {

  private static final int OFF = Integer.MIN_VALUE;

  private static int[][] ramp(int n) {
    int[][] b = new int[n][2];
    for (int i = 0; i < n; i++) {
      int v = (int) (Math.sin(i * 0.05) * 1.0e9);
      b[i][0] = v;
      b[i][1] = v;
    }
    return b;
  }

  @Test
  public void bitcrushZerosLowBits() {
    int n = 128;
    int[][] buf = ramp(n);
    int[] postFX = {2147483647};
    // Max bitcrush (norm≈1 -> positivePreset 7 -> mask clears low 26 bits), SRR off.
    new org.deluge.firmware2.SrrBitcrush().process(buf, n, Integer.MAX_VALUE, OFF, postFX);

    int lowBitsMask = (1 << 26) - 1;
    boolean anyNonZero = false;
    for (int i = 0; i < n; i++) {
      assertEquals(0, buf[i][0] & lowBitsMask, "low 26 bits must be cleared by bitcrush at " + i);
      if (buf[i][0] != 0) anyNonZero = true;
    }
    assertTrue(anyNonZero, "bitcrushed signal should not be all-zero");
  }

  @Test
  public void bitcrushOffIsTransparent() {
    int n = 64;
    int[][] in = ramp(n);
    int[] expected = new int[n];
    for (int i = 0; i < n; i++) expected[i] = in[i][0];
    int[] postFX = {2147483647};
    new org.deluge.firmware2.SrrBitcrush().process(in, n, OFF, OFF, postFX);
    for (int i = 0; i < n; i++)
      assertEquals(expected[i], in[i][0], "bitcrush off must be transparent");
  }

  @Test
  public void srrHoldsSamples() {
    int n = 256;
    int[][] dry = ramp(n);
    int[][] wet = ramp(n);
    int[] postFX = {2147483647};
    // Higher srrParam = heavier decimation (positivePreset grows). ~0.75 knob gives long holds.
    int srr = Integer.MAX_VALUE / 2;
    new org.deluge.firmware2.SrrBitcrush().process(wet, n, OFF, srr, postFX);

    int dryMaxRun = maxEqualRun(dry);
    int wetMaxRun = maxEqualRun(wet);
    assertTrue(
        wetMaxRun > dryMaxRun + 4,
        "SRR should hold samples (staircase): wetRun=" + wetMaxRun + " dryRun=" + dryMaxRun);
  }

  private static int maxEqualRun(int[][] b) {
    int max = 1, cur = 1;
    for (int i = 1; i < b.length; i++) {
      if (b[i][0] == b[i - 1][0]) cur++;
      else cur = 1;
      max = Math.max(max, cur);
    }
    return max;
  }
}
