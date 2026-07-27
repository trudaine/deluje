package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ModFxNegativeScalingParityTest {

  @Test
  public void testDarkChorusNegativeDepthAndRate() {
    ModFx processor = new ModFx();
    int numSamples = 512;
    int[][] buffer = new int[numSamples][2];
    for (int i = 0; i < numSamples; i++) {
      buffer[i][0] = (int) (10000000.0 * Math.sin(2.0 * Math.PI * i / 64.0));
      buffer[i][1] = buffer[i][0];
    }

    int[] postFXVolume = {1 << 30};
    // 083 Dark Chorus XML parameters:
    int rate = 0xF5C28F47; // -171802809
    int depth = 0xF5C28F47; // -171802809
    int offset = 0x1E68F500; // 510190848
    int feedback = 0xFFFFFFAA; // -86

    for (int b = 0; b < 4; b++) {
      processor.processModFX(
          buffer,
          numSamples,
          ModFx.ModFXType.CHORUS,
          rate,
          depth,
          postFXVolume,
          offset,
          feedback,
          true,
          true);
    }

    boolean hasEnergy = false;
    for (int i = 0; i < numSamples; i++) {
      if (buffer[i][0] != 0 || buffer[i][1] != 0) {
        hasEnergy = true;
        break;
      }
    }
    assertTrue(hasEnergy, "Negative rate/depth chorus must produce non-zero acoustic output");
  }

  @Test
  public void testNegativeVsPositiveDepthInversion() {
    ModFx pPos = new ModFx();
    ModFx pNeg = new ModFx();
    int numSamples = 256;
    int[][] bufPos = new int[numSamples][2];
    int[][] bufNeg = new int[numSamples][2];

    for (int i = 0; i < numSamples; i++) {
      int val = (i % 32 < 16) ? 5000000 : -5000000;
      bufPos[i][0] = val;
      bufPos[i][1] = val;
      bufNeg[i][0] = val;
      bufNeg[i][1] = val;
    }

    int[] volPos = {1 << 30};
    int[] volNeg = {1 << 30};
    int rate = 10000;
    int depth = 50000000;

    // Run enough samples to populate delay lines and advance LFO
    pPos.processModFX(
        bufPos, numSamples, ModFx.ModFXType.CHORUS, rate, depth, volPos, 0, 0, false, true);
    pPos.processModFX(
        bufPos, numSamples, ModFx.ModFXType.CHORUS, rate, depth, volPos, 0, 0, false, true);

    pNeg.processModFX(
        bufNeg, numSamples, ModFx.ModFXType.CHORUS, rate, -depth, volNeg, 0, 0, false, true);
    pNeg.processModFX(
        bufNeg, numSamples, ModFx.ModFXType.CHORUS, rate, -depth, volNeg, 0, 0, false, true);

    boolean differed = false;
    for (int i = 0; i < numSamples; i++) {
      if (bufPos[i][0] != bufNeg[i][0]) {
        differed = true;
        break;
      }
    }
    assertTrue(
        differed, "Positive vs negative depth must produce distinct delay modulation trajectories");
  }

  @Test
  public void testFlangerFeedbackVolumeCompensation() {
    ModFx processor = new ModFx();
    int[][] buffer = new int[128][2];
    for (int i = 0; i < 128; i++) {
      buffer[i][0] = 1000000;
      buffer[i][1] = 1000000;
    }

    int initialVol = 1 << 30;
    int[] postFXVolume = {initialVol};

    // Test extreme positive feedback (C ModFXProcessor.cpp:117-118 volume compensation)
    processor.processModFX(
        buffer,
        128,
        ModFx.ModFXType.FLANGER,
        1000,
        100000,
        postFXVolume,
        0,
        0x7FFFFFFF, // max positive feedback
        false,
        true);

    assertNotEquals(
        initialVol, postFXVolume[0], "Extreme flanger feedback must attenuate post-FX volume");
    assertTrue(
        postFXVolume[0] < initialVol,
        "Volume compensation must reduce gain to prevent runaway oscillation");
  }
}
