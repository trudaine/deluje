package org.chuck.deluge.firmware.model.sample;

public class SampleCache {
  public int writeBytePos;
  public int waveformLengthBytes;
  public int phaseIncrement;
  public int timeStretchRatio;
  public int skipSamplesAtStart;
  public boolean reversed;

  public void setWriteBytePos(int pos) {
    this.writeBytePos = pos;
  }
}
