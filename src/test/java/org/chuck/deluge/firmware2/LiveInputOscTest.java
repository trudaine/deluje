package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the live-input oscillator sources (voice.cpp:2232-2360 pass-through path,
 * INPUT_L/R/STEREO reading the LiveInput block bus).
 */
class LiveInputOscTest {

  private FirmwareSound makeInputSound(OscType type) {
    FirmwareSound s = new FirmwareSound();
    s.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.oscTypes[0] = type;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    return s;
  }

  /** Render with a synthetic input: left = 400 Hz sine, right = silence. */
  private double[] renderWithInput(OscType type, boolean deviceConnected) {
    try {
      LiveInput.lineInPluggedIn = deviceConnected;
      LiveInput.micPluggedIn = false;
      FirmwareSound s = makeInputSound(type);
      FirmwareAudioEngine eng = new FirmwareAudioEngine();
      eng.sounds.add(s);
      s.triggerNote(60, 100);
      int[] inputBlock = new int[128 * 2];
      double rms = 0;
      int n = 0;
      double phase = 0;
      for (int b = 0; b < 20; b++) {
        for (int i = 0; i < 128; i++) {
          phase += 2 * Math.PI * 400.0 / 44100.0;
          inputBlock[i * 2] = (int) (Math.sin(phase) * 1.0e9); // left
          inputBlock[i * 2 + 1] = 0; // right silent
        }
        LiveInput.currentBlock = inputBlock;
        eng.renderBlock(128);
        if (b >= 5) {
          for (int i = 0; i < 128; i++) {
            double v = eng.masterBuffer[i].l / 2147483648.0;
            rms += v * v;
            n++;
          }
        }
      }
      return new double[] {Math.sqrt(rms / n)};
    } finally {
      LiveInput.currentBlock = null;
      LiveInput.lineInPluggedIn = false;
    }
  }

  @Test
  void inputSourcesEchoTheLiveInputBus() {
    double left = renderWithInput(OscType.INPUT_L, true)[0];
    assertTrue(left > 1e-4, "INPUT_L should echo the left-channel input (rms " + left + ")");

    // Right channel of the synthetic input is silent — INPUT_R with a device connected is quiet.
    double right = renderWithInput(OscType.INPUT_R, true)[0];
    assertTrue(right < left / 10, "INPUT_R should read the (silent) right channel");

    // C voice.cpp:2298-2305: with NO input device, INPUT_R falls back to channel 0 (left).
    double rightNoDevice = renderWithInput(OscType.INPUT_R, false)[0];
    assertEquals(left, rightNoDevice, left * 0.05, "INPUT_R without a device reads the left/mono");

    // Stereo condensed to mono: left-only signal halves (l/2 + r/2 with r = 0).
    double stereo = renderWithInput(OscType.INPUT_STEREO, true)[0];
    assertEquals(left / 2, stereo, left * 0.1, "INPUT_STEREO condenses to (l+r)/2");

    // No input published → silence.
    LiveInput.currentBlock = null;
    FirmwareSound s = makeInputSound(OscType.INPUT_L);
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    s.triggerNote(60, 100);
    double silent = 0;
    for (int b = 0; b < 10; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        silent = Math.max(silent, Math.abs(eng.masterBuffer[i].l / 2147483648.0));
      }
    }
    assertTrue(silent < 1e-6, "no published input block should render silence");
  }
}
