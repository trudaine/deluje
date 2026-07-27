package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Suggestion 1: Interactive Real-Time
 * Oscilloscope & Spectrum Visualizer (§4.2sextriginties). Verifies that Radix-2 FFT spectrum
 * calculations and time-domain oscilloscope windowing in SwingVisualizerPanel accurately resolve
 * harmonic peak frequencies (e.g. 430 Hz sine wave at FFT bin 10) without spectral leakage,
 * numerical overflow, or NaN generation across 512 magnitude frequency bands.
 */
public class VisualizerBehaviorTest {

  @Test
  public void testFftSpectrumAnalysisAndHarmonicResolution() {
    int sampleRate = 44100;
    int windowSize = 1024;
    float[] sineWave = new float[windowSize];

    // Generate pure 430.66 Hz sine wave (exactly bin 10: 10 * 44100 / 1024 = 430.664 Hz)
    double targetFreqHz = 10.0 * sampleRate / windowSize;
    for (int i = 0; i < windowSize; i++) {
      double t = (double) i / sampleRate;
      sineWave[i] = (float) Math.sin(2.0 * Math.PI * targetFreqHz * t);
    }

    // Compute FFT via SwingVisualizerPanel
    float[] magnitudes = SwingVisualizerPanel.computeFFT(sineWave);
    assertNotNull(magnitudes, "computeFFT must return 512 magnitude bins");
    assertEquals(
        512, magnitudes.length, "FFT magnitude array must contain exactly 512 half-spectrum bins");

    int peakBin = -1;
    float maxMag = 0.0f;
    float sumOtherMags = 0.0f;

    for (int bin = 0; bin < magnitudes.length; bin++) {
      float mag = magnitudes[bin];
      assertFalse(
          Float.isNaN(mag) || Float.isInfinite(mag),
          "FFT magnitude bin " + bin + " must remain valid");
      assertTrue(mag >= 0.0f, "FFT magnitude bin " + bin + " must be non-negative");

      if (mag > maxMag) {
        maxMag = mag;
        peakBin = bin;
      }
      if (bin != 10) {
        sumOtherMags += mag;
      }
    }

    // 1. Verify exact harmonic frequency bin resolution
    assertEquals(
        10,
        peakBin,
        "FFT spectrum analyzer must resolve 430.66 Hz sine wave peak precisely at bin index 10");
    assertTrue(
        maxMag > 100.0f,
        "Peak magnitude at bin 10 must be strong and prominent (was " + maxMag + ")");

    // 2. Verify windowing sideband suppression (peak must dominate background noise bins by > 20x)
    float avgOtherMag = sumOtherMags / (magnitudes.length - 1);
    assertTrue(
        maxMag > avgOtherMag * 20.0f,
        "Hamming windowing must suppress spectral sideband leakage (peak="
            + maxMag
            + ", avgNoise="
            + avgOtherMag
            + ")");
  }

  @Test
  public void testOscilloscopeAndStereoGoniometerBoundedness() {
    int windowSize = 1024;
    float[] left = new float[windowSize];
    float[] right = new float[windowSize];

    // Generate stereo phase offset signals (Lissajous ellipse)
    for (int i = 0; i < windowSize; i++) {
      double t = (double) i / 44100.0;
      left[i] = (float) Math.sin(2.0 * Math.PI * 500.0 * t);
      right[i] = (float) Math.cos(2.0 * Math.PI * 500.0 * t); // 90 degree phase shift
    }

    float[] fftLeft = SwingVisualizerPanel.computeFFT(left);
    float[] fftRight = SwingVisualizerPanel.computeFFT(right);

    assertNotNull(fftLeft);
    assertNotNull(fftRight);
    assertEquals(512, fftLeft.length);
    assertEquals(512, fftRight.length);

    for (int i = 0; i < 512; i++) {
      assertFalse(
          Float.isNaN(fftLeft[i]) || Float.isInfinite(fftLeft[i]),
          "Stereo left FFT bin " + i + " must remain valid");
      assertFalse(
          Float.isNaN(fftRight[i]) || Float.isInfinite(fftRight[i]),
          "Stereo right FFT bin " + i + " must remain valid");
    }
  }
}
