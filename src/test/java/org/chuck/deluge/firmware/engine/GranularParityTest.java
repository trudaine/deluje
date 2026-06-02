package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.granular.GranularProcessor;
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
    GranularProcessor g = new GranularProcessor();
    int grainMix = 0x60000000;

    double wetEnergy = 0;
    double diffEnergy = 0;
    int n = 128;
    for (int blk = 0; blk < 40; blk++) {
      StereoSample[] buf = new StereoSample[n];
      int[] dry = new int[n];
      for (int i = 0; i < n; i++) {
        int v = (int) (Math.sin((blk * n + i) * 0.05) * 4e8);
        buf[i] = new StereoSample(v, v);
        dry[i] = v;
      }
      g.processGrainFX(buf, 16777216, grainMix, 0, 0, 120f);
      if (blk >= 30) { // after the grain buffer has filled
        for (int i = 0; i < n; i++) {
          assertTrue(
              buf[i].l > Integer.MIN_VALUE && buf[i].l < Integer.MAX_VALUE,
              "grain output must stay bounded");
          wetEnergy += Math.abs((long) buf[i].l);
          diffEnergy += Math.abs((long) buf[i].l - dry[i]);
        }
      }
    }
    assertTrue(wetEnergy > 0, "granular should produce a non-silent grain texture");
    assertTrue(diffEnergy > 0, "granular output must differ from the dry signal (effect applied)");
  }
}
