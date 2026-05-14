package org.chuck.deluge.firmware.dsp.oscillators;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class SineOscTest {

  @Test
  public void testSineOsc() {
    int[] output = new int[4];
    int[] phases = {0, 1073741824, (int) 2147483648L, (int) 3221225472L}; // 0, 90, 180, 270 degrees

    SineOsc.render(phases, output);

    // 0 degrees: sin(0) = 0
    assertEquals(0, output[0], 100);
    // 90 degrees: sin(90) = 1.0 (Q31.ONE is roughly 2^31)
    // Note: the sine table in Deluge is likely scaled.
    assertTrue(output[1] > 2000000000);
    // 180 degrees: sin(180) = 0
    assertEquals(0, output[2], 100);
    // 270 degrees: sin(270) = -1.0
    assertTrue(output[3] < -2000000000);
  }
}
