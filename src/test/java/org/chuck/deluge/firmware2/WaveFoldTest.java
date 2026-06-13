package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.junit.jupiter.api.Test;

/** Regression test for the LOCAL_FOLD wavefolder port (dsp/util.hpp:66-80, voice.cpp:1499/1585). */
class WaveFoldTest {

  private float[] renderSine(int foldKnob) {
    FirmwareSound s = new FirmwareSound();
    s.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.oscTypes[0] = OscType.SINE;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    s.paramNeutralValues[Param.LOCAL_FOLD] = foldKnob;
    s.osc1RetriggerPhase = 0; // deterministic
    s.osc2RetriggerPhase = 0;
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
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

  /** Brightness proxy: rms of first difference / rms. */
  private static double hfRatio(float[] x) {
    double d = 0, s = 0;
    for (int i = 10 * 128 + 1; i < x.length; i++) {
      double dv = x[i] - x[i - 1];
      d += dv * dv;
      s += x[i] * x[i];
    }
    return Math.sqrt(d / (s + 1e-18));
  }

  @Test
  void foldAddsHarmonicsToASine() {
    float[] clean = renderSine(Integer.MIN_VALUE); // knob off → LOCAL_FOLD final 0 → no fold
    float[] folded = renderSine(Integer.MAX_VALUE); // knob max → deep fold
    double hfClean = hfRatio(clean);
    double hfFolded = hfRatio(folded);
    System.out.printf("hfRatio clean=%.5f folded=%.5f%n", hfClean, hfFolded);
    assertTrue(
        hfFolded > hfClean * 1.5,
        "wavefolding should add harmonics to a sine (clean "
            + hfClean
            + ", folded "
            + hfFolded
            + ")");
  }
}
