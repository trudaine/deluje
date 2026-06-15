package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware2.Param;
import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the portamento port (voice.cpp:190/372-397/840-856, sound.h:141 lastNoteCode,
 * UNPATCHED_PORTAMENTO knob).
 */
class PortamentoTest {

  private FirmwareSound makeSound(int portaKnob) {
    FirmwareSound s = new FirmwareSound();
    s.lpfMode = org.chuck.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware2.FilterSet.FilterMode.OFF;
    s.oscTypes[0] = OscType.SINE;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    s.portamentoKnob = portaKnob;
    s.osc1RetriggerPhase = 0;
    s.osc2RetriggerPhase = 0;
    return s;
  }

  /** Trigger C3, release, trigger C5, and measure the pitch early vs late in the second note. */
  private double[] earlyAndLatePitch(int portaKnob) {
    FirmwareSound s = makeSound(portaKnob);
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    s.triggerNote(48, 100);
    for (int b = 0; b < 10; b++) eng.renderBlock(128);
    s.releaseNote(48);
    for (int b = 0; b < 30; b++) eng.renderBlock(128); // let it fully release
    s.triggerNote(72, 100);
    float[] out = new float[120 * 128];
    for (int b = 0; b < 120; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        out[b * 128 + i] = eng.masterBuffer[i].l / 2147483648.0f;
      }
    }
    // crude pitch via positive zero crossings over a window
    return new double[] {zcr(out, 2 * 128, 20 * 128), zcr(out, 90 * 128, 28 * 128)};
  }

  private static double zcr(float[] x, int from, int len) {
    int zc = 0;
    for (int i = from + 1; i < from + len; i++) {
      if (x[i - 1] <= 0 && x[i] > 0) zc++;
    }
    return zc * 44100.0 / (len - 1);
  }

  @Test
  void portamentoGlidesFromPreviousNote() {
    // No porta: the C5 note is at full pitch immediately.
    double[] off = earlyAndLatePitch(Integer.MIN_VALUE);
    assertEquals(523.25, off[0], 15.0, "without porta the note starts at C5 pitch");
    assertEquals(523.25, off[1], 15.0, "without porta the note stays at C5 pitch");

    // Porta on (mid knob): the note starts LOW (gliding up from C3) and lands on C5.
    double[] on = earlyAndLatePitch(0);
    System.out.printf(
        "porta off early=%.1f late=%.1f | porta on early=%.1f late=%.1f%n",
        off[0], off[1], on[0], on[1]);
    assertTrue(
        on[0] < 480.0,
        "with porta the start of the second note should be well below C5 (got " + on[0] + ")");
    assertEquals(523.25, on[1], 15.0, "porta should settle on the target C5 pitch");
  }
}
