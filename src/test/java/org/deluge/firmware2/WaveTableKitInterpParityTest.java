package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * C++ parity tests for {@link WaveTable}, {@link Kit}, {@link SincInterpolator}, and {@link
 * SineOsc}.
 */
class WaveTableKitInterpParityTest {

  @Test
  void waveTableGetKernelIndexTreatsPhaseIncrementUnsigned() {
    // C wave_table.cpp:1026-1050: phaseIncrementHere is uint32_t.
    // For cycleSizeMagnitude = 4 (16-sample cycle), maxPhaseIncrement = (0xFFFFFFFF >>> 4) * 1.25
    // = 335_544_318 (0x13FFFFFE). When phaseIncrement = 0x80000000 (2_147_483_648 unsigned),
    // Java signed int comparison saw phaseIncrementHere < 0 < bandMaxPhaseIncrement and returned 0.
    int bandMaxPhaseIncrement = (int) ((0xFFFFFFFFL >>> 4) * 1.25);
    int highPhaseIncrement = 0x80000000;

    // Unsigned trace:
    // iter 0: 0x80000000 (2147483648) >= 335544318 -> whichKernel=2, here=0x40000000 (1073741824)
    // iter 1: 0x40000000 (1073741824) >= 335544318 -> whichKernel=4, here=0x20000000 (536870912)
    // iter 2: 0x20000000 (536870912)  >= 335544318 -> whichKernel=6, here=0x10000000 (268435456)
    // loop stops at whichKernel == 6.
    assertEquals(6, WaveTable.getKernelIndex(highPhaseIncrement, bandMaxPhaseIncrement));

    // Also verify the 0.707 step-up when bit 31 is set and bandMaxPhaseIncrement is large enough
    // that the loop runs zero times: e.g. bandMax = 0x70000000, phaseInc = 0x60000000 (> 0.707*max)
    // vs bandMax = 0x7FFFFFFF, phaseInc = 0x80000000 (loop runs once -> whichKernel=2,
    // here=0x40000000).
    assertEquals(2, WaveTable.getKernelIndex(0x80000000, 0x7FFFFFFF));
  }

  @Test
  void kitRenderInternalSumsAllDrumRows() {
    // C kit.cpp:582 / sound.cpp:2630: each drum Sound sums into the shared Kit output and reverb
    // buffers rather than overwriting earlier drum rows.
    Kit kit = new Kit();
    Sound drum0 = kit.drums.get(0);
    Sound drum1 = kit.drums.get(1);

    for (Sound drum : new Sound[] {drum0, drum1}) {
      Sound.initParams(drum.patchedParamValues);
      drum.oscTypes[0] = Oscillator.OscType.SINE;
      drum.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
      drum.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      drum.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
      drum.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = 1 << 28;
      drum.srrParam = Integer.MIN_VALUE;
      drum.bitcrushParam = Integer.MIN_VALUE;
    }

    // Trigger only drum 0 (not the last drum row 15) and verify Kit output is non-silent.
    kit.triggerDrum(0, 127);

    int numSamples = 16;
    int[] outOnlyDrum0 = new int[numSamples * 2];
    int[] revOnlyDrum0 = new int[numSamples];
    kit.renderOutput(outOnlyDrum0, numSamples, revOnlyDrum0);

    long energyDrum0 = 0;
    long revEnergyDrum0 = 0;
    for (int v : outOnlyDrum0) {
      energyDrum0 += Math.abs((long) v);
    }
    for (int v : revOnlyDrum0) {
      revEnergyDrum0 += Math.abs((long) v);
    }
    assertTrue(energyDrum0 > 0, "Drum 0 output must not be overwritten by later quiet drum rows");
    assertTrue(revEnergyDrum0 > 0, "Drum 0 reverb send must not be overwritten by later drum rows");

    // Now render two identical Kits: one with only drum 0 triggered, one with both drum 0 and 1.
    Kit kitA = new Kit();
    Kit kitAB = new Kit();
    for (Kit k : new Kit[] {kitA, kitAB}) {
      for (int r = 0; r < 2; r++) {
        Sound d = k.drums.get(r);
        Sound.initParams(d.patchedParamValues);
        d.oscTypes[0] = Oscillator.OscType.SINE;
        d.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = 1 << 29;
        d.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
        d.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
        d.srrParam = Integer.MIN_VALUE;
        d.bitcrushParam = Integer.MIN_VALUE;
      }
    }
    kitA.triggerDrum(0, 100);
    kitAB.triggerDrum(0, 100);
    kitAB.triggerDrum(1, 100);

    int[] outA = new int[numSamples * 2];
    int[] outAB = new int[numSamples * 2];
    kitA.renderOutput(outA, numSamples, null);
    kitAB.renderOutput(outAB, numSamples, null);

    long absSumA = 0;
    long absSumAB = 0;
    for (int i = 0; i < numSamples * 2; i++) {
      absSumA += Math.abs((long) outA[i]);
      absSumAB += Math.abs((long) outAB[i]);
    }
    assertTrue(absSumAB > absSumA, "Two active drum rows must sum to greater energy than one");
  }

  @Test
  void sincInterpolatorUsesUnsignedShiftsForOscPosAndPhase() {
    // C interpolate.cpp:9, 17, 25, 70, 72: oscPos and phase are uint32_t.
    SincInterpolator interp = new SincInterpolator();
    for (int i = 0; i < SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES; i++) {
      interp.bufferL[i] = (short) ((i + 1) * 100);
      interp.bufferR[i] = (short) ((i + 1) * -50);
    }

    // 1. interpolateLinear with bit 31 set: phase = 0x80000200 -> phase >>> 9 = 0x00400001 ->
    // int16_t strength2 = 1, strength1 = 32766. Signed >> 9 would give -1 (0xFFFF).
    int phase = 0x80000200;
    int[] linearOut = interp.interpolateLinear(2, phase);
    short expectedStrength2 = (short) (phase >>> 9);
    short expectedStrength1 = (short) (0x7FFF - expectedStrength2);
    int expectedL = interp.bufferL[1] * expectedStrength1 + interp.bufferL[0] * expectedStrength2;
    int expectedR = interp.bufferR[1] * expectedStrength1 + interp.bufferR[0] * expectedStrength2;
    assertEquals(expectedL, linearOut[0]);
    assertEquals(expectedR, linearOut[1]);

    // 2. interpolate: verify unsigned >>> shift matches interpolateWide for normal 24-bit oscPos
    // and compare kernel lerp with explicit unsigned formula.
    int oscPos = 0x00FEDCBA;
    int[] sincOut = interp.interpolate(1, 0, oscPos);
    int strength2 = (oscPos >>> 5) & 0x7FFF;
    int progressSmall = oscPos >>> 20;
    short[] k1 = SincInterpolator.WINDOWED_SINC_KERNEL[0][progressSmall];
    short[] k2 = SincInterpolator.WINDOWED_SINC_KERNEL[0][progressSmall + 1];
    int expectedConv = 0;
    for (int i = 0; i < SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES; i++) {
      int prod = SincInterpolator.sat16(((k2[i] - k1[i]) * strength2) >> 15);
      short kc = (short) SincInterpolator.sat16(k1[i] + prod);
      expectedConv += kc * interp.bufferL[i];
    }
    assertEquals(expectedConv, sincOut[0]);
  }

  @Test
  void sineOscVectorRenderMatchesArgonRenderQuantization() {
    // C sine_osc.h:40-63: doFMVector(phaseVector, phaseShift) calls
    // render(phaseVector + (phaseShift << 8)), extracting 15-bit strength2 = (phase >>> 9) & 0x7FFF
    // and multiplying by 2 via MultiplyDoubleAddSaturateLong (VQDMAL.S16).
    int[] phaseVecA = {0x00000000, 0x7F000000, 0x80000000, 0xF0000000};
    int[] phaseVecB = {0x00000100, 0x7F000100, 0x80000100, 0xF0000100};
    int[] phaseShift = {0x000001, 0x001001, 0x002001, 0x003001};

    // For lane 0:
    // phaseVecA[0] + (1 << 8) = 0x00000100 -> strength2 = (0x100 >>> 9) & 0x7FFF = 0 -> output = 0.
    // (Old doFMVector4 used 16-bit strength2 = ((0 >>> 8) + 1) & 0xFFFF = 1 -> output = 804.)
    // phaseVecB[0] + (1 << 8) = 0x00000200 -> strength2 = (0x200 >>> 9) & 0x7FFF = 1 -> output =
    // 1608.
    int[] outA = new int[4];
    int[] outB = new int[4];
    SineOsc.doFMVector4(phaseVecA, phaseShift, outA);
    SineOsc.doFMVector4(phaseVecB, phaseShift, outB);

    assertEquals(0, outA[0], "Bit 8 of phase must shift out in 15-bit Argon strength2");
    assertEquals(1608, outB[0], "Carry into bit 9 must advance 15-bit Argon strength2 by 1");

    for (int i = 0; i < 4; i++) {
      assertEquals(expectedSineRender(phaseVecA[i] + (phaseShift[i] << 8)), outA[i]);
      assertEquals(expectedSineRender(phaseVecB[i] + (phaseShift[i] << 8)), outB[i]);
    }

    // Also verify render4 matches expectedSineRender across all 4 lanes.
    int[] renderPhases = {0x10000000, 0x10000000, 0x10000000, 0x10000000};
    int[] expectedRender = new int[4];
    int inc = 0x03333333;
    for (int i = 0; i < 4; i++) {
      expectedRender[i] = expectedSineRender(0x10000000 + inc * (i + 1));
    }
    int[] actualRender = new int[4];
    SineOsc.render4(renderPhases, inc, actualRender);
    assertArrayEquals(expectedRender, actualRender);
  }

  private static int expectedSineRender(int phase) {
    int strength2 = (phase >>> 9) & 0x7FFF;
    int index = (phase >>> 24) << 1;
    int sine = (short) SineOsc.SINE_WAVE_DIFF[index] << 16;
    int diff = SineOsc.SINE_WAVE_DIFF[index + 1];
    return Functions.add_saturate(sine, diff * strength2 * 2);
  }
}
