package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareAudioEngine;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the 10 verified Slice 2 C++ faithfulness findings across {@link FmCore},
 * {@link Dx7Voice}, {@link Filter}, {@link FilterSet}, {@link LpLadderFilter}, and {@link
 * SVFilter}.
 */
class Fw2Slice2FindingsTest {

  private static FmCore.FmOpParams[] makeSilentFmParams() {
    FmCore.FmOpParams[] params = new FmCore.FmOpParams[6];
    for (int i = 0; i < 6; i++) {
      params[i] = new FmCore.FmOpParams();
      params[i].level_in = 0;
      params[i].gain_out = 0;
      params[i].phase = 0;
      params[i].freq = 0;
    }
    return params;
  }

  // ── Finding 1: FmCore.render buffer overrun on non-multiple-of-4 n ──

  @Test
  void finding1_fmCoreRenderDoesNotOverrunExactLengthBufferForNonMultipleOfFour() {
    for (int n : new int[] {1, 5, 129}) {
      FmCore.FmOpParams[] params = makeSilentFmParams();
      // Algorithm 0 (DX7 algo 1): op 4 is pure modulator -> bus 1, op 5 is modulated carrier -> out
      params[4].level_in = 14 << 24;
      params[4].gain_out = 1 << 22;
      params[4].freq = 0x00200000;
      params[5].level_in = 14 << 24;
      params[5].gain_out = 1 << 22;
      params[5].freq = 0x00400000;

      int[] out = new int[n];
      FmCore.render(out, n, params, 0, new int[2], 16);
      assertEquals(0x00200000 * n, params[4].phase);
      assertEquals(0x00400000 * n, params[5].phase);
    }
  }

  // ── Finding 2: FmCore NEON polynomial sine & sample-0 gain1 timing ──

  @Test
  void finding2_fmCoreRenderDefaultUsesNeonPolynomialKernelAndSampleZeroGain1() {
    // Algorithm 31 (DX7 algo 32): op 5 is a pure carrier writing directly to output.
    FmCore.FmOpParams[] neonParams = makeSilentFmParams();
    FmCore.FmOpParams[] scalarParams = makeSilentFmParams();
    int gain1 = 1 << 22;
    int levelIn = 14 << 24; // gain2 = 1 << 24
    int phase0 = 0x00400000; // quarter-cycle peak in Q24 phase domain

    neonParams[5].gain_out = gain1;
    neonParams[5].level_in = levelIn;
    neonParams[5].phase = phase0;
    neonParams[5].freq = 0x00310000;

    scalarParams[5].gain_out = gain1;
    scalarParams[5].level_in = levelIn;
    scalarParams[5].phase = phase0;
    scalarParams[5].freq = 0x00310000;

    int[] neonOut = new int[8];
    int[] scalarOut = new int[8];
    FmCore.render(neonOut, 8, neonParams, 31, new int[2], 16, true);
    FmCore.render(scalarOut, 8, scalarParams, 31, new int[2], 16, false);

    // At sample 0, phase = 0x00400000 => x = 0.0f => poly = 1.0f.
    // NEON uses gain1 on sample 0 (before adding dgain), so y1 = (int)(1.0f * (gain1 / 2^24) *
    // 2^24) = gain1.
    // Scalar pre-increments gain += dgain on sample 0, so scalarOut[0] > gain1.
    assertEquals(gain1, neonOut[0], "NEON kernel must apply gain1 on sample 0 before dgain step");
    assertTrue(scalarOut[0] > gain1, "Scalar fallback pre-increments gain += dgain on sample 0");
    assertNotEquals(neonOut[3], scalarOut[3]);

    // Check 1's-complement negative half-cycle sign flip ((p & 0x800000) != 0 => veor -1).
    FmCore.FmOpParams[] negParams = makeSilentFmParams();
    negParams[5].gain_out = 1 << 23;
    negParams[5].level_in = levelIn;
    negParams[5].phase = 0x00C00000; // negative half-cycle peak: (p & 0x800000) != 0, x = 0.0f
    negParams[5].freq = 0;
    int[] negOut = new int[4];
    FmCore.render(negOut, 4, negParams, 31, new int[2], 16, true);
    assertEquals((1 << 23) ^ -1, negOut[0], "NEON negative half-cycle uses 1's-complement veor");
  }

  // ── Finding 3: Dx7Voice.oscFreq float intermediate & fine constant ──

  @Test
  void finding3_dx7VoiceOscFreqUsesFloatDetuneRatioAndFullFineConstant() {
    Dx7Voice voice = new Dx7Voice();
    voice.randomDetuneScale = 0;
    int logFreq54 = Dx7Voice.dxNoteToFreq(54);

    // At MIDI note 54, mode=0, coarse=1 (COARSE_MUL[1]=0), fine=0, detune=14, random=0:
    // C dx7note.cpp:138 casts ((float)logFreq_for_detune) / (1 << 24) to float before exp(),
    // yielding detune delta 133810 (whereas pure double yields 133811).
    int actual = voice.oscFreq(logFreq54, 0, 1, 0, 14, 0);
    assertEquals(133810, actual);

    // Check fine constant 24204406.323123 (dx7note.cpp:146).
    int expectedFine = (int) Math.floor(24204406.323123 * Math.log(1 + 0.01 * 99) + 0.5);
    assertEquals(expectedFine, voice.oscFreq(logFreq54, 0, 1, 99, 7, 0));
  }

  // ── Finding 4: Dx7Voice.getDelay uint32 wrap on delta * n ──

  @Test
  void finding4_dx7VoiceGetDelayWrapsDeltaTimesNInUint32BeforeAddingToDelayState() {
    Dx7Voice voice = new Dx7Voice();
    // LFO delay = 0 sets delayInc = ~0u = -1 (dx7note.cpp:278-280).
    voice.delayInc = -1;
    voice.delayInc2 = -1;
    voice.delayState = 0;

    // C dx7note.cpp:295: uint64_t d = ((uint64_t)delaystate_) + delta * n;
    // With delta = 0xFFFFFFFFu and n = 128, delta * n wraps in uint32_t to 0xFFFFFF80u,
    // so d = 0xFFFFFF80u <= 0xFFFFFFFFu, delaystate_ becomes 0xFFFFFF80, and return is 16777215.
    int delay = voice.getDelay(128);
    assertEquals(16777215, delay);
    assertEquals(0xFFFFFF80, voice.delayState);
  }

  // ── Finding 5: Filter.filterStereo and filterMono blend cadence ──

  @Test
  void finding5_filterStereoAndMonoAdvanceBlendEveryBufferElement() {
    Filter identityFilter =
        new Filter() {
          @Override
          public void resetFilter() {}

          @Override
          public int setConfig(
              int frequency, int resonance, FilterSet.FilterMode mode, int morph, int filterGain) {
            return filterGain;
          }

          @Override
          public void doFilter(int[] buf, int startIdx, int endIdx, int step) {
            for (int i = startIdx; i < endIdx; i += step) {
              buf[i] = 1 << 28;
            }
          }

          @Override
          public void doFilterStereo(int[] buf, int startIdx, int endIdx) {
            for (int i = startIdx; i < endIdx; i++) {
              buf[i] = 1 << 28;
            }
          }
        };

    // Stereo: 4 elements (2 stereo frames) must advance dryFade 4 times (0.99^4), and R of frame 0
    // must use the updated wetLevel after element 0, not the same wetLevel as L.
    identityFilter.reset(true); // dryFade = 1.0f, wetLevel = 0
    int[] stereoBuf = {0, 0, 0, 0};
    identityFilter.filterStereo(stereoBuf, 0, 4);
    float expectedFade4 = 1.0f * 0.99f * 0.99f * 0.99f * 0.99f;
    assertEquals(expectedFade4, identityFilter.dryFade, 1e-7f);
    assertEquals(0, stereoBuf[0], "Element 0 starts at wetLevel=0 so output is purely dry (0)");
    assertTrue(
        stereoBuf[1] > 0, "Element 1 (R of frame 0) must use wetLevel after 1 updateBlend()");

    // Mono with step=2 across [0, 4): C filter.h:68-77 steps currentSample += 1 across [start,
    // end).
    identityFilter.reset(true);
    int[] monoBuf = {0, 0, 0, 0};
    identityFilter.filterMono(monoBuf, 0, 4, 2);
    assertEquals(expectedFade4, identityFilter.dryFade, 1e-7f);
  }

  // ── Finding 6: FilterSet union blend state transfer across filter families ──

  @Test
  void finding6_filterSetTransfersDryFadeAndWetLevelAcrossUnionFamilies() {
    FilterSet fs = new FilterSet();
    fs.reset();

    // Start LPF in TRANSISTOR_24DB and HPF in HPLADDER.
    fs.setConfig(
        1 << 24,
        1 << 27,
        FilterSet.FilterMode.TRANSISTOR_24DB,
        0,
        1 << 24,
        1 << 27,
        FilterSet.FilterMode.HPLADDER,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);

    // Simulate mid-fade state on both active filters.
    fs.lpLadder.dryFade = 0.42f;
    fs.lpLadder.wetLevel = 123456789;
    fs.hpLadder.dryFade = 0.37f;
    fs.hpLadder.wetLevel = 987654321;

    // Switch LPF to SVF_BAND and HPF to SVF_NOTCH while lastMode != OFF.
    fs.setConfig(
        1 << 24,
        1 << 27,
        FilterSet.FilterMode.SVF_BAND,
        0,
        1 << 24,
        1 << 27,
        FilterSet.FilterMode.SVF_NOTCH,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);

    assertEquals(0.42f, fs.lpSvf.dryFade, 1e-7f);
    assertEquals(123456789, fs.lpSvf.wetLevel);
    assertEquals(0.37f, fs.hpSvf.dryFade, 1e-7f);
    assertEquals(987654321, fs.hpSvf.wetLevel);

    // Switch back SVF -> LP_LADDER and SVF -> HPLADDER.
    fs.lpSvf.dryFade = 0.15f;
    fs.lpSvf.wetLevel = 555555555;
    fs.hpSvf.dryFade = 0.25f;
    fs.hpSvf.wetLevel = 444444444;
    fs.setConfig(
        1 << 24,
        1 << 27,
        FilterSet.FilterMode.TRANSISTOR_12DB,
        0,
        1 << 24,
        1 << 27,
        FilterSet.FilterMode.HPLADDER,
        0,
        1 << 28,
        FilterRoute.HIGH_TO_LOW);

    assertEquals(0.15f, fs.lpLadder.dryFade, 1e-7f);
    assertEquals(555555555, fs.lpLadder.wetLevel);
    assertEquals(0.25f, fs.hpLadder.dryFade, 1e-7f);
    assertEquals(444444444, fs.hpLadder.wetLevel);
  }

  // ── Finding 7: FilterSet.reset() clears hpfLastWorkingValue ──

  @Test
  void finding7_filterSetResetClearsHpLadderLastWorkingValueWhileFilterResetPreservesIt() {
    FilterSet fs = new FilterSet();
    fs.hpLadder.l.hpfLastWorkingValue = 0x12345678;
    fs.hpLadder.r.hpfLastWorkingValue = 0x87654321;

    // HPLadderState::reset() (hpladder.h:44-48) preserves hpfLastWorkingValue.
    fs.hpLadder.resetFilter();
    assertEquals(0x12345678, fs.hpLadder.l.hpfLastWorkingValue);
    assertEquals(0x87654321, fs.hpLadder.r.hpfLastWorkingValue);

    // FilterSet::reset() (filter_set.h:47 memset) zeroes both.
    fs.reset();
    assertEquals(0, fs.hpLadder.l.hpfLastWorkingValue);
    assertEquals(0, fs.hpLadder.r.hpfLastWorkingValue);
  }

  // ── Finding 8: LpLadderState.reset() preserves noiseLastValue; FilterSet.reset() zeroes it ──

  @Test
  void finding8_lpLadderStateResetPreservesNoiseLastValueWhileFilterSetResetZeroesIt() {
    FilterSet fs = new FilterSet();
    fs.lpLadder.l.noiseLastValue = 0x13579BDF;
    fs.lpLadder.r.noiseLastValue = 0x2468ACE0;

    // LpLadderState::reset() (lpladder.h:45-50) preserves noiseLastValue.
    fs.lpLadder.resetFilter();
    assertEquals(0x13579BDF, fs.lpLadder.l.noiseLastValue);
    assertEquals(0x2468ACE0, fs.lpLadder.r.noiseLastValue);

    // FilterSet::reset() (filter_set.h:47 memset) zeroes both.
    fs.reset();
    assertEquals(0, fs.lpLadder.l.noiseLastValue);
    assertEquals(0, fs.lpLadder.r.noiseLastValue);
  }

  // ── Finding 9: LpLadderFilter.setConfig oversampling cpuDireness < 14 guard ──

  @Test
  void finding9_lpLadderDriveOversamplingHonorsCpuDirenessCeiling() {
    LpLadderFilter ladder = new LpLadderFilter();
    int highFreq = 1 << 27; // quickLog(1<<27) >> 24 == 56 (> 51), threshold = 9000 << 16
    int highRes = 500_000_000; // processedResonance = 2_000_000_000 > threshold

    int savedDireness = FirmwareAudioEngine.cpuDireness;
    try {
      FirmwareAudioEngine.cpuDireness = 0;
      ladder.setConfig(highFreq, highRes, FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE, 0, 1 << 28);
      assertTrue(ladder.doOversampling, "Oversampling must engage when cpuDireness < 14");

      FirmwareAudioEngine.cpuDireness = 14;
      ladder.setConfig(highFreq, highRes, FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE, 0, 1 << 28);
      assertFalse(ladder.doOversampling, "Oversampling must be skipped when cpuDireness >= 14");
    } finally {
      FirmwareAudioEngine.cpuDireness = savedDireness;
    }
  }

  // ── Finding 10: SVFilter.setConfig POINT_25 double literal ──

  @Test
  void finding10_svFilterPoint25UsesDoubleTruncationMatchingCpp() {
    SVFilter svf = new SVFilter();
    int freq = 1 << 24;
    svf.curveFrequency(freq);
    int baseFc = svf.fc;

    int expectedPoint25 = 536870911; // (int)(ONE_Q31 * 0.25) in double, NOT 536870912 from 0.25f
    assertEquals(expectedPoint25, (int) (Functions.ONE_Q31 * 0.25));
    int expectedFc = baseFc + Functions.multiply_32x32_rshift32(baseFc, expectedPoint25);

    svf.setConfig(freq, 1 << 26, FilterSet.FilterMode.SVF_BAND, 0, 1 << 28);
    assertEquals(expectedFc, svf.fc);
  }
}
