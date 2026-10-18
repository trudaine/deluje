package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies C++ firmware parity fixes across {@link Freeverb}, {@link AbsValueFollower}, {@link
 * SampleHolder}, {@link LiveInputBuffer}, and {@link Compressor}.
 */
class FreeverbFollowerSampleHolderParityTest {

  @Test
  void freeverbCombProcessUsesWrappingShiftAndAddAndExposesGetters() {
    // C comb.hpp:44,48,54-58
    Freeverb.Comb comb = new Freeverb.Comb();
    int[] buf = new int[] {1 << 30};
    comb.setBuffer(buf);
    comb.setDamp(0.5f);
    comb.setFeedback(Integer.MAX_VALUE);

    assertEquals(
        (float) comb.damp1 / (float) Integer.MAX_VALUE, comb.getDamp(), 1e-7f, "comb.getDamp()");
    assertEquals(Integer.MAX_VALUE, comb.getFeedback(), "comb.getFeedback()");

    comb.filterstore = 1 << 30;
    int input = 1 << 30;
    int out = comb.process(input);
    assertEquals(1 << 30, out);

    int expectedFilterstore =
        (Functions.multiply_32x32_rshift32_rounded(1 << 30, comb.damp2)
                + Functions.multiply_32x32_rshift32_rounded(1 << 30, comb.damp1))
            << 1;
    int expectedBuf0 =
        input
            + (Functions.multiply_32x32_rshift32_rounded(expectedFilterstore, comb.feedback) << 1);
    assertEquals(expectedFilterstore, comb.filterstore, "comb filterstore must wrap on << 1");
    assertEquals(expectedBuf0, buf[0], "comb buffer write must wrap on + and << 1");
  }

  @Test
  void freeverbProcessOneUsesWrappingCrossFeedAndAccumulationAndSetsGain() {
    // C freeverb.hpp:90-95, freeverb.cpp:92, allpass.hpp:41, base.hpp:56-57
    Freeverb rv = new Freeverb();
    assertEquals(
        (int) (Freeverb.FIXEDGAIN * (float) Integer.MAX_VALUE),
        rv.gain,
        "Freeverb.update() must set gain = fixedgain * INT32_MAX");
    assertEquals(0.5f, rv.allpassL[0].getFeedback(), 1e-6f);

    rv.setWidth(0.0f); // wet2 = INT32_MAX -> cross-feed active
    rv.setPanLevels(Integer.MAX_VALUE, Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, rv.getPanLeft());
    assertEquals(Integer.MAX_VALUE, rv.getPanRight());

    int[] outLR = new int[] {Integer.MAX_VALUE - 10, Integer.MAX_VALUE - 10};
    // Prime one comb output so outL and outR are large positive values that wrap on << 1 and +=.
    rv.combL[0].buffer[0] = 1 << 30;
    rv.combR[0].buffer[0] = 1 << 30;
    rv.processOne(0, outLR);

    // Re-derive C freeverb.hpp:78-95 on the same initial state.
    Freeverb ref = new Freeverb();
    ref.setWidth(0.0f);
    ref.setPanLevels(Integer.MAX_VALUE, Integer.MAX_VALUE);
    ref.combL[0].buffer[0] = 1 << 30;
    ref.combR[0].buffer[0] = 1 << 30;
    int outL = 0;
    int outR = 0;
    for (int i = 0; i < Freeverb.NUMCOMBS; i++) {
      outL += ref.combL[i].process(0);
      outR += ref.combR[i].process(0);
    }
    for (int i = 0; i < Freeverb.NUMALLPASSES; i++) {
      outL = ref.allpassL[i].process(outL);
      outR = ref.allpassR[i].process(outR);
    }
    outL = (outL + Functions.multiply_32x32_rshift32_rounded(outR, ref.wet2)) << 1;
    outR = (outR + Functions.multiply_32x32_rshift32_rounded(outL, ref.wet2)) << 1;
    int expectedL =
        (Integer.MAX_VALUE - 10) + Functions.multiply_32x32_rshift32_rounded(outL, ref.panLeft);
    int expectedR =
        (Integer.MAX_VALUE - 10) + Functions.multiply_32x32_rshift32_rounded(outR, ref.panRight);

    assertEquals(expectedL, outLR[0], "Freeverb L output must wrap on cross-feed and +=");
    assertEquals(expectedR, outLR[1], "Freeverb R output must wrap on cross-feed and +=");
  }

  @Test
  void absValueFollowerUsesFloatExpInputAndExposesGetters() {
    // C absolute_value.h:38-56, absolute_value.cpp:21-30
    AbsValueFollower follower = new AbsValueFollower();
    int attackKnob = 1234567890;
    int releaseKnob = 987654321;

    int attackMS = follower.setAttack(attackKnob);
    int releaseMS = follower.setRelease(releaseKnob);

    float expAttack = (float) Math.exp(2.0f * (float) attackKnob / AbsValueFollower.ONE_Q31f);
    float expectedAttackMS = (float) (0.5 + (expAttack - 1.0f) * 10.0f);
    float expRelease = (float) Math.exp(2.0f * (float) releaseKnob / AbsValueFollower.ONE_Q31f);
    float expectedReleaseMS = 50.0f + (expRelease - 1.0f) * 50.0f;

    assertEquals((int) expectedAttackMS, attackMS);
    assertEquals((int) expectedReleaseMS, releaseMS);
    assertEquals(attackKnob, follower.getAttack());
    assertEquals((int) expectedAttackMS, follower.getAttackMS());
    assertEquals(releaseKnob, follower.getRelease());
    assertEquals((int) expectedReleaseMS, follower.getReleaseMS());

    float current = 100.0f;
    float desired = 500.0f;
    float numSamples = 128.0f;
    float expectedEnv = desired + (float) Math.exp(follower.a_ * numSamples) * (current - desired);
    assertEquals(expectedEnv, follower.runEnvelope(current, desired, numSamples), 0.0f);
  }

  @Test
  void sampleHolderDefaultEndPosAndSetAudioFileAndLengthAtSystemRateMatchC() {
    // C sample_holder.cpp:29-31, 91-99, 112-149
    SampleHolder holder = new SampleHolder();
    assertEquals(0L, holder.startPos);
    assertEquals(9999999L, holder.endPos, "Fresh SampleHolder endPos must default to 9999999");
    assertEquals(SampleHolder.K_MAX_SAMPLE_VALUE, holder.neutralPhaseIncrement);

    Sample sample = new Sample();
    sample.lengthInSamples = 48000L;
    sample.sampleRate = 48000;
    holder.audioFile = sample;
    // Before setAudioFile, getEndPos(false) clamps default 9999999 to sample.lengthInSamples.
    assertEquals(48000L, holder.getEndPos(false));
    assertEquals(9999999L, holder.getEndPos(true));

    // setAudioFile(..., manuallySelected = false) clamps markers and updates neutralPhaseIncrement.
    holder.startPos = 60000L;
    holder.endPos = 9999999L;
    holder.setAudioFile(sample, false, false);
    assertEquals(0L, holder.startPos, "startPos >= endPos must reset startPos to 0");
    assertEquals(48000L, holder.endPos, "default 9999999 endPos must snap to lengthInSamples");
    int expectedNeutralPhaseInc = (int) Long.divideUnsigned(48000L << 24, 44100L);
    assertEquals(expectedNeutralPhaseInc, holder.neutralPhaseIncrement);
    int expectedLenAtSystemRate =
        (int) Long.divideUnsigned(48000L << 24, expectedNeutralPhaseInc & 0xFFFFFFFFL);
    assertEquals(expectedLenAtSystemRate, holder.getLengthInSamplesAtSystemSampleRate(false));

    // setAudioFile(..., manuallySelected = true) resets zone to [0, lengthInSamples].
    holder.startPos = 1000L;
    holder.endPos = 2000L;
    holder.setAudioFile(sample, false, true);
    assertEquals(0L, holder.startPos);
    assertEquals(48000L, holder.endPos);
  }

  @Test
  void liveInputBufferPercussivenessUsesUnsigned64Division() {
    // C live_input_buffer.cpp:93 — (((uint64_t)difference * 262144 / angle) >> 1)
    LiveInputBuffer buf = new LiveInputBuffer();
    int[] input = new int[] {Integer.MAX_VALUE, 0};
    buf.giveInput(input, 1, 0, LiveInputBuffer.InputType.INPUT_L);

    int sampleRead = Integer.MAX_VALUE >> 2;
    int angle = sampleRead;
    for (int p = 0; p < LiveInputBuffer.K_DIFFERENCE_LPF_POLES; p++) {
      angle = Functions.multiply_32x32_rshift32_rounded(angle, 1 << 23);
    }
    int diff = angle;
    int expectedPerc =
        Functions.getTanHUnknown(
            (int) (Long.divideUnsigned((long) diff * 262144L, (long) angle) >>> 1), 23);
    assertEquals((byte) expectedPerc, buf.percBuffer[0]);
  }

  @Test
  void compressorExposesAllCGettersAndDisplayHelpers() {
    // C rms_feedback.h:70-161
    Compressor comp = new Compressor();
    int attack = 10 << 24;
    int release = 20 << 24;
    int threshold = 30 << 24;
    int ratio = 40 << 24;
    int sidechain = 50 << 24;
    int blend = 64 << 24;

    int attackMS = comp.setAttack(attack);
    int releaseMS = comp.setRelease(release);
    comp.setThreshold(threshold);
    int ratioInt = comp.setRatio(ratio);
    int sidechainHz = comp.setSidechain(sidechain);
    int blendPct = comp.setBlend(blend);

    assertEquals(attack, comp.getAttack());
    assertEquals(attackMS, (int) comp.getAttackMS());
    assertEquals(release, comp.getRelease());
    assertEquals(releaseMS, (int) comp.getReleaseMS());
    assertEquals(threshold, comp.getThreshold());
    assertEquals(ratio, comp.getRatio());
    assertEquals(ratioInt, (int) comp.getRatioForDisplay());
    assertEquals(sidechain, comp.getSidechain());
    assertEquals(sidechainHz, (int) comp.getSidechainForDisplay());
    assertEquals(blend, comp.getBlend());
    assertEquals(blendPct, comp.getBlendForDisplay());
  }
}
