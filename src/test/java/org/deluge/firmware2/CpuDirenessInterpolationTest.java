package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.firmware.engine.FirmwareAudioEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Faithful-port checks for the CPU-direness adaptive sample-interpolation fallback.
 *
 * <ul>
 *   <li>{@link Functions#getInterpolationBufferSize} — C:
 *       SampleControls::getInterpolationBufferSize (sample_controls.cpp:29): linear (2 taps) when
 *       {@code cpuDireness != 0} and {@code octave >= 26 - (cpuDireness >> 2)}, else sinc (16).
 *   <li>{@link FirmwareAudioEngine#updateDireness} — C: AudioEngine::setDireness
 *       (audio_engine.cpp:472): raise on render overrun (threshold 50, cap 14), decay with
 *       hysteresis (kSampleRate>>3).
 * </ul>
 */
class CpuDirenessInterpolationTest {

  private static final int SINC = SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES; // 16
  private static final int UNITY =
      1 << 24; // phaseIncrement at native pitch → getMagnitudeOld == 25

  @BeforeEach
  @AfterEach
  void resetDireness() {
    FirmwareAudioEngine.cpuDireness = 0;
  }

  @Test
  void noDirenessAlwaysSinc() {
    FirmwareAudioEngine.cpuDireness = 0;
    assertEquals(SINC, Functions.getInterpolationBufferSize(UNITY), "unity pitch");
    assertEquals(SINC, Functions.getInterpolationBufferSize(1 << 26), "+2 octaves up");
  }

  @Test
  void magnitudeOldMatchesC() {
    // C: getMagnitudeOld(input) = 32 - clz(input).
    assertEquals(25, Functions.getMagnitudeOld(UNITY)); // 2^24
    assertEquals(27, Functions.getMagnitudeOld(1 << 26)); // +2 octaves
    assertEquals(1, Functions.getMagnitudeOld(1));
  }

  @Test
  void unityPitchGoesLinearOnlyOnceDirenessReachesFour() {
    // threshold for octave 25 = 26 - (dir>>2); linear iff 25 >= that, i.e. (dir>>2) >= 1 → dir >=
    // 4.
    for (int dir = 1; dir <= 3; dir++) {
      FirmwareAudioEngine.cpuDireness = dir;
      assertEquals(SINC, Functions.getInterpolationBufferSize(UNITY), "dir=" + dir + " still sinc");
    }
    FirmwareAudioEngine.cpuDireness = 4;
    assertEquals(2, Functions.getInterpolationBufferSize(UNITY), "dir=4 → linear");
    FirmwareAudioEngine.cpuDireness = 14;
    assertEquals(2, Functions.getInterpolationBufferSize(UNITY), "dir=14 → linear");
  }

  @Test
  void pitchedUpSamplesGoLinearEarlier() {
    // +2 octaves → octave 27; threshold at dir=1 is 26 → 27 >= 26 → linear, while unity stays sinc.
    FirmwareAudioEngine.cpuDireness = 1;
    assertEquals(2, Functions.getInterpolationBufferSize(1 << 26), "+2oct linear at dir=1");
    assertEquals(SINC, Functions.getInterpolationBufferSize(UNITY), "unity still sinc at dir=1");
  }

  @Test
  void direnessRaisesOnOverrunAndCapsAt14() {
    FirmwareAudioEngine.cpuDireness = 0;
    // ~10 ms render of a 128-sample block: dspTime≈441, overrun≈313 → capped at 14.
    long tenMs = 10_000_000L;
    FirmwareAudioEngine.updateDireness(tenMs, 128, 0);
    assertEquals(14, FirmwareAudioEngine.cpuDireness, "raised + capped");

    // A smaller overrun must NOT lower direness (raise branch only raises).
    long smallNs = Math.round((128 + 60) / 44100.0 * 1e9); // overrun ~60 → newDireness ~11 < 14
    FirmwareAudioEngine.updateDireness(smallNs, 128, 1000);
    assertEquals(14, FirmwareAudioEngine.cpuDireness, "raise never lowers");
  }

  @Test
  void direnessDecaysWithHysteresis() {
    FirmwareAudioEngine.cpuDireness = 0;
    FirmwareAudioEngine.updateDireness(10_000_000L, 128, 0); // raise to 14, timeChanged=0
    assertEquals(14, FirmwareAudioEngine.cpuDireness);

    int decayInterval = 44100 >> 3; // 5512
    // A comfortable render too soon after the change must NOT decay (hysteresis gate).
    FirmwareAudioEngine.updateDireness(0, 128, decayInterval - 1);
    assertEquals(14, FirmwareAudioEngine.cpuDireness, "too soon → no decay");

    // Once enough time passes, each comfortable block decays by exactly 1.
    FirmwareAudioEngine.updateDireness(0, 128, decayInterval);
    assertEquals(13, FirmwareAudioEngine.cpuDireness, "first decay");
    FirmwareAudioEngine.updateDireness(0, 128, 2L * decayInterval);
    assertEquals(12, FirmwareAudioEngine.cpuDireness, "second decay");
  }
}
