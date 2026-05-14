package org.chuck.deluge.engine.dsp;

import java.util.Random;

/** Pure Java LFO implementation for the Deluge engine. */
public class NativeLfo {
  public enum Waveform {
    SINE,
    TRIANGLE,
    SAW,
    SQUARE,
    RANDOM
  }

  private final float sampleRate;
  private double phase = 0;
  private double phaseInc = 0;
  private Waveform waveform = Waveform.SINE;
  private double rateHz = 1.0;
  private double lastRandomValue = 0;
  private final Random random = new Random();

  public NativeLfo(float sampleRate) {
    this.sampleRate = sampleRate;
    setRate(1.0);
  }

  public void setRate(double rateHz) {
    this.rateHz = rateHz;
    this.phaseInc = 2.0 * Math.PI * rateHz / sampleRate;
  }

  public void setWaveform(int type) {
    this.waveform = Waveform.values()[type % Waveform.values().length];
  }

  public float tick() {
    double val =
        switch (waveform) {
          case SINE -> Math.sin(phase);
          case TRIANGLE -> {
            double p = phase / (2.0 * Math.PI);
            yield (p < 0.5) ? (4.0 * p - 1.0) : (3.0 - 4.0 * p);
          }
          case SAW -> (phase / Math.PI) - 1.0;
          case SQUARE -> (phase < Math.PI) ? 1.0 : -1.0;
          case RANDOM -> {
            if (phase < phaseInc) lastRandomValue = random.nextDouble() * 2.0 - 1.0;
            yield lastRandomValue;
          }
        };

    phase += phaseInc;
    if (phase >= 2.0 * Math.PI) phase -= 2.0 * Math.PI;

    return (float) val;
  }
}
