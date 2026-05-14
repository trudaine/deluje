package org.chuck.deluge.firmware.dsp.convolution;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;

public class ImpulseResponseProcessor {
  private static final int IR_SIZE = 26;
  private static final int IR_BUFFER_SIZE = IR_SIZE - 1;

  private static final int[] ir = {
    -3203916, 8857848, 24813136, 41537808, 35217472, 15195632, -27538592, -61984128, 1944654848,
    1813580928, 438462784, 101125088, 6042048, -22429488, -46218864, -56638560, -64785312,
        -52108528,
    -37256992, -11863856, 1390352, 14663296, 12784464, 14254800, 5690912, 4490736,
  };

  private final StereoSample[] buffer = new StereoSample[IR_BUFFER_SIZE];

  public ImpulseResponseProcessor() {
    for (int i = 0; i < IR_BUFFER_SIZE; i++) {
      buffer[i] = new StereoSample();
    }
  }

  public void process(StereoSample input, StereoSample output) {
    int outL = buffer[0].l + multiply_32x32_rshift32_rounded(input.l, ir[0]);
    int outR = buffer[0].r + multiply_32x32_rshift32_rounded(input.r, ir[0]);

    for (int i = 1; i < IR_BUFFER_SIZE; i++) {
      buffer[i - 1].l = buffer[i].l + multiply_32x32_rshift32_rounded(input.l, ir[i]);
      buffer[i - 1].r = buffer[i].r + multiply_32x32_rshift32_rounded(input.r, ir[i]);
    }

    buffer[IR_BUFFER_SIZE - 1].l = multiply_32x32_rshift32_rounded(input.l, ir[IR_BUFFER_SIZE]);
    buffer[IR_BUFFER_SIZE - 1].r = multiply_32x32_rshift32_rounded(input.r, ir[IR_BUFFER_SIZE]);

    output.l = outL;
    output.r = outR;
  }
}
