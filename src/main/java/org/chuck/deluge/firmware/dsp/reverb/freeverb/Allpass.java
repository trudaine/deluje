package org.chuck.deluge.firmware.dsp.reverb.freeverb;

public class Allpass {
  private int[] buffer;
  private int bufidx = 0;

  public void setBuffer(int[] buffer) {
    this.buffer = buffer;
  }

  public void mute() {
    if (buffer != null) {
      for (int i = 0; i < buffer.length; i++) buffer[i] = 0;
    }
  }

  public int process(int input) {
    int bufout = buffer[bufidx];
    int output = -input + bufout;

    buffer[bufidx] = input + (bufout >> 1);

    if (++bufidx >= buffer.length) {
      bufidx = 0;
    }

    return output;
  }
}
