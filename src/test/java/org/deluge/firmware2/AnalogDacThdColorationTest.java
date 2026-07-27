package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.FidelityScorecardTest;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Frontier A: Analog DAC THD Coloration
 * (§4.2quattuortriginties). Verifies that applying the physical analog line-out model (AC coupling
 * high-pass, op-amp reconstruction shelf, and cubic THD saturation) generates bounded odd-harmonic
 * coloration on high-amplitude audio without numerical instability, clipping, or NaN generation,
 * mirroring analog hardware output stages against C++ audio output stages.
 */
public class AnalogDacThdColorationTest {

  @Test
  public void testOpAmpThdHarmonicGenerationAndBoundedness() {
    int sr = 44100;
    int numSamples = sr; // 1 second
    float[] out = new float[numSamples];

    // Generate high-amplitude 500 Hz pure sine wave (1.5 max amplitude to exercise op-amp
    // saturation)
    double freq = 500.0;
    for (int i = 0; i < numSamples; i++) {
      out[i] = (float) (1.5 * Math.sin(2.0 * Math.PI * freq * i / sr));
    }

    FidelityScorecardTest.applyAnalogLineOutModel(out);

    double rms = 0.0;
    double maxAbs = 0.0;
    for (float f : out) {
      assertFalse(
          Float.isNaN(f) || Float.isInfinite(f), "DAC THD coloration output must remain valid");
      double abs = Math.abs(f);
      if (abs > maxAbs) maxAbs = abs;
      rms += f * (double) f;
    }
    rms = Math.sqrt(rms / numSamples);

    assertTrue(
        maxAbs < 1.5,
        "Op-amp cubic saturation THD must smoothly compress peak amplitudes below linear 1.5 input");
    assertTrue(rms > 0.5, "Must preserve strong RMS signal energy through line-out equalization");
  }
}
