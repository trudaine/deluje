package org.chuck.deluge.firmware.dsp.reverb;

import org.chuck.deluge.firmware.dsp.StereoSample;

public abstract class ReverbBase {
  protected int amplitudeRight = 0;
  protected int amplitudeLeft = 0;

  public abstract void process(int[] input, StereoSample[] output);

  public void setPanLevels(int amplitudeLeft, int amplitudeRight) {
    this.amplitudeLeft = amplitudeLeft;
    this.amplitudeRight = amplitudeRight;
  }

  public void setRoomSize(float value) {}

  public float getRoomSize() {
    return 0;
  }

  public void setHPF(float f) {}

  public float getHPF() {
    return 0;
  }

  public void setLPF(float f) {}

  public float getLPF() {
    return 0;
  }

  public void setDamping(float value) {}

  public float getDamping() {
    return 0;
  }

  public void setWidth(float value) {}

  public float getWidth() {
    return 0;
  }

  public int getPanLeft() {
    return amplitudeLeft;
  }

  public int getPanRight() {
    return amplitudeRight;
  }
}
