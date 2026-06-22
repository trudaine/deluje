package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the live-input oscillator sources (voice.cpp:2232-2360 pass-through path,
 * INPUT_L/R/STEREO reading the LiveInput block bus).
 */
class LiveInputOscTest {

  private FirmwareSound makeInputSound(OscType type) {
    FirmwareSound s = new FirmwareSound();
    s.fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.oscTypes[0] = type;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    return s;
  }

  /** Render with a synthetic input: left = 400 Hz sine, right = silence. Returns {rms, zcrHz}. */
  private double[] renderWithInput(OscType type, boolean deviceConnected, int note) {
    try {
      LiveInput.lineInPluggedIn = deviceConnected;
      LiveInput.micPluggedIn = false;
      FirmwareSound s = makeInputSound(type);
      FirmwareAudioEngine eng = new FirmwareAudioEngine();
      eng.sounds.add(s);
      s.triggerNote(note, 100);
      int[] inputBlock = new int[128 * 2];
      double rms = 0;
      int n = 0;
      int zc = 0;
      double prev = 0;
      double phase = 0;
      for (int b = 0; b < 40; b++) {
        for (int i = 0; i < 128; i++) {
          phase += 2 * Math.PI * 400.0 / 44100.0;
          inputBlock[i * 2] = (int) (Math.sin(phase) * 1.0e9); // left
          inputBlock[i * 2 + 1] = 0; // right silent
        }
        LiveInput.currentBlock = inputBlock;
        eng.renderBlock(128);
        // Measure well past the pitch-shifter's warm-up (it needs to fill its input ring and
        // settle its hop scheduling before the output is steady).
        if (b >= 25) {
          for (int i = 0; i < 128; i++) {
            double v = eng.masterBuffer[i].l / 2147483648.0;
            rms += v * v;
            if (prev <= 0 && v > 0) zc++;
            prev = v;
            n++;
          }
        }
      }
      return new double[] {Math.sqrt(rms / n), zc * 44100.0 / n};
    } finally {
      LiveInput.currentBlock = null;
      LiveInput.lineInPluggedIn = false;
    }
  }

  private double[] renderWithInput(OscType type, boolean deviceConnected) {
    return renderWithInput(type, deviceConnected, 60);
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

    // (l+r)/2 with r=0 routes to ~l/2 pre-master; the engaged master compressor's makeup boosts the
    // quieter condensed signal, so it lands above l/2 yet stays clearly condensed (below full
    // left).
    double stereo = renderWithInput(OscType.INPUT_STEREO, true)[0];
    assertTrue(
        stereo < left && stereo > left * 0.3,
        "INPUT_STEREO condenses (l+r)/2 then makeup boosts: " + stereo + " vs left " + left);

    // Pitch-shift sub-path (voice.cpp:2236-2274): note 72 = ratio 2.0 — the per-source
    // LivePitchShifter engages and the 400 Hz input comes out pitched well up. The hop-based
    // granular shifter's zero-cross average reads below the nominal 2× (hop crossfades cut
    // cycles; measures ~666 Hz steady), so assert "clearly shifted", not exact.
    double[] shifted = renderWithInput(OscType.INPUT_L, true, 72);
    System.out.printf("pitch-shift: rms=%.5f zcr=%.1f Hz (input 400)%n", shifted[0], shifted[1]);
    assertTrue(shifted[0] > 1e-4, "pitch-shifted input should be audible (rms " + shifted[0] + ")");
    assertTrue(
        shifted[1] > 550.0 && shifted[1] < 900.0,
        "note 72 should clearly shift the 400 Hz input up (got " + shifted[1] + " Hz)");

    // And at the unity note the shifter must NOT engage — output stays at the input pitch.
    double[] unity = renderWithInput(OscType.INPUT_L, true, 60);
    assertEquals(400.0, unity[1], 30.0, "unity note must pass the input through unshifted");

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
