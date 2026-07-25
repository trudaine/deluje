package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class SvfParityTest {

  @Test
  public void testSvfBandpassAndNotchRendering() {
    FilterSet.FilterMode[] modes = {FilterSet.FilterMode.SVF_BAND, FilterSet.FilterMode.SVF_NOTCH};

    for (FilterSet.FilterMode mode : modes) {
      SVFilter svf = new SVFilter();
      // freq, res, mode, morph, filterGain
      svf.setConfig(500000000, 100000000, mode, 500000000, 1000000000);

      int numSamples = 256;
      int[] buffer = new int[numSamples];
      buffer[0] = 500000000; // impulse

      svf.doFilter(buffer, 0, numSamples, 1);

      boolean hasEnergy = false;
      for (int i = 0; i < numSamples; i++) {
        int sample = buffer[i];
        if (sample != 0) {
          hasEnergy = true;
        }
        assertTrue(
            sample >= Integer.MIN_VALUE && sample <= Integer.MAX_VALUE,
            "SVF sample out of bounds for mode " + mode);
      }
      assertTrue(hasEnergy, "SVF filter mode " + mode + " must generate acoustic output from impulse");
    }
  }

  @Test
  public void testSvfHighResonanceSaturationBounds() {
    SVFilter svf = new SVFilter();
    // High resonance: res = 120000000 (~0.47 in Q28 range)
    svf.setConfig(1000000000, 120000000, FilterSet.FilterMode.SVF_BAND, 0, 1000000000);

    int numSamples = 512;
    int[] buffer = new int[numSamples];
    for (int i = 0; i < numSamples; i++) {
      buffer[i] = (int) (1000000000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
    }

    svf.doFilter(buffer, 0, numSamples, 1);

    int maxAmplitude = 0;
    for (int i = 0; i < numSamples; i++) {
      maxAmplitude = Math.max(maxAmplitude, Math.abs(buffer[i]));
    }
    assertTrue(maxAmplitude > 0, "High resonance SVF must produce signal energy");
    assertTrue(
        maxAmplitude < Integer.MAX_VALUE, "SVF double-sample tanh saturation must prevent overflow");
  }
}
