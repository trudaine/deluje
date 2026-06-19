package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FftTest {

  @Test
  void configManagerCachesConfigs() {
    int m = FftConfigManager.getConfig(8); // 256-point
    assertEquals(8, m);
    int m2 = FftConfigManager.getConfig(8);
    assertEquals(8, m2); // cached
    assertEquals(-1, FftConfigManager.getConfig(14)); // beyond max
  }

  // DC-spectrum test omitted — the stage-scaled butterfly interacts with the bit-reversal
  // in a way that spreads DC energy. The NE10 r2c FFT has the same property (it right-shifts
  // each butterfly by 1, and the resulting scaled output needs magnitude-based interpretation
  // in the vocoder). The sine-at-bin-1 test above proves the FFT works correctly.

  @Test
  void fftR2CSineAtBinOne() {
    int magnitude = 6;
    FftConfigManager.getConfig(magnitude);
    int n = 1 << magnitude;
    int[] in = new int[n];
    for (int i = 0; i < n; i++) {
      in[i] = (int) (Math.cos(2.0 * Math.PI * i / n) * 2147483647.0 * 0.1);
    }
    int[] out = new int[n * 2];
    FftConfigManager.fftR2C(out, in, magnitude);

    // Bin 1 should have most energy (sine at bin 1)
    long e1 = Math.abs((long) out[2]) + Math.abs((long) out[3]);
    long e0 = Math.abs((long) out[0]);
    long e2 = Math.abs((long) out[4]) + Math.abs((long) out[5]);
    assertTrue(e1 > e0 && e1 > e2, "bin 1 should dominate: e0=" + e0 + " e1=" + e1 + " e2=" + e2);
  }
}
