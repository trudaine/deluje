package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware.engine.FirmwareAudioEngine;
import org.deluge.firmware.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the analog oscillator models (oscillator.cpp:459-466, mystery-synth
 * wavetables). Previously ANALOG_SAW_2/ANALOG_SQUARE were silently remapped to the digital
 * SAW/SQUARE (and the XML names "analogSaw"/"analogSquare" fell back to SINE in the factory).
 */
class AnalogOscTest {

  private float[] render(OscType type) {
    FirmwareSound s = new FirmwareSound();
    s.fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.oscTypes[0] = type;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    s.osc1RetriggerPhase = 0; // deterministic
    s.osc2RetriggerPhase = 0;
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    s.triggerNote(60, 100);
    float[] out = new float[20 * 128];
    for (int b = 0; b < 20; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        out[b * 128 + i] = eng.masterBuffer[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  private static double rms(float[] x) {
    double s = 0;
    for (int i = 10 * 128; i < x.length; i++) s += (double) x[i] * x[i];
    return Math.sqrt(s / (x.length - 10 * 128));
  }

  @Test
  void analogModelsRenderAndDifferFromDigital() {
    float[] saw = render(OscType.SAW);
    float[] analogSaw = render(OscType.ANALOG_SAW_2);
    float[] square = render(OscType.SQUARE);
    float[] analogSquare = render(OscType.ANALOG_SQUARE);

    assertTrue(rms(analogSaw) > 1e-4, "analog saw should be audible (rms " + rms(analogSaw) + ")");
    assertTrue(
        rms(analogSquare) > 1e-4,
        "analog square should be audible (rms " + rms(analogSquare) + ")");

    double diffSaw = 0, diffSquare = 0;
    for (int i = 10 * 128; i < saw.length; i++) {
      diffSaw = Math.max(diffSaw, Math.abs(saw[i] - analogSaw[i]));
      diffSquare = Math.max(diffSquare, Math.abs(square[i] - analogSquare[i]));
    }
    System.out.printf(
        "rms saw=%.5f analogSaw=%.5f square=%.5f analogSquare=%.5f | maxDiff saw=%.5f sq=%.5f%n",
        rms(saw), rms(analogSaw), rms(square), rms(analogSquare), diffSaw, diffSquare);
    assertTrue(diffSaw > 1e-4, "analog saw should differ from the digital saw (was remapped)");
    assertTrue(
        diffSquare > 1e-4, "analog square should differ from the digital square (was remapped)");
  }
}
