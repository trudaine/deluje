package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Verifies the granular mod-FX produces a (bounded, non-silent) grain texture that differs from the
 * dry signal (GAP-13, granular half). Before this, ModFXType.GRAIN was routed to the LFO-based
 * ModFXProcessor and did nothing. Grain rate calibration is approximate (the firmware feeds the raw
 * mod-FX rate param domain), but the effect is now functional.
 */
public class GranularParityTest {

  @Test
  public void granularProducesBoundedGrainTexture() {
    org.deluge.firmware2.GranularProcessor g = new org.deluge.firmware2.GranularProcessor();
    int grainMix = 0x60000000;

    double wetEnergy = 0;
    double diffEnergy = 0;
    int n = 128;
    int[] postFX = {2147483647};
    for (int blk = 0; blk < 40; blk++) {
      int[][] buf = new int[n][2];
      int[] dry = new int[n];
      for (int i = 0; i < n; i++) {
        int v = (int) (Math.sin((blk * n + i) * 0.05) * 4e8);
        buf[i][0] = v;
        buf[i][1] = v;
        dry[i] = v;
      }
      g.processGrainFX(buf, n, 16777216, grainMix, 0, 0, postFX, true, 120f);
      if (blk >= 30) { // after the grain buffer has filled
        for (int i = 0; i < n; i++) {
          assertTrue(
              buf[i][0] > Integer.MIN_VALUE && buf[i][0] < Integer.MAX_VALUE,
              "grain output must stay bounded");
          wetEnergy += Math.abs((long) buf[i][0]);
          diffEnergy += Math.abs((long) buf[i][0] - dry[i]);
        }
      }
    }
    assertTrue(wetEnergy > 0, "granular should produce a non-silent grain texture");
    assertTrue(diffEnergy > 0, "granular output must differ from the dry signal (effect applied)");
    assertTrue(postFX[0] < 2147483647, "granular should attenuate post-FX volume like firmware");
  }
}
