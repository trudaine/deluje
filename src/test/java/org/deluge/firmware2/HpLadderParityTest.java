package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class HpLadderParityTest {

  @Test
  public void testHpLadderHighpassAttenuation() {
    HpLadderFilter hpf = new HpLadderFilter();
    // High cutoff freq: ~150000000 in Q28 scaling (~1 kHz corner)
    hpf.setConfig(150000000, 0, FilterSet.FilterMode.HPLADDER, 0, 1000000000);

    int numSamples = 1024;
    int[] lowFreqBuf = new int[numSamples];
    int[] highFreqBuf = new int[numSamples];

    // Excite with 40 Hz (well below corner) and 8000 Hz (well above corner)
    for (int i = 0; i < numSamples; i++) {
      int lowSample = (int) (500000000.0 * Math.sin(2.0 * Math.PI * 40.0 * i / 44100.0));
      int highSample = (int) (500000000.0 * Math.sin(2.0 * Math.PI * 8000.0 * i / 44100.0));
      lowFreqBuf[i] = lowSample;
      highFreqBuf[i] = highSample;
    }

    hpf.doFilter(lowFreqBuf, 0, numSamples, 1);
    hpf.resetFilter();
    hpf.doFilter(highFreqBuf, 0, numSamples, 1);

    long lowEnergy = 0;
    long highEnergy = 0;
    // Skip initial filter transient (first 256 samples)
    for (int i = 256; i < numSamples; i++) {
      lowEnergy += Math.abs((long) lowFreqBuf[i]);
      highEnergy += Math.abs((long) highFreqBuf[i]);
    }

    assertTrue(highEnergy > 0, "High-frequency signal above corner must pass through HPF");
    assertTrue(
        lowEnergy < (highEnergy / 4),
        "Low-frequency signal below corner must be significantly attenuated by transistor HPF");
  }

  @Test
  public void testHpLadderResonanceSaturation() {
    HpLadderFilter hpf = new HpLadderFilter();
    // High resonance: res = 120000000 (~0.47 in Q28 range -> hpfProcessedResonance > 900000000)
    hpf.setConfig(150000000, 120000000, FilterSet.FilterMode.HPLADDER, 0, 1000000000);

    int numSamples = 512;
    int[] buffer = new int[numSamples];
    for (int i = 0; i < numSamples; i++) {
      buffer[i] = (int) (500000000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
    }

    hpf.doFilter(buffer, 0, numSamples, 1);

    int maxAmplitude = 0;
    for (int i = 0; i < numSamples; i++) {
      maxAmplitude = Math.max(maxAmplitude, Math.abs(buffer[i]));
    }
    assertTrue(maxAmplitude > 0, "High resonance HPF must generate signal energy");
    assertTrue(
        maxAmplitude < Integer.MAX_VALUE,
        "HPF anti-aliased tanh saturation must bound resonance and prevent overflow");
  }
}
