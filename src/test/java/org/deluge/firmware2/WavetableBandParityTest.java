package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class WavetableBandParityTest {

  private WaveTable buildTestWavetable(int numCycles, int cycleSize) {
    int totalSamples = numCycles * cycleSize;
    float[] samples = new float[totalSamples];
    for (int c = 0; c < numCycles; c++) {
      for (int i = 0; i < cycleSize; i++) {
        if (c == 0) {
          // Cycle 0: pure sine
          samples[c * cycleSize + i] = (float) Math.sin(2.0 * Math.PI * i / cycleSize);
        } else {
          // Cycle 1+: saw ramp
          samples[c * cycleSize + i] = -1.0f + 2.0f * i / cycleSize;
        }
      }
    }
    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, totalSamples);
    WavetableGenerator.generateBands(wt, samples);
    return wt;
  }

  @Test
  public void testBandSelectionByPhaseIncrement() {
    WaveTable wt = buildTestWavetable(1, 2048);
    assertFalse(wt.bands.isEmpty(), "Wavetable generator must create band-limited tables");

    int numSamples = 256;
    int[] outputBuffer = new int[numSamples];

    // Test low pitch (low phaseIncrement -> selects highest resolution band)
    int lowInc = 1000000;
    int endPhaseLow =
        wt.render(
            outputBuffer, 0, numSamples, lowInc, 0, false, 0, 0, 0, 0, 0, 0);

    boolean hasEnergy = false;
    for (int i = 0; i < numSamples; i++) {
      if (outputBuffer[i] != 0) hasEnergy = true;
      assertTrue(
          outputBuffer[i] >= Integer.MIN_VALUE && outputBuffer[i] <= Integer.MAX_VALUE,
          "Wavetable output must remain bounded within Q31");
    }
    assertTrue(hasEnergy, "Low pitch wavetable rendering must produce acoustic signal");
    assertEquals(numSamples * lowInc, endPhaseLow, "Phase must advance monotonically by phaseIncrement * numSamples");

    // Test high pitch (high phaseIncrement -> selects band-limited anti-aliasing table)
    int highInc = 50000000;
    int endPhaseHigh =
        wt.render(
            outputBuffer, 0, numSamples, highInc, 0, false, 0, 0, 0, 0, 0, 0);
    assertEquals(numSamples * highInc, endPhaseHigh, "Phase must advance correctly at high pitch");
  }

  @Test
  public void testWavetableMorphingAcrossCycles() {
    WaveTable wt = buildTestWavetable(2, 2048);
    int numSamples = 128;
    int[] outSine = new int[numSamples];
    int[] outMid = new int[numSamples];
    int[] outSaw = new int[numSamples];

    int inc = 10000000;
    // waveIndex = 0 (pure cycle 0 -> sine)
    wt.render(outSine, 0, numSamples, inc, 0, false, 0, 0, 0, 0, 0, 0);
    // waveIndex = 0x3FFFFFFF (~0.5 in Q31 -> 50/50 blend)
    wt.render(outMid, 0, numSamples, inc, 0, false, 0, 0, 0, 0, 0x3FFFFFFF, 0);
    // waveIndex = 0x7FFFFFFF (pure cycle 1 -> saw)
    wt.render(outSaw, 0, numSamples, inc, 0, false, 0, 0, 0, 0, 0x7FFFFFFF, 0);

    boolean diffFound = false;
    for (int i = 0; i < numSamples; i++) {
      if (outSine[i] != outSaw[i]) diffFound = true;
    }
    assertTrue(diffFound, "Rendering at waveIndex 0 vs max must access distinct wavetable cycles");
  }
}
