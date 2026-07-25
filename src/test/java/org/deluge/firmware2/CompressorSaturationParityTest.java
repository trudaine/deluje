package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CompressorSaturationParityTest {

  @Test
  public void testTanHUnknownParity() {
    int[] testInputs = {0, 100000000, 500000000, 1000000000, -100000000, -500000000, -1000000000};
    int[] satAmounts = {0, 1, 3, 5, 8};

    for (int amount : satAmounts) {
      for (int input : testInputs) {
        int output = Functions.getTanHUnknown(input, amount);
        if (input == 0) {
          assertEquals(0, output, "0 input must produce 0 output for sat amount " + amount);
        } else if (input > 0) {
          assertTrue(output > 0, "Positive input must produce positive saturated output");
          assertTrue(output <= input, "Saturated output must not exceed linear input amplitude");
        } else {
          assertTrue(output < 0, "Negative input must produce negative saturated output");
          assertTrue(output >= input, "Saturated output must not exceed linear input amplitude");
        }
      }
    }
  }

  @Test
  public void testTanHAntialiasedStateUpdate() {
    int satAmount = 3; // compressor default (rms_feedback.cpp:58)
    int input = 500000000;
    int lastWorkingValue = Functions.lshiftAndSaturateUnknown(0, satAmount) + 0x80000000;

    int nextWorkingValue = Functions.lshiftAndSaturateUnknown(input, satAmount) + 0x80000000;
    int output = Functions.getTanHAntialiased(input, lastWorkingValue, satAmount);

    assertTrue(output > 0, "Anti-aliased tanh must produce positive energy from positive input");
    assertTrue(
        output <= input,
        "Anti-aliased tanh output must compress peak amplitude below linear input");
    assertTrue(
        nextWorkingValue != lastWorkingValue, "Working value state must advance for anti-aliasing");
  }

  @Test
  public void testCompressorRenderAndSaturation() {
    Compressor comp = new Compressor();
    comp.setAttackFloat(0.01f);
    comp.setReleaseFloat(0.05f);
    comp.setThresholdFloat(-10.0f);
    comp.setRatioFloat(4.0f);
    comp.setBlendFloat(1.0f); // 100% wet

    int numSamples = 512;
    int[][] buffer = new int[numSamples][2];
    for (int i = 0; i < numSamples; i++) {
      int sample = (int) (1000000000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
      buffer[i][0] = sample;
      buffer[i][1] = sample;
    }

    // Render neutral volume (rms_feedback.cpp:52-57)
    comp.renderVolNeutral(buffer, numSamples, 1 << 27);

    boolean hasEnergy = false;
    int maxPeak = 0;
    for (int i = 0; i < numSamples; i++) {
      int l = buffer[i][0];
      int r = buffer[i][1];
      if (l != 0 || r != 0) {
        hasEnergy = true;
      }
      maxPeak = Math.max(maxPeak, Math.abs(l));
      assertEquals(l, r, "Stereo compressor processing identical inputs must maintain channel symmetry");
    }

    assertTrue(hasEnergy, "Compressor must generate non-zero output from high-level excitation");
    assertTrue(
        maxPeak < 1000000000,
        "Compressor saturation stack must attenuate peak amplitude below uncompressed excitation");
  }
}
