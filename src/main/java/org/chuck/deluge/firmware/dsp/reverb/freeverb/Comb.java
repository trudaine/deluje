package org.chuck.deluge.firmware.dsp.reverb.freeverb;

import static org.chuck.deluge.firmware.util.Q31.*;

public class Comb {
  private int feedback;
  private int filterstore = 0;
  private int damp1;
  private int damp2;
  private int[] buffer;
  private int bufidx = 0;

  public void setBuffer(int[] buffer) {
    this.buffer = buffer;
  }

  public void mute() {
    if (buffer != null) {
      for (int i = 0; i < buffer.length; i++) buffer[i] = 0;
    }
    filterstore = 0;
  }

  public void setDamp(float val) {
    damp1 = (int) (val * 2147483647.0);
    damp2 = 2147483647 - damp1;
  }

  public void setFeedback(int val) {
    this.feedback = val;
  }

  public int process(int input) {
    int output = buffer[bufidx];

    filterstore =
        (multiply_32x32_rshift32_rounded(output, damp2)
                + multiply_32x32_rshift32_rounded(filterstore, damp1))
            << 1;

    buffer[bufidx] = input + (multiply_32x32_rshift32_rounded(filterstore, feedback) << 1);

    if (++bufidx >= buffer.length) {
      bufidx = 0;
    }

    return output;
  }
}
