package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the oscillator hard-sync + retrigger-phase port (C sound.h:143/156-157,
 * voice.cpp:1100-1106/2400-2430, voice_unison_part_source.cpp:79-82).
 */
class OscSyncRetrigPhaseTest {

  private FirmwareSound makeSawSound(boolean sync, int retrigA, int retrigB, int oscBTransposeUp) {
    FirmwareSound s = new FirmwareSound();
    // Filters OFF: the 24dB ladder adds per-sample cutoff noise (lpladder.cpp:348), which breaks
    // sample-exact determinism and dulls the saws this test measures.
    s.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.oscTypes[0] = OscType.SAW;
    s.oscTypes[1] = OscType.SAW;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_VOLUME] = 134217728;
    s.paramNeutralValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = oscBTransposeUp * 100 * 178956;
    s.oscillatorSync = sync;
    s.osc1RetriggerPhase = retrigA;
    s.osc2RetriggerPhase = retrigB;
    return s;
  }

  private float[] render(FirmwareSound s, int blocks) {
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    s.triggerNote(60, 100);
    float[] out = new float[blocks * 128];
    for (int b = 0; b < blocks; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        out[b * 128 + i] = eng.masterBuffer[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  /** Normalized autocorrelation at the given lag over the post-attack region. */
  private static double acAtLag(float[] x, int lag) {
    int from = 10 * 128;
    int n = 2048;
    double num = 0, d1 = 0, d2 = 0;
    for (int i = 0; i < n; i++) {
      double a = x[from + i], b = x[from + i + lag];
      num += a * b;
      d1 += a * a;
      d2 += b * b;
    }
    return num / Math.sqrt(d1 * d2 + 1e-18);
  }

  @Test
  void retrigPhaseZeroMakesRendersDeterministic() {
    // C vups:79-82 — with a retrigger phase set, the osc starts at a fixed phase, so two
    // independent sounds render IDENTICAL audio. With retrig off (-1 = C default) the start
    // phase is random and the renders differ.
    float[] a = render(makeSawSound(false, 0, 0, 0), 20);
    float[] b = render(makeSawSound(false, 0, 0, 0), 20);
    for (int i = 0; i < a.length; i++) {
      if (a[i] != b[i]) {
        System.out.printf(
            "first diff at sample %d (block %d): %.6f vs %.6f%n", i, i / 128, a[i], b[i]);
        break;
      }
    }
    assertEquals(0.0f, maxAbsDiff(a, b), 0.0f, "retrig=0 renders should be sample-identical");

    float[] c = render(makeSawSound(false, -1, -1, 0), 20);
    float[] d = render(makeSawSound(false, -1, -1, 0), 20);
    assertTrue(maxAbsDiff(c, d) > 1e-4, "retrig=off renders should differ (random start phases)");
  }

  @Test
  void oscSyncLocksOscBPeriodToOscA() {
    // Osc B transposed +7 semitones (irrational period ratio vs A). Without sync the mix is only
    // periodic at the (long) common period; with hard sync osc B resets every osc-A cycle, so the
    // mix is periodic at osc A's period (note 60 ≈ 261.63 Hz → ~168.6 samples).
    int lagA = (int) Math.round(44100.0 / 261.6256);
    float[] unsynced = render(makeSawSound(false, 0, 0, 7), 30);
    float[] synced = render(makeSawSound(true, 0, 0, 7), 30);
    double acUnsynced = acAtLag(unsynced, lagA);
    double acSynced = acAtLag(synced, lagA);
    System.out.printf("AC(period A): unsynced=%.4f synced=%.4f%n", acUnsynced, acSynced);
    assertTrue(
        acSynced > 0.95,
        "hard-synced mix should be periodic at osc A's period (got " + acSynced + ")");
    assertTrue(
        acSynced > acUnsynced + 0.1,
        "sync should clearly raise periodicity at osc A's period (synced "
            + acSynced
            + " vs unsynced "
            + acUnsynced
            + ")");
  }

  private static float maxAbsDiff(float[] a, float[] b) {
    float m = 0;
    for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
    return m;
  }
}
