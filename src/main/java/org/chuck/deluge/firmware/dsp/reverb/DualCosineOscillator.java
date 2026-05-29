package org.chuck.deluge.firmware.dsp.reverb;

/**
 * Port of DualCosineOscillator from the firmware. Generates low-overhead, high-performance cosine
 * LFO outputs using a 2nd-order digital resonator.
 */
public class DualCosineOscillator {
  private final float[] frequencies = new float[2];
  private final float[] y0 = new float[2];
  private final float[] y1 = new float[2];
  private final float[] iirCoefficient = new float[2];
  private final float[] initialAmplitude = new float[2];

  public DualCosineOscillator(float[] frequencies) {
    System.arraycopy(frequencies, 0, this.frequencies, 0, 2);
    init();
  }

  public void setFrequency(int index, float frequency) {
    if (index >= 0 && index < 2) {
      this.frequencies[index] = frequency;
      init();
    }
  }

  private void init() {
    for (int i = 0; i < 2; i++) {
      iirCoefficient[i] = (float) (2.0 * Math.cos(2.0 * Math.PI * frequencies[i]));
      initialAmplitude[i] = iirCoefficient[i] * 0.25f;
    }
    start();
  }

  public void start() {
    for (int i = 0; i < 2; i++) {
      y0[i] = initialAmplitude[i];
      y1[i] = 0.5f;
    }
  }

  public float[] getValues() {
    return new float[] {y0[0] + 0.5f, y0[1] + 0.5f};
  }

  public float getValue(int index) {
    if (index >= 0 && index < 2) {
      return y0[index] + 0.5f;
    }
    return 0.5f;
  }

  public void next() {
    for (int i = 0; i < 2; i++) {
      float temp = y1[i];
      y1[i] = iirCoefficient[i] * y1[i] - y0[i];
      y0[i] = temp;
    }
  }
}
