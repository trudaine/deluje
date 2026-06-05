package org.chuck.deluge.firmware.dsp.reverb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.Q31;
import org.junit.jupiter.api.Test;

class DigitalReverbParityTest {

  @Test
  void digitalReverbReusesPrimaryStateForBothLanes() {
    DigitalReverb reverb = new DigitalReverb();
    reverb.setPanLevels(Q31.ONE, Q31.ONE);
    reverb.setHPF(1.0f);
    reverb.setLPF(1.0f);

    int[] input = new int[8192];
    input[0] = 1_000_000_000;
    StereoSample[] output = new StereoSample[input.length];
    for (int i = 0; i < output.length; i++) {
      output[i] = new StereoSample();
    }

    reverb.process(input, output);

    assertNotEquals(0.0f, reverb.lpDecay[0], 1.0e-12f);
    assertNotEquals(0.0f, reverb.hpState[0], 1.0e-12f);
    assertNotEquals(0.0f, reverb.lpState[0], 1.0e-12f);

    assertEquals(0.0f, reverb.lpDecay[1], 1.0e-12f);
    assertEquals(0.0f, reverb.hpState[1], 1.0e-12f);
    assertEquals(0.0f, reverb.lpState[1], 1.0e-12f);
  }
}
