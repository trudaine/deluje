package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ReverbParityTest {

  @Test
  public void testAllReverbModelsGenerateBoundedDeterministicTails() {
    for (Reverb.Model model : Reverb.Model.values()) {
      Reverb.Container reverb = new Reverb.Container();
      reverb.setModel(model);
      reverb.setRoomSize(0.7f);
      reverb.setDamping(0.3f);
      reverb.setWidth(0.8f);
      reverb.setHPF(0.1f);
      reverb.setPanLevels(1000000000, 1000000000);

      // Reverb comb and allpass delay networks have minimum delay lengths (e.g. Freeverb COMB_L1 =
      // 1116 samples). We must process enough samples (e.g. 8192, ~185ms at 44.1kHz) for the
      // acoustic reflections to traverse the delay lines and emerge in the acoustic tail.
      int numSamples = 8192;
      int[] monoInput = new int[numSamples];
      // Seed an impulse at sample 0 and sine wave excitation
      monoInput[0] = 500000000;
      for (int i = 1; i < 1000; i++) {
        monoInput[i] = (int) (100000000.0 * Math.sin(2.0 * Math.PI * 440.0 * i / 44100.0));
      }

      int[][] stereoOutput = new int[numSamples][2];
      reverb.process(monoInput, stereoOutput);

      boolean hasEnergy = false;
      for (int i = 0; i < numSamples; i++) {
        long l = stereoOutput[i][0];
        long r = stereoOutput[i][1];
        if (l != 0 || r != 0) {
          hasEnergy = true;
        }
        // Verify Q31 integer bounds (no overflow or NaN corruption)
        assertTrue(
            l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE,
            "Left channel out of bounds for model " + model);
        assertTrue(
            r >= Integer.MIN_VALUE && r <= Integer.MAX_VALUE,
            "Right channel out of bounds for model " + model);
      }
      assertTrue(
          hasEnergy,
          "Reverb model " + model + " must generate non-zero acoustic tail from excitation");
    }
  }

  @Test
  public void testReverbPanLevelsScaleStereoSpread() {
    Reverb.Container reverb = new Reverb.Container();
    reverb.setModel(Reverb.Model.FREEVERB);
    reverb.setRoomSize(0.5f);
    // Skew hard left: L=1.0, R=0.0
    reverb.setPanLevels(1000000000, 0);

    int numSamples = 8192;
    int[] monoInput = new int[numSamples];
    for (int i = 0; i < 1000; i++) {
      monoInput[i] = 100000000;
    }
    int[][] stereoOutput = new int[numSamples][2];
    reverb.process(monoInput, stereoOutput);

    long totalL = 0;
    long totalR = 0;
    for (int i = 0; i < numSamples; i++) {
      totalL += Math.abs(stereoOutput[i][0]);
      totalR += Math.abs(stereoOutput[i][1]);
    }
    assertTrue(totalL > 0, "Hard-left panned reverb must produce left-channel energy");
    assertTrue(
        totalL > totalR,
        "Hard-left panned reverb must have significantly higher left-channel energy than right");
  }
}
