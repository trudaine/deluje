package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.SrrBitcrushProcessor;
import org.junit.jupiter.api.Test;

/**
 * Verifies the ported bitcrush + sample-rate-reduction DSP (GAP-09). Bitcrushing must zero the low
 * bits of each sample; sample-rate reduction must hold samples (decimation staircase). Both were
 * entirely absent from the pure firmware engine before.
 */
public class SrrBitcrushParityTest {

  private static final int OFF = Integer.MIN_VALUE;

  private static StereoSample[] ramp(int n) {
    StereoSample[] b = new StereoSample[n];
    for (int i = 0; i < n; i++) {
      int v = (int) (Math.sin(i * 0.05) * 1.0e9);
      b[i] = new StereoSample(v, v);
    }
    return b;
  }

  @Test
  public void bitcrushZerosLowBits() {
    int n = 128;
    StereoSample[] buf = ramp(n);
    int[] postFX = {2147483647};
    // Max bitcrush (norm≈1 -> positivePreset 7 -> mask clears low 26 bits), SRR off.
    new SrrBitcrushProcessor().process(buf, n, Integer.MAX_VALUE, OFF, postFX);

    int lowBitsMask = (1 << 26) - 1;
    boolean anyNonZero = false;
    for (int i = 0; i < n; i++) {
      assertEquals(0, buf[i].l & lowBitsMask, "low 26 bits must be cleared by bitcrush at " + i);
      if (buf[i].l != 0) anyNonZero = true;
    }
    assertTrue(anyNonZero, "bitcrushed signal should not be all-zero");
  }

  @Test
  public void bitcrushOffIsTransparent() {
    int n = 64;
    StereoSample[] in = ramp(n);
    int[] expected = new int[n];
    for (int i = 0; i < n; i++) expected[i] = in[i].l;
    int[] postFX = {2147483647};
    new SrrBitcrushProcessor().process(in, n, OFF, OFF, postFX);
    for (int i = 0; i < n; i++)
      assertEquals(expected[i], in[i].l, "bitcrush off must be transparent");
  }

  @Test
  public void srrHoldsSamples() {
    int n = 256;
    StereoSample[] dry = ramp(n);
    StereoSample[] wet = ramp(n);
    int[] postFX = {2147483647};
    // Higher srrParam = heavier decimation (positivePreset grows). ~0.75 knob gives long holds.
    int srr = Integer.MAX_VALUE / 2;
    new SrrBitcrushProcessor().process(wet, n, OFF, srr, postFX);

    int dryMaxRun = maxEqualRun(dry);
    int wetMaxRun = maxEqualRun(wet);
    assertTrue(
        wetMaxRun > dryMaxRun + 4,
        "SRR should hold samples (staircase): wetRun=" + wetMaxRun + " dryRun=" + dryMaxRun);
  }

  private static int maxEqualRun(StereoSample[] b) {
    int max = 1, cur = 1;
    for (int i = 1; i < b.length; i++) {
      if (b[i].l == b[i - 1].l) cur++;
      else cur = 1;
      max = Math.max(max, cur);
    }
    return max;
  }
}
