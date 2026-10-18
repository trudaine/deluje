package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * Tests oscillator hard-sync parity against C {@code oscillator.cpp} and {@code render_wave.h}.
 *
 * <p>Covers the {@code (uint16_t)} divisor cast in {@code oscillator.cpp:140-142}, synced {@code
 * ANALOG_SAW_2} ({@code oscillator.cpp:459-495}), synced variable-width {@code SQUARE} ({@code
 * oscillator.cpp:381-449}), non-zero {@code retriggerPhase} ({@code -1 = 0xFFFFFFFF}), and
 * multi-block {@code startPhase} continuity across sync resets.
 */
class OscSyncParityTest {

  private static final int BLOCK = 128;
  private static final int AMP = 1 << 27;

  @Test
  void syncDivisorWrapsAtUint16LikeCpp() {
    // C oscillator.cpp:140-142:
    // resetterDivideByPhaseIncrement = (uint32_t)2147483648u
    //     / (uint16_t)((resetterPhaseIncrement + 65535) >> 16);
    // For resetterPhaseIncrement = 0xFFFF0001:
    //   0xFFFF0001 + 65535 = 0x100000000 -> wraps to 0x00000000 in uint32_t, >> 16 = 0 -> div=0.
    // For resetterPhaseIncrement = 0xFFFE0001:
    //   0xFFFE0001 + 65535 = 0xFFFF0000 -> >> 16 = 0xFFFF -> div = 2147483648 / 65535 = 32768.
    int phaseInc = 0x04000000;
    int resetterWrapToZero = 0xFFFF0001;
    int resetterMaxUint16 = 0xFFFE0001;

    int[] bufZero = new int[BLOCK];
    int[] phaseZero = {0};
    Oscillator.renderOsc(
        OscType.SAW,
        AMP,
        bufZero,
        0,
        BLOCK,
        phaseInc,
        /* pulseWidth= */ 0,
        phaseZero,
        /* applyAmplitude= */ false,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0x7FFF0000,
        resetterWrapToZero,
        /* retriggerPhase= */ 0);

    int[] bufMax = new int[BLOCK];
    int[] phaseMax = {0};
    Oscillator.renderOsc(
        OscType.SAW,
        AMP,
        bufMax,
        0,
        BLOCK,
        phaseInc,
        /* pulseWidth= */ 0,
        phaseMax,
        /* applyAmplitude= */ false,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0x7FFF0000,
        resetterMaxUint16,
        /* retriggerPhase= */ 0);

    // Both reset almost every sample, but resetterWrapToZero has resetterDivideByPhaseIncrement = 0
    // so fadeBetweenSyncs = 0, whereas resetterMaxUint16 has resetterDivideByPhaseIncrement =
    // 32768.
    boolean anyDiff = false;
    for (int i = 0; i < BLOCK; i++) {
      if (bufZero[i] != bufMax[i]) {
        anyDiff = true;
        break;
      }
    }
    assertTrue(anyDiff, "uint16 wrap to 0 vs 0xFFFF must produce distinct crossover blends");
  }

  @Test
  void analogSaw2SyncResetsPhaseAtResetterPeriod() {
    // C oscillator.cpp:459-495 — ANALOG_SAW_2 routes through callRenderWave -> renderOscSync.
    // With resetterPhaseInc = 0x08000000 (period = 32 samples), the output must repeat every 32
    // samples after the first crossover.
    int phaseInc = 0x0212ABCD;
    int resetterPhaseInc = 0x08000000; // 2^32 / 2^27 = 32 samples
    int[] buf = new int[BLOCK];
    int[] startPhase = {0};

    Oscillator.renderOsc(
        OscType.ANALOG_SAW_2,
        AMP,
        buf,
        0,
        BLOCK,
        phaseInc,
        /* pulseWidth= */ 0,
        startPhase,
        /* applyAmplitude= */ false,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0,
        resetterPhaseInc,
        /* retriggerPhase= */ 0);

    // Samples 40..63 (inside sync cycle 2) must equal samples 72..95 (inside sync cycle 3).
    for (int i = 40; i < 64; i++) {
      assertEquals(
          buf[i],
          buf[i + 32],
          "ANALOG_SAW_2 sync output must repeat at resetter period (i=" + i + ")");
    }
  }

  @Test
  void syncedBandLimitedPulseWaveUpdatesDoubledStartPhase() {
    // C oscillator.cpp:417-448 — band-limited SQUARE with pulseWidth != 0 and doOscSync = true
    // halves phase and phaseIncrement before renderOscSync, then shifts phase <<= 1 before
    // maybeStorePhase.
    int phaseInc = 0x0212ABCD; // band-limited (tableNumber >= 6)
    int pulseWidth = 0x40000000; // non-zero PW -> doPulseWave = true
    int resetterPhaseInc = 0x08000000; // period = 32 samples
    int[] buf = new int[BLOCK];
    int[] startPhase = {0};

    Oscillator.renderOsc(
        OscType.SQUARE,
        AMP,
        buf,
        0,
        BLOCK,
        phaseInc,
        pulseWidth,
        startPhase,
        /* applyAmplitude= */ false,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0,
        resetterPhaseInc,
        /* retriggerPhase= */ 0);

    // C oscillator.cpp:444 — phase <<= 1 clears bit 0 of the stored phase.
    assertEquals(0, startPhase[0] & 1, "stored phase after synced pulse wave must have bit 0 = 0");
    for (int i = 40; i < 64; i++) {
      assertEquals(
          buf[i],
          buf[i + 32],
          "synced band-limited pulse must repeat at resetter period (i=" + i + ")");
    }
  }

  @Test
  void syncedCrudePulseWaveRespectsRetriggerPhaseAllOnes() {
    // C oscillator.cpp:381-409 — crude SQUARE (tableNumber < 6) with pulseWidth != 0, doOscSync =
    // true, and retriggerPhase = 0xFFFFFFFF (-1, as in "045 Square Sync").
    int phaseInc = 0x004EC4EC; // crude path (tableNumber < 6)
    int pulseWidth = 0x20000000;
    int resetterPhaseInc = 0x08000000; // period = 32 samples
    int[] bufNegOne = new int[BLOCK];
    int[] bufQuarter = new int[BLOCK];
    int[] sp1 = {0};
    int[] sp2 = {0};

    Oscillator.renderOsc(
        OscType.SQUARE,
        AMP,
        bufNegOne,
        0,
        BLOCK,
        phaseInc,
        pulseWidth,
        sp1,
        /* applyAmplitude= */ false,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0,
        resetterPhaseInc,
        /* retriggerPhase= */ -1);

    Oscillator.renderOsc(
        OscType.SQUARE,
        AMP,
        bufQuarter,
        0,
        BLOCK,
        phaseInc,
        pulseWidth,
        sp2,
        /* applyAmplitude= */ false,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0,
        resetterPhaseInc,
        /* retriggerPhase= */ 0x9F000000);

    assertNotEquals(
        sp1[0], sp2[0], "retriggerPhase=-1 vs 0x9F000000 must offset the post-sync phase");
    boolean anySampleDiff = false;
    for (int i = 32; i < BLOCK; i++) {
      if (bufNegOne[i] != bufQuarter[i]) {
        anySampleDiff = true;
        break;
      }
    }
    assertTrue(anySampleDiff, "retriggerPhase must shift the synced crude square waveform");
  }

  @Test
  void multiBlockSyncHandoffMatchesSingleResetterTimeline() {
    // Rendering two consecutive 64-sample blocks with startPhase + resetterPhase carried forward
    // must match a single 128-sample render (C voice.cpp:1110, oscillator.cpp:530-534).
    int phaseInc = 0x0189ABCD;
    int resetterPhaseInc = 0x05555555;
    int retriggerPhase = -1; // 0xFFFFFFFF as in 045 Square Sync / 098 Saturated Sync

    int[] single = new int[BLOCK];
    int[] singlePhase = {0x12345678};
    Oscillator.renderOsc(
        OscType.SAW,
        AMP,
        single,
        0,
        BLOCK,
        phaseInc,
        /* pulseWidth= */ 0,
        singlePhase,
        /* applyAmplitude= */ true,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0x01020304,
        resetterPhaseInc,
        retriggerPhase);

    int[] split = new int[BLOCK];
    int[] splitPhase = {0x12345678};
    int resetterBlock0 = 0x01020304;
    Oscillator.renderOsc(
        OscType.SAW,
        AMP,
        split,
        0,
        64,
        phaseInc,
        /* pulseWidth= */ 0,
        splitPhase,
        /* applyAmplitude= */ true,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        resetterBlock0,
        resetterPhaseInc,
        retriggerPhase);
    int resetterBlock1 = resetterBlock0 + resetterPhaseInc * 64;
    Oscillator.renderOsc(
        OscType.SAW,
        AMP,
        split,
        64,
        64,
        phaseInc,
        /* pulseWidth= */ 0,
        splitPhase,
        /* applyAmplitude= */ true,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        resetterBlock1,
        resetterPhaseInc,
        retriggerPhase);

    assertEquals(singlePhase[0], splitPhase[0], "end phase after two 64-sample blocks");
    for (int i = 0; i < BLOCK; i++) {
      assertEquals(single[i], split[i], "sample mismatch at i=" + i);
    }
  }
}
