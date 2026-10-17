package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Dedicated C++ parity regression tests for firmware2 Slice 4 (WaveTable, Functions,
 * LivePitchShifter, Patcher, SincInterpolator).
 */
class Fw2Slice4FindingsTest {

  @Test
  void wavetableClampsCycleSizeBeforeMinimumSizeCheck() {
    // C wave_table.cpp:161-168: rawFileCycleSize is clamped to originalSampleLengthInSamples
    // BEFORE checking rawFileCycleSize < kWavetableMinCycleSize.
    WaveTable wt256 = new WaveTable();
    wt256.setup(2048, 256);
    assertEquals(1, wt256.numCycles);
    assertFalse(wt256.bands.isEmpty(), "256-sample single-cycle WAV with default 2048 must setup");

    WaveTable wt1024 = new WaveTable();
    wt1024.setup(2048, 1024);
    assertEquals(1, wt1024.numCycles);
    assertFalse(
        wt1024.bands.isEmpty(), "1024-sample single-cycle WAV with default 2048 must setup");

    WaveTable wtTooShort = new WaveTable();
    wtTooShort.setup(2048, 4);
    assertEquals(0, wtTooShort.numCycles);
    assertTrue(wtTooShort.bands.isEmpty(), "Sample shorter than kWavetableMinCycleSize must fail");
  }

  @Test
  void volumeAndLinearCurvesPreserveMultiCableHeadroom() {
    // C functions.cpp:199-242: no input ceiling at 2^30 on positivePatchedValue.
    // Two full-depth cables give patchedValue = 1073741824 (2^30) -> positivePatchedValue = 3 *
    // 2^29.
    int patchedValue = 1073741824;
    int neutralVolume = 134217728;
    int expectedVol =
        Functions.lshiftAndSaturate(
            Functions.multiply_32x32_rshift32(
                ((patchedValue + 536870912) >> 16) * ((patchedValue + 536870912) >> 15),
                neutralVolume),
            5);
    assertEquals(1207959552, expectedVol);
    assertEquals(expectedVol, Functions.getFinalParameterValueVolume(neutralVolume, patchedValue));

    int neutralLinear = 7908853;
    int expectedLin =
        Functions.lshiftAndSaturate(
            Functions.multiply_32x32_rshift32(patchedValue + 536870912, neutralLinear), 3);
    assertEquals(23726552, expectedLin);
    assertEquals(expectedLin, Functions.getFinalParameterValueLinear(neutralLinear, patchedValue));
  }

  @Test
  void interpolateTableInverseUsesUnsigned64BitDivision() {
    // C functions.cpp:488-490: numerator is (uint64_t), so the division is unsigned 64-bit.
    int actual =
        Functions.interpolateTableInverse(2135113728, 23, LookupTables.decayTableSmall4, 8);
    assertEquals(
        0, actual, "Descending decayTableSmall4 inverse lookup must use unsigned 64-bit division");
  }

  @Test
  void livePitchShifterHopEndSeparatesThisAndNextCrossfadeLengths() {
    // C live_pitch_shifter.cpp:383, 401, 547, 809: hopEnd snapshots thisCrossfadeLength =
    // nextCrossfadeLength BEFORE updating nextCrossfadeLength from the table.
    int phaseIncrement = LivePitchShifter.K_MAX_SAMPLE_VALUE * 2; // 1 octave up
    LivePitchShifter shifter =
        new LivePitchShifter(LiveInputBuffer.InputType.INPUT_L, phaseIncrement);
    LiveInputBuffer inputBuf = new LiveInputBuffer();

    int[] stereoInput = new int[512 * 2];
    for (int i = 0; i < 512; i++) {
      stereoInput[i * 2] = (i * 1000) << 16;
      stereoInput[i * 2 + 1] = (i * 1000) << 16;
    }

    // Force an immediate hopEnd with nextCrossfadeLength = 0 (as when render() shortens a fade
    // because the outgoing play-head ran out of audio).
    shifter.samplesTilHopEnd = 0;
    shifter.nextCrossfadeLength = 0;

    int[] out = new int[1];
    shifter.render(out, 1, phaseIncrement, 1 << 26, 0, 16, inputBuf, 512, stereoInput);

    // Since thisCrossfadeLength was 0, crossfadeProgress must stay at K_MAX_SAMPLE_VALUE (no fade
    // on the outgoing head), while nextCrossfadeLength was updated from the table (> 0) for the
    // following hop.
    assertEquals(
        LivePitchShifter.K_MAX_SAMPLE_VALUE,
        shifter.crossfadeProgress,
        "thisCrossfadeLength == 0 must skip fading the outgoing head (live_pitch_shifter.cpp:809)");
    assertTrue(
        shifter.nextCrossfadeLength > 0,
        "nextCrossfadeLength must be loaded from the table for the next hop");
  }

  @Test
  void patchCablePolarityRespectsAftertouchAndYSourceRules() {
    // C patch_cable.h:50-63: AFTERTOUCH is stored unipolar; Y is shared with mod wheel and
    // bypasses conversion; all other sources are stored bipolar.
    int halfMax = Integer.MAX_VALUE / 2;

    Patcher.PatchCable aftertouchUni = new Patcher.PatchCable();
    aftertouchUni.source = PatchSource.AFTERTOUCH.ordinal();
    aftertouchUni.polarity = Patcher.PatchCable.UNIPOLAR;
    assertEquals(0, aftertouchUni.toPolarity(0));
    assertEquals(123456789, aftertouchUni.toPolarity(123456789));

    Patcher.PatchCable aftertouchBi = new Patcher.PatchCable();
    aftertouchBi.source = PatchSource.AFTERTOUCH.ordinal();
    aftertouchBi.polarity = Patcher.PatchCable.BIPOLAR;
    assertEquals((0 - halfMax) * 2, aftertouchBi.toPolarity(0));
    assertEquals((halfMax - halfMax) * 2, aftertouchBi.toPolarity(halfMax));

    Patcher.PatchCable yUni = new Patcher.PatchCable();
    yUni.source = PatchSource.Y.ordinal();
    yUni.polarity = Patcher.PatchCable.UNIPOLAR;
    assertEquals(-500000000, yUni.toPolarity(-500000000));

    Patcher.PatchCable envUni = new Patcher.PatchCable();
    envUni.source = PatchSource.ENVELOPE_0.ordinal();
    envUni.polarity = Patcher.PatchCable.UNIPOLAR;
    assertEquals((-500000000 / 2) + halfMax, envUni.toPolarity(-500000000));
  }

  @Test
  void sincInterpolatorReusesKernelScratchAndMatchesReference() {
    // SincInterpolator.interpolate must reuse its instance kernelScratch without changing output.
    SincInterpolator interp = new SincInterpolator();
    for (int i = 0; i < SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES; i++) {
      interp.bufferL[i] = (short) (1000 + i * 300);
      interp.bufferR[i] = (short) (-2000 + i * 250);
    }

    int[] out1 = new int[2];
    int[] out2 = new int[2];
    interp.interpolate(2, 0, 0x00456700, out1);
    interp.interpolate(2, 0, 0x00456700, out2);

    assertEquals(out1[0], out2[0]);
    assertEquals(out1[1], out2[1]);
    assertTrue(out1[0] != 0 && out1[1] != 0);
  }
}
