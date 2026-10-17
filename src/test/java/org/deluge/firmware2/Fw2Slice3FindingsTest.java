package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Dedicated C++ parity regression tests for firmware2 Slice 3 (Delay, DelayBuffer, Compressor, Eq,
 * GranularProcessor).
 */
class Fw2Slice3FindingsTest {

  private static final int ONE_Q31 = 2147483647;
  private static final float ONE_Q31F = 2147483647.0f;
  private static final int K_SAMPLE_RATE = 44100;

  @Test
  void delayResamplingReadClearsEveryConsumedCellAndTracksWrap() {
    // C delay.cpp:304-308: primaryBuffer.advance([&]() { wrapped =
    // primaryBuffer.clearAndMoveOn() || wrapped; })
    Delay delay = new Delay();
    int nativeRate = Delay.K_MAX_SAMPLE_VALUE;
    assertTrue(delay.primaryBuffer.init(nativeRate));

    int sizeIncludingExtra = delay.primaryBuffer.sizeIncludingExtra;
    for (int i = 0; i < sizeIncludingExtra; i++) {
      delay.primaryBuffer.data[i][0] = 10000 + i;
      delay.primaryBuffer.data[i][1] = 20000 + i;
    }

    // Position two cells before end_ and spin at 1.75x native rate (< 2x so secondaryBuffer is not
    // auto-spawned) for 2 samples (= 3.5 cells, crossing two cell boundaries on sample 1 and
    // wrapping end_ -> 0).
    int fastRate = nativeRate + (nativeRate >> 1) + (nativeRate >> 2);
    delay.primaryBuffer.currentIdx = delay.primaryBuffer.end_ - 2;
    delay.primaryBuffer.setupForRender(fastRate);

    Delay.State state = new Delay.State();
    state.doDelay = true;
    state.userDelayRate = fastRate;
    state.delayFeedbackAmount = 1 << 29;
    delay.repeatsUntilAbandon = 2;

    int startIdx = delay.primaryBuffer.currentIdx;
    int secondIdx = startIdx + 1;
    int[][] buf = new int[][] {{0, 0}, {0, 0}};
    delay.process(buf, 2, state);

    assertEquals(
        0,
        delay.primaryBuffer.data[startIdx][0],
        "First consumed cell must be cleared during >1x resampling read (delay.cpp:307)");
    assertEquals(
        0,
        delay.primaryBuffer.data[secondIdx][0],
        "Second consumed cell must also be cleared during >1x resampling read (delay.cpp:307)");
    assertEquals(
        1,
        delay.repeatsUntilAbandon,
        "Ring wrap during primary resampling read must decrement repeatsUntilAbandon (delay.cpp:447)");
  }

  @Test
  void delayEchoMixUsesPlainWrappingAddition() {
    // C delay.cpp:385: output += current (plain StereoSample::operator+=).
    Delay delay = new Delay();
    int rate = Delay.K_MAX_SAMPLE_VALUE;
    assertTrue(delay.primaryBuffer.init(rate));

    int readIdx = (delay.primaryBuffer.currentIdx + 1) % delay.primaryBuffer.sizeIncludingExtra;
    delay.primaryBuffer.data[readIdx][0] = 1_500_000_000;
    delay.primaryBuffer.data[readIdx][1] = -1_500_000_000;

    Delay.State state = new Delay.State();
    state.doDelay = true;
    state.userDelayRate = rate;
    state.delayFeedbackAmount = 1 << 29;

    int[][] buf = new int[][] {{1_500_000_000, -1_500_000_000}};
    delay.process(buf, 1, state);

    // Compute the exact echo after digital feedback + 1-pole HPF (delay.cpp:348-368) and verify
    // plain += (delay.cpp:385).
    int afterFeedbackL =
        Functions.lshiftAndSaturate(
            Functions.signed_saturate(
                Functions.multiply_32x32_rshift32(1_500_000_000, state.delayFeedbackAmount), 29),
            2);
    int postLpfL = afterFeedbackL >> 11;
    int currentL = afterFeedbackL - postLpfL;

    assertEquals(1_500_000_000 + currentL, buf[0][0]);
  }

  @Test
  void delayBufferHighSpinRateUsesUnsignedComparisonAndShift() {
    // C delay_buffer.cpp:143-182: actualSpinRate, spinRateForSpedUpWriting, and divideByRate are
    // uint32_t; high-bit actualSpinRate (>= 0x80000000) must take the fast-spin branch.
    int nativeRate = DelayBuffer.K_MAX_SAMPLE_VALUE;
    int highRate = nativeRate * 140; // actualSpinRate = 140 * 2^24 = 0x8C000000 (negative in int32)

    DelayBuffer standalone = new DelayBuffer();
    assertEquals(DelayBuffer.Error.NONE, standalone.init(nativeRate));
    standalone.setupForRender(highRate);

    Delay delay = new Delay();
    assertTrue(delay.primaryBuffer.init(nativeRate));
    delay.primaryBuffer.setupForRender(highRate);

    int expectedActualSpinRate =
        (int)
            (long)
                ((double) ((highRate & 0xFFFFFFFFL) << 24)
                    / (double) (standalone.nativeRate() & 0xFFFFFFFFL));
    assertTrue(expectedActualSpinRate < 0, "Test rate must set sign bit of actualSpinRate");
    assertEquals(expectedActualSpinRate, standalone.resampleConfig.actualSpinRate);
    assertEquals(expectedActualSpinRate, delay.primaryBuffer.actualSpinRate);

    // Boundary case where C double division ((uint64_t)((double)...)) rounds up to 0x7f000001
    // while integer uint64 division yields 0x7f000000 (delay_buffer.cpp:143).
    DelayBuffer boundaryBuf = new DelayBuffer();
    assertEquals(DelayBuffer.Error.NONE, boundaryBuf.init(16_777_217));
    boundaryBuf.setupForRender(2_130_706_560);
    assertEquals(0x7f000001, boundaryBuf.resampleConfig.actualSpinRate);

    Delay boundaryDelay = new Delay();
    assertTrue(boundaryDelay.primaryBuffer.init(16_777_217));
    boundaryDelay.primaryBuffer.setupForRender(2_130_706_560);
    assertEquals(0x7f000001, boundaryDelay.primaryBuffer.actualSpinRate);

    int expectedCappedFastSpin = (DelayBuffer.K_MAX_SAMPLE_VALUE * 8) << 1;
    assertEquals(
        expectedCappedFastSpin,
        standalone.resampleConfig.spinRateForSpedUpWriting,
        "Standalone DelayBuffer must take fast-spin branch and cap at 8 * kMaxSampleValue");
    assertEquals(
        expectedCappedFastSpin,
        delay.primaryBuffer.spinRateForSpedUpWriting,
        "Delay.DelayBuffer must take fast-spin branch and cap at 8 * kMaxSampleValue");

    int rawDivide = (int) (long) ((double) 0xFFFFFFFFL / (double) (expectedActualSpinRate >>> 8));
    assertEquals(rawDivide >>> 1, standalone.resampleConfig.divideByRate);
    assertEquals(rawDivide >>> 1, delay.primaryBuffer.divideByRate);
  }

  @Test
  void delayDottedSyncClampsAfterSyncTypeAdjustment() {
    // C delay.cpp:107-118: adjustedRate = adjustedRate * 2 / 3 BEFORE std::min<int64_t>(...,
    // limit).
    Delay delay = new Delay();
    delay.syncLevel = 5; // shift = 10, limit = INT_MAX >> 10 = 2097151
    delay.syncType = 2; // DOTTED (* 2 / 3)

    Delay.State state = new Delay.State();
    state.delayFeedbackAmount = 1 << 20;
    state.userDelayRate = 12_000_000;

    // Neutralize multiply_32x32_rshift32_rounded(rate, tickInv) by choosing tickInv = 1 << 30
    // and checking against the exact C++ order (afterTick = 3_000_000 > limit 2_097_151, while
    // dottedAfter = 2_000_000 < limit 2_097_151).
    int tickInv = 1 << 30;
    delay.setupWorkingState(state, tickInv, true);

    int afterTick = Functions.multiply_32x32_rshift32_rounded(12_000_000, tickInv);
    long dottedAfter = (long) afterTick * 2L / 3L;
    int limit = 2147483647 >> (delay.syncLevel + 5);
    assertTrue(afterTick > limit && dottedAfter < limit);
    int expected = ((int) Math.min(dottedAfter, (long) limit)) << (delay.syncLevel + 5);
    assertEquals(expected, state.userDelayRate);
  }

  @Test
  void compressorFloatPrecisionMatchesCpp() {
    // C rms_feedback.h:80-147, rms_feedback.cpp:41, 133, 152-164.
    Compressor comp = new Compressor();
    int knob = 1234567890;

    comp.setAttack(knob);
    float expAttack = (float) Math.exp(2.0f * (float) knob / ONE_Q31F);
    float expectedAttackMs = (float) (0.5 + (expAttack - 1.0f) * 10.0f);
    float expectedA = (-1000.0f / K_SAMPLE_RATE) / expectedAttackMs;
    assertEquals(Float.floatToRawIntBits(expectedAttackMs), Float.floatToRawIntBits(comp.attackMS));
    assertEquals(Float.floatToRawIntBits(expectedA), Float.floatToRawIntBits(comp.a_));

    comp.setRelease(knob);
    float expRelease = (float) Math.exp(2.0f * (float) knob / ONE_Q31F);
    float expectedReleaseMs = 50.0f + (expRelease - 1.0f) * 50.0f;
    float expectedR = (-1000.0f / K_SAMPLE_RATE) / expectedReleaseMs;
    assertEquals(
        Float.floatToRawIntBits(expectedReleaseMs), Float.floatToRawIntBits(comp.releaseMS));
    assertEquals(Float.floatToRawIntBits(expectedR), Float.floatToRawIntBits(comp.r_));

    comp.setThreshold(knob);
    float expectedThreshold = 1.0f - 0.8f * ((float) knob / ONE_Q31F);
    assertEquals(
        Float.floatToRawIntBits(expectedThreshold), Float.floatToRawIntBits(comp.threshold));

    comp.setRatio(knob);
    float expectedFraction = 0.5f + ((float) knob / ONE_Q31F) / 2.0f;
    float expectedRatio = 1.0f / (1.0f - expectedFraction);
    assertEquals(Float.floatToRawIntBits(expectedFraction), Float.floatToRawIntBits(comp.fraction));
    assertEquals(Float.floatToRawIntBits(expectedRatio), Float.floatToRawIntBits(comp.ratio));

    comp.setSidechain(knob);
    float expectedFcHz =
        (float) ((Math.exp(1.5 * (double) ((float) knob) / (double) ONE_Q31F) - 1.0) * 30.0);
    assertEquals(Float.floatToRawIntBits(expectedFcHz), Float.floatToRawIntBits(comp.fc_hz));

    float env = comp.runEnvelope(0.25f, 1.75f, 32.0f);
    float expectedEnv = 1.75f + (float) Math.exp(comp.a_ * 32.0f) * (0.25f - 1.75f);
    assertEquals(Float.floatToRawIntBits(expectedEnv), Float.floatToRawIntBits(env));
  }

  @Test
  void eqUsesPlainWrappingShiftsAndAdditions() {
    // C mod_controllable_audio.cpp:378-402: plain << 1, << 3, and += (no saturating helpers).
    Eq eq = new Eq();
    int[][] buffer = new int[][] {{1_900_000_000, -1_900_000_000}};
    int bassParam = 1 << 29;
    int trebleParam = 1 << 29;
    int bassFreqParam = 1 << 28;
    int trebleFreqParam = 1 << 28;

    eq.process(buffer, 1, bassParam, trebleParam, bassFreqParam, trebleFreqParam);

    int posBass = (bassParam >> 1) + 1073741824;
    int bassAmount = (Functions.multiply_32x32_rshift32_rounded(posBass, posBass) << 1) - 536870912;
    int posTreble = (trebleParam >> 1) + 1073741824;
    int trebleAmount = Functions.multiply_32x32_rshift32_rounded(posTreble, posTreble) << 1;
    int bassFreq = Functions.getExp(120000000, (bassFreqParam >> 5) * 6);
    int trebleFreq = Functions.getExp(700000000, (trebleFreqParam >> 5) * 6);

    int inL = 1_900_000_000;
    int withoutTrebleL = Functions.multiply_32x32_rshift32(inL, trebleFreq) << 1;
    int trebleOnlyL = inL - withoutTrebleL;
    int afterTrebleRemoveL = withoutTrebleL;
    int bassOnlyL = Functions.multiply_32x32_rshift32(afterTrebleRemoveL, bassFreq);
    int expectedL =
        afterTrebleRemoveL
            + (Functions.multiply_32x32_rshift32(trebleOnlyL, trebleAmount) << 3)
            + (Functions.multiply_32x32_rshift32(bassOnlyL, bassAmount) << 3);

    assertTrue(expectedL < 0, "Test input must cross 32-bit signed overflow to test wrapping");
    assertEquals(expectedL, buffer[0][0]);
  }

  @Test
  void granularProcessesFinalWrapWhenWrapsToShutdownIsZero() {
    // C GranularProcessor.cpp:50: if (anySoundComingIn || wrapsToShutdown >= 0).
    GranularProcessor gp = new GranularProcessor();
    gp.wrapsToShutdown = 0;

    int[][] buffer = new int[][] {{1 << 26, -(1 << 26)}};
    int[] postFxVolume = new int[] {1 << 28};

    gp.processGrainFX(buffer, 1, 1 << 24, 1 << 29, 1 << 28, 0, postFxVolume, false, 120.0f);
    assertNotEquals(
        1 << 28,
        postFxVolume[0],
        "wrapsToShutdown == 0 must still execute setupGrainFX/processGrainFX (GranularProcessor.cpp:50)");

    int[] postFxStopped = new int[] {1 << 28};
    gp.wrapsToShutdown = -1;
    gp.processGrainFX(buffer, 1, 1 << 24, 1 << 29, 1 << 28, 0, postFxStopped, false, 120.0f);
    assertEquals(
        1 << 28,
        postFxStopped[0],
        "wrapsToShutdown < 0 with no sound coming in must skip processGrainFX");
  }

  @Test
  void granularWetDryAndReverbBackdoorUseQ31Mult() {
    // C GranularProcessor.cpp:64-76, fixedpoint.h:62-67, audio_engine.cpp:1010-1012.
    GranularProcessor gp = new GranularProcessor();

    // Prime 1 sample with mix = 0 (100% dry, grainDryVol = ONE_Q31, grainVol = 0) and check dry
    // q31_mult (multiply_32x32_rshift32 << 1).
    int drySample = 500_000_000;
    int[][] dryBuf = new int[][] {{drySample, -drySample}};
    int[] postFx = new int[] {1 << 28};
    gp.processGrainFX(dryBuf, 1, 1 << 24, 0, 1 << 28, 0, postFx, true, 120.0f);

    int expectedDry = Functions.multiply_32x32_rshift32(drySample, gp.grainDryVol) << 1;
    assertEquals(expectedDry, dryBuf[0][0], "Dry path must use q31_mult (<< 1)");

    // Now initialize grain buffer and force an active grain + reverb backdoor send.
    gp.grainInitialized = true;
    gp.bufferFull = true;
    gp.bufferWriteIndex = 70000;
    for (int i = 0; i < 1024; i++) {
      gp.grainBuffer[i][0] = 200_000_000;
      gp.grainBuffer[i][1] = 200_000_000;
    }
    gp.grains[0].length = 1024;
    gp.grains[0].startPoint = 256;
    gp.grains[0].counter = 256;
    gp.grains[0].pitch = 1024;
    gp.grains[0].volScale = ONE_Q31 / 512;
    gp.grains[0].volScaleMax = ONE_Q31;
    gp.grains[0].rev = false;
    gp.grains[0].panVolL = 1 << 30;
    gp.grains[0].panVolR = 1 << 30;

    int[][] wetBuf = new int[][] {{0, 0}};
    int[] reverbBuf = new int[] {0};
    int reverbAmount = 1 << 28;
    gp.processGrainFX(
        wetBuf,
        1,
        1 << 24,
        1 << 30,
        1 << 28,
        0,
        new int[] {1 << 28},
        true,
        120.0f,
        reverbAmount,
        reverbBuf);

    assertNotEquals(0, wetBuf[0][0], "Active grain should produce non-zero wet output");
    int expectedReverbFeed =
        Functions.multiply_32x32_rshift32(wetBuf[0][0] + wetBuf[0][1], reverbAmount) << 1;
    assertEquals(
        expectedReverbFeed,
        reverbBuf[0],
        "Reverb backdoor must add q31_mult(wetL + wetR, reverbAmount) into reverbBuffer");
  }

  @Test
  void granularUpdatesGrainRateBeforeGrainSize() {
    // C GranularProcessor.cpp:98-115: _grainRate is updated BEFORE _grainSize = q31_mult(_grainRate
    // << 3, density).
    GranularProcessor gp = new GranularProcessor();
    int rate = 1 << 27;
    int density = 1 << 28;
    gp.setupGrainFX(rate, 1 << 28, density, 0, new int[] {1 << 28});

    int densityQ31 = (density / 2) + 1073741824;
    int expectedGrainSize =
        1760 + (Functions.multiply_32x32_rshift32(gp.grainRate << 3, densityQ31) << 1);

    assertFalse(gp.grainRate == 550, "Test rate should change grainRate away from constructor 550");
    assertEquals(expectedGrainSize, gp.grainSize);
  }

  @Test
  void compressorHotBlendedInputDoesNotOverflowRmsToNan() {
    // C rms_feedback.cpp:144, 152: hot blended input (128 frames at 500_000_000) must not wrap
    // sum negative and produce NaN from Math.sqrt(mean).
    Compressor comp = new Compressor();
    comp.setSidechain(0); // hpfA_ = 0
    comp.hpfA_ = 0;
    comp.wet = 1 << 29;
    comp.dry = ONE_Q31 - comp.wet;

    int[][] buffer = new int[128][2];
    for (int i = 0; i < 128; i++) {
      buffer[i][0] = 500_000_000;
      buffer[i][1] = 500_000_000;
    }

    float rmsOut = comp.calcRMS(buffer, 128);
    assertTrue(Float.isFinite(comp.mean), "Compressor mean must stay finite on hot input");
    assertTrue(comp.mean > 0.0f, "Compressor mean must stay positive on hot input");
    assertTrue(Float.isFinite(rmsOut), "Compressor calcRMS must not return NaN on hot input");
  }

  @Test
  void soundGrainReverbUsesModulatedSendAndHalvesBeforeScaling() {
    // C sound.cpp:2450-2453, 2610, mod_controllable_audio.cpp:206-210: Sound::render resolves
    // GLOBAL_REVERB_AMOUNT with active patch cables, passes reverbSendAmount >> 1 into processFX,
    // and preserves grain reverb in GlobalEffectable.renderOutput even when the unpatched knob is
    // off.
    Sound sound = new Sound();
    sound.reverbSendKnob = Integer.MIN_VALUE; // GlobalEffectable base knob off
    sound.modFXType = ModFx.ModFXType.GRAIN;
    sound.modFXDepth = 1 << 29;
    sound.modFXRateIncrement = 1 << 24;
    sound.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = -1073741824;
    sound.patchedParamValues[Param.GLOBAL_VOLUME_POST_FX] = 0;
    sound.patchedParamValues[Param.GLOBAL_MOD_FX_DEPTH] = 1 << 30;
    sound.patchedParamValues[Param.GLOBAL_MOD_FX_RATE] = 0;

    int reverbAmountAdjust = 67108864;
    int unmodulatedSend = sound.computeReverbSendAmount(reverbAmountAdjust);

    // Square LFO_GLOBAL_1 at phase 0 outputs +ONE_Q31; patch it to GLOBAL_REVERB_AMOUNT.
    sound.lfoConfig[0].waveType = Lfo.LfoType.SQUARE;
    sound.globalLfos[0].phase = 0;
    Patcher.Destination dest = new Patcher.Destination(Param.GLOBAL_REVERB_AMOUNT);
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.LFO_GLOBAL_1.ordinal();
    cable.amount = 1 << 30;
    cable.rangeValue = 536870912;
    cable.polarity = Patcher.PatchCable.UNIPOLAR;
    dest.cables.add(cable);
    sound.patchCableSet.destinations.add(dest);

    // Prime an active grain inside sound.granular so renderInternal produces wet grain audio.
    sound.granular.grainInitialized = true;
    sound.granular.bufferFull = true;
    sound.granular.bufferWriteIndex = 70000;
    sound.granular.wrapsToShutdown = 1;
    for (int i = 0; i < 1024; i++) {
      sound.granular.grainBuffer[i][0] = 200_000_000;
      sound.granular.grainBuffer[i][1] = 200_000_000;
    }
    sound.granular.grains[0].length = 1024;
    sound.granular.grains[0].startPoint = 256;
    sound.granular.grains[0].counter = 256;
    sound.granular.grains[0].pitch = 1024;
    sound.granular.grains[0].volScale = ONE_Q31 / 512;
    sound.granular.grains[0].volScaleMax = ONE_Q31;
    sound.granular.grains[0].rev = false;
    sound.granular.grains[0].panVolL = 1 << 30;
    sound.granular.grains[0].panVolR = 1 << 30;

    // 1. Verify computeReverbSendAmount resolves the modulated cable.
    sound.globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] = ONE_Q31;
    int modulatedSend = sound.computeReverbSendAmount(reverbAmountAdjust);
    assertTrue(
        modulatedSend > unmodulatedSend,
        "Patch cable on GLOBAL_REVERB_AMOUNT must raise reverbSendAmount above unmodulated knob");

    // 2. Verify Sound.renderInternal scales grain reverb by (reverbSendAmount >> 1) * postFXVolume.
    int[] outBuf = new int[2];
    int[] revBuf = new int[1];
    sound.renderInternal(outBuf, 1, revBuf);
    assertNotEquals(0, revBuf[0], "Modulated grain reverb must write into trackReverbBuffer");

    int postFXVolume =
        Patcher.computeFinalValueForParam(
            Param.GLOBAL_VOLUME_POST_FX, sound.patchedParamValues[Param.GLOBAL_VOLUME_POST_FX]);
    int[] postFxAfterSrr = {postFXVolume};
    int expectedGrainReverbAmount =
        Functions.multiply_32x32_rshift32(postFxAfterSrr[0], modulatedSend >> 1) << 5;
    int expectedRevFeed =
        Functions.multiply_32x32_rshift32(outBuf[0] + outBuf[1], expectedGrainReverbAmount) << 1;
    assertEquals(
        expectedRevFeed,
        revBuf[0],
        "Sound.renderInternal must halve reverbSendAmount before scaling by postFXVolume");

    // 3. Verify GlobalEffectable.renderOutput preserves grain reverb in the caller's reverbBuffer.
    long[] callerOut = new long[2];
    long[] callerRev = new long[1];
    sound.granular.grains[0].length = 1024;
    sound.granular.grains[0].startPoint = 256;
    sound.granular.grains[0].counter = 256;
    sound.renderOutput(callerOut, 1, callerRev);
    assertNotEquals(
        0L,
        callerRev[0],
        "GlobalEffectable.renderOutput must preserve modulated grain reverb when base knob is off");
  }

  @Test
  void reverbSendSkipsLocalSourcesAndUsesSmoothedParamValue() {
    // C sound.cpp:1346-1348, patcher.cpp:219: local sources (>= kFirstLocalSource) must be skipped
    // on global params instead of throwing ArrayIndexOutOfBoundsException, and GLOBAL_REVERB_AMOUNT
    // must read getSmoothedPatchedParamValue.
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    sound.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = -1073741824;

    int reverbAmountAdjust = 67108864;
    int baselineSend = sound.computeReverbSendAmount(reverbAmountAdjust);

    // 1. Add disallowed local cables (ENVELOPE_1 and VELOCITY) directly onto GLOBAL_REVERB_AMOUNT.
    Patcher.Destination dest = new Patcher.Destination(Param.GLOBAL_REVERB_AMOUNT);
    Patcher.PatchCable envCable = new Patcher.PatchCable();
    envCable.source = PatchSource.ENVELOPE_1.ordinal();
    envCable.amount = 1 << 30;
    envCable.rangeValue = 536870912;
    dest.cables.add(envCable);
    Patcher.PatchCable velCable = new Patcher.PatchCable();
    velCable.source = PatchSource.VELOCITY.ordinal();
    velCable.amount = 1 << 30;
    velCable.rangeValue = 536870912;
    dest.cables.add(velCable);
    sound.patchCableSet.destinations.add(dest);

    assertEquals(
        baselineSend,
        sound.computeReverbSendAmount(reverbAmountAdjust),
        "Disallowed local patch sources on GLOBAL_REVERB_AMOUNT must be ignored safely");

    // 2. Also verify FirmwareSound.syncParamsToFw2 filters out local-to-global cables.
    org.deluge.engine.FirmwareSound fwSound = new org.deluge.engine.FirmwareSound();
    org.deluge.modulation.patch.PatchCable bridgeCable =
        new org.deluge.modulation.patch.PatchCable();
    bridgeCable.from = org.deluge.modulation.patch.PatchSource.ENVELOPE_1;
    bridgeCable.amount = 1 << 30;
    fwSound.paramManager.getPatchCableSet().addCable(Param.GLOBAL_REVERB_AMOUNT, bridgeCable);
    fwSound.syncParamsToFw2();
    assertTrue(
        fwSound.fw2Sound.patchCableSet.destinations.isEmpty(),
        "FirmwareSound.syncParamsToFw2 must filter out disallowed local-to-global cables");

    // 3. Verify computeReverbSendAmount reads the smoothed parameter value during LPF ramping.
    sound.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = 0;
    int unsmoothedTargetSend = sound.computeReverbSendAmount(reverbAmountAdjust);
    assertTrue(unsmoothedTargetSend > baselineSend);

    sound.notifyValueChangeViaLPF(Param.GLOBAL_REVERB_AMOUNT, true, -1073741824, 0, false);
    assertEquals(
        baselineSend,
        sound.computeReverbSendAmount(reverbAmountAdjust),
        "computeReverbSendAmount must use getSmoothedPatchedParamValue while LPF is active");
  }

  @Test
  void shortNoteGrainTailRendersBeforeFullBufferWrap() {
    // C sound.cpp:2207-2209, GranularProcessor.cpp:50, 84-86: grain tail must keep rendering once
    // audio has entered the grain buffer, even if the note ended before 65,536 samples set
    // grainInitialized.
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    sound.srrParam = Integer.MIN_VALUE;
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.modFXType = ModFx.ModFXType.GRAIN;
    sound.modFXDepth = 1 << 29;
    sound.modFXRateIncrement = 1 << 24;

    // Simulate a short note (~0.9s = 40,000 samples) that filled > half the buffer (bufferFull =
    // true) and spawned an active grain, but ended before 65,536 samples (grainInitialized =
    // false).
    sound.granular.grainInitialized = false;
    sound.granular.bufferFull = true;
    sound.granular.bufferWriteIndex = 40_000;
    sound.granular.wrapsToShutdown = 1;
    for (int i = 0; i < 1024; i++) {
      sound.granular.grainBuffer[i][0] = 200_000_000;
      sound.granular.grainBuffer[i][1] = 200_000_000;
    }
    sound.granular.grains[0].length = 1024;
    sound.granular.grains[0].startPoint = 256;
    sound.granular.grains[0].counter = 256;
    sound.granular.grains[0].pitch = 1024;
    sound.granular.grains[0].volScale = ONE_Q31 / 512;
    sound.granular.grains[0].volScaleMax = ONE_Q31;
    sound.granular.grains[0].rev = false;
    sound.granular.grains[0].panVolL = 1 << 30;
    sound.granular.grains[0].panVolR = 1 << 30;

    int[] outBuf = new int[2];
    int[] revBuf = new int[1];
    sound.renderInternal(outBuf, 1, revBuf);
    assertNotEquals(
        0,
        outBuf[0],
        "Short-note grain tail must continue rendering before grainInitialized reaches 65,536 samples");
  }

  @Test
  void grainAndOrdinaryReverbSendsUseSameSmoothedValueWithinBlock() {
    // C sound.cpp:2450-2453, 2610, 2613, 2646: reverbSendAmount is resolved once per block before
    // doParamLPF advances paramLPF.currentValue, so grain and ordinary sends see the same value.
    Sound smoothed = new Sound();
    Sound fixed = new Sound();
    for (Sound s : new Sound[] {smoothed, fixed}) {
      Sound.initParams(s.patchedParamValues);
      s.srrParam = Integer.MIN_VALUE;
      s.bitcrushParam = Integer.MIN_VALUE;
      s.modFXType = ModFx.ModFXType.GRAIN;
      s.modFXDepth = 1 << 29;
      s.modFXRateIncrement = 1 << 24;
      s.granular.grainInitialized = true;
      s.granular.bufferFull = true;
      s.granular.bufferWriteIndex = 40_000;
      s.granular.wrapsToShutdown = 2;
      for (int i = 0; i < 1024; i++) {
        s.granular.grainBuffer[i][0] = 200_000_000;
        s.granular.grainBuffer[i][1] = 200_000_000;
      }
      s.granular.grains[0].length = 1024;
      s.granular.grains[0].startPoint = 256;
      s.granular.grains[0].counter = 256;
      s.granular.grains[0].pitch = 1024;
      s.granular.grains[0].volScale = ONE_Q31 / 512;
      s.granular.grains[0].volScaleMax = ONE_Q31;
      s.granular.grains[0].rev = false;
      s.granular.grains[0].panVolL = 1 << 30;
      s.granular.grains[0].panVolR = 1 << 30;
    }

    int startReverbKnob = -1073741824;
    fixed.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = startReverbKnob;
    smoothed.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = 0;
    smoothed.notifyValueChangeViaLPF(Param.GLOBAL_REVERB_AMOUNT, true, startReverbKnob, 0, false);

    long[] outFixed = new long[128];
    long[] revFixed = new long[64];
    long[] outSmoothed = new long[128];
    long[] revSmoothed = new long[64];

    Functions.resetNoiseSeed();
    fixed.renderOutput(outFixed, 64, revFixed);
    Functions.resetNoiseSeed();
    smoothed.renderOutput(outSmoothed, 64, revSmoothed);

    assertTrue(
        smoothed.paramLPF.currentValue > startReverbKnob,
        "doParamLPF must advance paramLPF.currentValue at the end of the block");
    assertNotEquals(0L, revSmoothed[0], "Reverb buffer must receive non-zero send");
    for (int i = 0; i < 64; i++) {
      assertEquals(
          revFixed[i],
          revSmoothed[i],
          "Grain and ordinary reverb sends must both use the pre-doParamLPF smoothed value at sample "
              + i);
    }
  }

  @Test
  void reverbTailPreservesGlobalRangeDepthModulationAfterVoicesEnd() {
    // C sound.cpp:2446, patcher.cpp:85-105: global range/depth cables on GLOBAL_REVERB_AMOUNT must
    // be evaluated every block even when voices_.empty() (tail-only rendering).
    org.deluge.engine.FirmwareSound withoutRange = new org.deluge.engine.FirmwareSound();
    org.deluge.engine.FirmwareSound withRange = new org.deluge.engine.FirmwareSound();

    for (org.deluge.engine.FirmwareSound fs :
        new org.deluge.engine.FirmwareSound[] {withoutRange, withRange}) {
      Sound.initParams(fs.paramNeutralValues);
      Sound.initParams(fs.paramKnobs);
      fs.paramKnobsPopulated = true;
      fs.paramKnobs[Param.GLOBAL_REVERB_AMOUNT] = -1073741824;
      fs.paramKnobs[Param.GLOBAL_LFO_FREQ_1] = Integer.MIN_VALUE;
      fs.paramKnobs[Param.GLOBAL_LFO_FREQ_2] = Integer.MIN_VALUE;
      fs.lfoWaveforms[0] = Lfo.LfoType.SQUARE;
      fs.lfoWaveforms[2] = Lfo.LfoType.SQUARE;
      fs.globalLfos[0].phase = 0; // SQUARE outputs +ONE_Q31
      fs.globalLfos[1].phase = 0; // SQUARE outputs +ONE_Q31
      fs.fw2Sound.srrParam = Integer.MIN_VALUE;
      fs.fw2Sound.bitcrushParam = Integer.MIN_VALUE;
      fs.fw2Sound.modFXType = ModFx.ModFXType.GRAIN;
      fs.fw2Sound.modFXDepth = 1 << 29;
      fs.fw2Sound.modFXRateIncrement = 1 << 24;
      fs.fw2Sound.granular.grainInitialized = true;
      fs.fw2Sound.granular.bufferFull = true;
      fs.fw2Sound.granular.bufferWriteIndex = 40_000;
      fs.fw2Sound.granular.wrapsToShutdown = 2;
      for (int i = 0; i < 1024; i++) {
        fs.fw2Sound.granular.grainBuffer[i][0] = 200_000_000;
        fs.fw2Sound.granular.grainBuffer[i][1] = 200_000_000;
      }
      fs.fw2Sound.granular.grains[0].length = 1024;
      fs.fw2Sound.granular.grains[0].startPoint = 256;
      fs.fw2Sound.granular.grains[0].counter = 256;
      fs.fw2Sound.granular.grains[0].pitch = 1024;
      fs.fw2Sound.granular.grains[0].volScale = ONE_Q31 / 512;
      fs.fw2Sound.granular.grains[0].volScaleMax = ONE_Q31;
      fs.fw2Sound.granular.grains[0].rev = false;
      fs.fw2Sound.granular.grains[0].panVolL = 1 << 30;
      fs.fw2Sound.granular.grains[0].panVolR = 1 << 30;

      org.deluge.modulation.patch.PatchCable revCable =
          new org.deluge.modulation.patch.PatchCable();
      revCable.from = org.deluge.modulation.patch.PatchSource.LFO_GLOBAL_1;
      revCable.amount = 1 << 30;
      revCable.polarity = org.deluge.modulation.patch.PatchCable.Polarity.UNIPOLAR;
      fs.paramManager.getPatchCableSet().addCable(Param.GLOBAL_REVERB_AMOUNT, revCable);
    }

    // Add a negative range/depth cable (LFO_GLOBAL_2 -> depth of LFO_GLOBAL_1 ->
    // GLOBAL_REVERB_AMOUNT) so the reverb cable's depth is attenuated during the tail block.
    org.deluge.modulation.patch.PatchCable rangeCable =
        new org.deluge.modulation.patch.PatchCable();
    rangeCable.from = org.deluge.modulation.patch.PatchSource.LFO_GLOBAL_2;
    rangeCable.amount = -(1 << 30);
    withRange
        .paramManager
        .getPatchCableSet()
        .addRangeCable(
            Param.GLOBAL_REVERB_AMOUNT,
            org.deluge.modulation.patch.PatchSource.LFO_GLOBAL_1,
            rangeCable);

    assertTrue(withoutRange.fw2Sound.voices.isEmpty());
    assertTrue(withRange.fw2Sound.voices.isEmpty());

    long[] outNoRange = new long[16];
    long[] revNoRange = new long[8];
    long[] outWithRange = new long[16];
    long[] revWithRange = new long[8];

    Functions.resetNoiseSeed();
    withoutRange.renderOutput(outNoRange, 8, revNoRange);
    Functions.resetNoiseSeed();
    withRange.renderOutput(outWithRange, 8, revWithRange);

    assertTrue(
        Math.abs(revWithRange[0]) < Math.abs(revNoRange[0]),
        "Global range cable must attenuate GLOBAL_REVERB_AMOUNT modulation during voice-free FX tail");
  }

  @Test
  void reverbSmoothingCompletionBlockKeepsGrainAndOrdinarySendsConsistent() {
    // C sound.cpp:2450-2453, 2646: when doParamLPF hits diff == 0 and calls stopParamLPF at the
    // end of the block, both the grain send and the ordinary dry send in that block must still use
    // the block's initial smoothed value (oldValue), not snap mid-block to targetValue.
    Sound smoothed = new Sound();
    Sound fixed = new Sound();
    Sound targetSnap = new Sound();

    for (Sound s : new Sound[] {smoothed, fixed, targetSnap}) {
      Sound.initParams(s.patchedParamValues);
      s.srrParam = Integer.MIN_VALUE;
      s.bitcrushParam = Integer.MIN_VALUE;
      s.modFXType = ModFx.ModFXType.GRAIN;
      s.modFXDepth = 1 << 29;
      s.modFXRateIncrement = 1 << 24;
      s.patchedParamValues[Param.GLOBAL_LFO_FREQ_1] = Integer.MIN_VALUE;
      s.lfoConfig[0].waveType = Lfo.LfoType.SQUARE;
      s.globalLfos[0].phase = 0; // SQUARE outputs +ONE_Q31
      s.granular.grainInitialized = true;
      s.granular.bufferFull = true;
      s.granular.bufferWriteIndex = 40_000;
      s.granular.wrapsToShutdown = 2;
      for (int i = 0; i < 1024; i++) {
        s.granular.grainBuffer[i][0] = 200_000_000;
        s.granular.grainBuffer[i][1] = 200_000_000;
      }
      s.granular.grains[0].length = 1024;
      s.granular.grains[0].startPoint = 256;
      s.granular.grains[0].counter = 256;
      s.granular.grains[0].pitch = 1024;
      s.granular.grains[0].volScale = ONE_Q31 / 512;
      s.granular.grains[0].volScaleMax = ONE_Q31;
      s.granular.grains[0].rev = false;
      s.granular.grains[0].panVolL = 1 << 30;
      s.granular.grains[0].panVolR = 1 << 30;

      // Add a small LFO_GLOBAL_1 -> GLOBAL_REVERB_AMOUNT cable whose contribution sits right below
      // a 2^15 volume-curve step, so oldValue = 0 vs targetValue = 252 (diff == 0 in >> 8) crosses
      // the step and produces measurably different reverb sends.
      Patcher.Destination dest = new Patcher.Destination(Param.GLOBAL_REVERB_AMOUNT);
      Patcher.PatchCable cable = new Patcher.PatchCable();
      cable.source = PatchSource.LFO_GLOBAL_1.ordinal();
      cable.amount = 0x0003FFF0;
      cable.polarity = Patcher.PatchCable.UNIPOLAR;
      cable.rangeValue = 536870912;
      dest.cables.add(cable);
      s.patchCableSet.destinations.add(dest);
    }

    int oldReverbKnob = 0;
    int targetReverbKnob = 252; // (252 >> 8) == (0 >> 8) -> diff == 0 in doParamLPF
    fixed.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = oldReverbKnob;
    targetSnap.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = targetReverbKnob;
    smoothed.patchedParamValues[Param.GLOBAL_REVERB_AMOUNT] = targetReverbKnob;
    smoothed.notifyValueChangeViaLPF(
        Param.GLOBAL_REVERB_AMOUNT, true, oldReverbKnob, targetReverbKnob, false);

    long[] outFixed = new long[128];
    long[] revFixed = new long[64];
    long[] outTarget = new long[128];
    long[] revTarget = new long[64];
    long[] outSmoothed = new long[128];
    long[] revSmoothed = new long[64];

    Functions.resetNoiseSeed();
    fixed.renderOutput(outFixed, 64, revFixed);
    Functions.resetNoiseSeed();
    targetSnap.renderOutput(outTarget, 64, revTarget);
    Functions.resetNoiseSeed();
    smoothed.renderOutput(outSmoothed, 64, revSmoothed);

    assertEquals(
        -1, smoothed.paramLPF.p, "doParamLPF must finish smoothing (stopParamLPF) at block end");
    assertNotEquals(
        revFixed[0],
        revTarget[0],
        "Test setup must cross a volume-curve step between oldReverbKnob and targetReverbKnob");
    for (int i = 0; i < 64; i++) {
      assertEquals(
          revFixed[i],
          revSmoothed[i],
          "Smoothing-completion block must keep grain and ordinary sends at oldReverbKnob at sample "
              + i);
    }
  }
}
