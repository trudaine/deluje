package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the voice saturation/clipping port (voice.cpp:1535-1565, sound.h:286-294 —
 * anti-aliased tanh at 5 + clippingAmount).
 */
class SaturationTest {

  private float[] renderSine(int clippingAmount) {
    FirmwareSound s = new FirmwareSound();
    s.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.oscTypes[0] = OscType.SINE;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    s.clippingAmount = clippingAmount;
    s.osc1RetriggerPhase = 0; // deterministic
    s.osc2RetriggerPhase = 0;
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    eng.masterCompressor.setBlendFloat(0.0f);
    s.triggerNote(60, 100);
    float[] out = new float[30 * 128];
    for (int b = 0; b < 30; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        out[b * 128 + i] = eng.masterBuffer[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  /** Crest factor (peak/rms) over the sustain. A saturated sine flattens: crest drops toward 1. */
  private static double crest(float[] x) {
    double pk = 0, sq = 0;
    int n = 0;
    for (int i = 10 * 128; i < x.length; i++) {
      pk = Math.max(pk, Math.abs(x[i]));
      sq += (double) x[i] * x[i];
      n++;
    }
    return pk / Math.sqrt(sq / n);
  }

  @Test
  void clippingFlattensAndAmplifiesTheWave() {
    float[] clean = renderSine(0);
    float[] saturated = renderSine(4);
    double crestClean = crest(clean);
    double crestSat = crest(saturated);
    System.out.printf("crest clean=%.4f saturated=%.4f%n", crestClean, crestSat);
    // A pure sine has crest √2 ≈ 1.414; tanh saturation flattens the top → crest drops.
    assertTrue(
        crestSat < crestClean - 0.05,
        "saturation should flatten the sine (crest " + crestClean + " → " + crestSat + ")");
    // And the output must still sound (not silenced by a broken shift/table path).
    double rms = 0;
    for (int i = 10 * 128; i < saturated.length; i++) rms += (double) saturated[i] * saturated[i];
    rms = Math.sqrt(rms / (saturated.length - 10 * 128));
    assertTrue(rms > 1e-4, "saturated render should still be audible (rms " + rms + ")");
  }
}
