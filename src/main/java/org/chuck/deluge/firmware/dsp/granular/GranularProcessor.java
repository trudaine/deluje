package org.chuck.deluge.firmware.dsp.granular;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.filter.BasicFilterComponent;

public class GranularProcessor {
  private static final int kModFXGrainBufferSize = 131072; // simplified size

  public static class Grain {
    public int length;
    public int startPoint;
    public int counter;
    public int pitch; // 1024 = 1.0
    public int volScale;
    public int volScaleMax;
    public boolean rev;
    public int panVolL;
    public int panVolR;
  }

  private final StereoSample[] grainBuffer = new StereoSample[kModFXGrainBufferSize];
  private final Grain[] grains = new Grain[8];
  private final BasicFilterComponent lpfL = new BasicFilterComponent();
  private final BasicFilterComponent lpfR = new BasicFilterComponent();
  private int bufferWriteIndex = 0;

  public GranularProcessor() {
    for (int i = 0; i < kModFXGrainBufferSize; i++) grainBuffer[i] = new StereoSample();
    for (int i = 0; i < 8; i++) grains[i] = new Grain();
  }

  public void processGrainFX(
      StereoSample[] buffer,
      int grainRate,
      int grainMix,
      int grainDensity,
      int pitchRandomness,
      float tempoBPM) {
    for (int i = 0; i < buffer.length; i++) {
      buffer[i] = processOneGrainSample(buffer[i]);
    }
  }

  private StereoSample processOneGrainSample(StereoSample currentSample) {
    // 1. Write to buffer
    grainBuffer[bufferWriteIndex].l = currentSample.l;
    grainBuffer[bufferWriteIndex].r = currentSample.r;

    // 2. Sum grains
    long sumL = 0;
    long sumR = 0;
    for (Grain g : grains) {
      if (g.length > 0) {
        // simple playback stub
        int readPos = (g.startPoint + g.counter) % kModFXGrainBufferSize;
        sumL += grainBuffer[readPos].l;
        sumR += grainBuffer[readPos].r;
        g.counter++;
        if (g.counter >= g.length) g.length = 0; // OFF
      }
    }

    bufferWriteIndex = (bufferWriteIndex + 1) % kModFXGrainBufferSize;

    StereoSample output = new StereoSample();
    output.l = (int) (sumL / 8);
    output.r = (int) (sumR / 8);
    return output;
  }
}
