package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * C++ parity tests for {@link Lfo}, {@link Envelope}, and {@link VoiceSample} time-stretch head
 * lifecycle (`lfo.h:111-121`, `envelope.h:37`, `envelope.cpp:82-101`, `time_stretcher.cpp:83-165`,
 * `voice_sample.cpp:1167-1205, 1434-1437`).
 */
class LfoEnvelopeVoiceSampleParityTest {

  private static Sample makeConstantSample(int frames, int value) {
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 4;
    s.sampleRate = 44100;
    s.lengthInSamples = frames;
    s.audioDataStartPosBytes = 0;
    s.audioDataLengthBytes = frames * 4;
    s.data = new int[frames];
    Arrays.fill(s.data, value);
    return s;
  }

  @Test
  void lfoWarblerUsesUnsignedShiftOnPhaseIncrement() {
    Lfo lfo = new Lfo();
    lfo.phase = 0x10000000;
    lfo.target = 0x20000000;
    lfo.holdValue = 0;
    lfo.speed = 0;

    // Lfo.render(WARBLER) doubles phaseIncrement to 0x80000000 before calling warble.
    // In C++ (lfo.h:111, 119), phaseIncrement is uint32_t, so phaseIncrement >> 8 is 0x00800000
    // (positive), moving holdValue toward target instead of away from it.
    int out = lfo.render(1, Lfo.LfoType.WARBLER, 0x40000000);

    int expectedSpeed = Functions.multiply_32x32_rshift32(0x20000000, 0x80000000 >>> 8);
    assertTrue(expectedSpeed > 0, "Expected positive speed toward positive target");
    assertEquals(expectedSpeed, lfo.speed);
    assertEquals(expectedSpeed, lfo.holdValue);
    assertEquals(expectedSpeed, out);
  }

  @Test
  void envelopeDefaultFastReleaseIncrementMatchesCpp() {
    Envelope env = new Envelope();
    assertEquals(
        1024,
        env.fastReleaseIncrement,
        "C envelope.h:37 initializes uint32_t fastReleaseIncrement{1024}");
  }

  @Test
  void envelopeReleaseAndFastReleaseUseUnsignedPosAndIncrementComparisons() {
    Envelope env = new Envelope();
    env.setState(Envelope.Stage.RELEASE);
    env.pos = 0;
    env.lastValue = 1 << 30;
    env.lastValuePreCurrentStage = 1 << 30;

    // C envelope.cpp:81-85 — pos and release are uint32_t; 0x80000000 >= 8388608 is true.
    int out = env.render(1, 0, 0, 0, 0x80000000, LookupTables.decayTableSmall8);
    assertEquals(Envelope.Stage.OFF, env.state);
    assertEquals(-2147483648, out);

    // C envelope.cpp:94-101 — fastReleaseIncrement (0x90000000) is unsigned > 2 * release (131072),
    // so fastReleaseIncrement must NOT be overwritten by 131072, and pos wraps past 8388608 to OFF.
    env.setState(Envelope.Stage.FAST_RELEASE);
    env.pos = 0;
    env.fastReleaseIncrement = 0x90000000;
    int outFast = env.render(1, 0, 0, 0, 65536, LookupTables.decayTableSmall8);
    assertEquals(0x90000000, env.fastReleaseIncrement);
    assertEquals(Envelope.Stage.OFF, env.state);
    assertEquals(-2147483648, outFast);

    // Conversely, when 2 * release (0xA0000000) is unsigned > fastReleaseIncrement (65536),
    // fastReleaseIncrement is raised to 0xA0000000 and finishes immediately.
    env.setState(Envelope.Stage.FAST_RELEASE);
    env.pos = 0;
    env.fastReleaseIncrement = 65536;
    int outFast2 = env.render(1, 0, 0, 0, 0x50000000, LookupTables.decayTableSmall8);
    assertEquals(0xA0000000, env.fastReleaseIncrement);
    assertEquals(Envelope.Stage.OFF, env.state);
    assertEquals(-2147483648, outFast2);
  }

  @Test
  void voiceSampleSetupTimeStretchCopiesReaderIntoOlderReader() {
    Sample stale = makeConstantSample(64, 1 << 20);
    Sample fresh = makeConstantSample(256, 1 << 28);
    VoiceSample vs = new VoiceSample();
    vs.olderReader.sample = stale;
    vs.olderReader.playPos = 55;
    vs.olderReader.interpolationBufferSizeLastTime = 16;

    vs.setupTimeStretch(fresh, 12, 256, 1, false, 0, false, 256);

    assertSame(fresh, vs.olderReader.sample);
    assertEquals(12, vs.olderReader.playPos);
    assertEquals(0, vs.olderReader.interpolationBufferSizeLastTime);
  }

  @Test
  void voiceSampleOneShotOlderHeadFinishesFadeoutAfterNewerHeadDies() {
    Sample sample = makeConstantSample(512, 1 << 28);
    VoiceSample vs = new VoiceSample();
    vs.setupTimeStretch(sample, 0, 512, 1, false, 0, false, 512);

    // Simulate the state right after hopEndVoid killed PLAY_HEAD_NEWER at the one-shot waveform
    // end while PLAY_HEAD_OLDER still needs 64 output samples to fade out, and samplesTilHopEnd
    // has already hit 0.
    vs.olderReader.copyStateFrom(vs.reader);
    vs.olderReader.init(64);
    vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = true;
    vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = false;
    vs.timeStretcher.crossfadeProgress = 0;
    vs.timeStretcher.crossfadeIncrement = (1 << 24) / 64;
    vs.timeStretcher.samplesTilHopEnd = 0;
    vs.timeStretcher.samplePosBig = 100L << 24;

    int[] amp = {1 << 26};
    int[] out1 = new int[32];
    vs.renderTimeStretched(out1, 32, 1, 1 << 24, 1 << 24, amp, 0);

    long energy1 = 0;
    for (int v : out1) {
      energy1 += Math.abs((long) v);
    }
    assertTrue(energy1 > 0, "Older head must keep rendering its crossfade when newer head is dead");
    assertTrue(
        vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER],
        "Older head must still be active halfway through the 64-sample crossfade");
    assertFalse(
        vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER],
        "Newer head must remain inactive instead of triggering another hop");
    assertTrue(vs.active, "VoiceSample must stay active until older head fadeout completes");

    int[] out2 = new int[32];
    vs.renderTimeStretched(out2, 32, 1, 1 << 24, 1 << 24, amp, 0);

    long energy2 = 0;
    for (int v : out2) {
      energy2 += Math.abs((long) v);
    }
    assertTrue(energy2 > 0, "Second half of older head fadeout must also render audio");
    assertFalse(
        vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER],
        "Older head must deactivate once crossfadeProgress reaches kMaxSampleValue");
    assertFalse(
        vs.active,
        "One-shot VoiceSample must deactivate immediately when both stretch heads are dead");
  }

  @Test
  void voiceSampleLoopReInitHopsImmediatelyWhenNewerHeadIsInactive() {
    Sample sample = makeConstantSample(256, 1 << 28);
    VoiceSample vs = new VoiceSample();
    vs.setupTimeStretch(sample, 0, 200, 1, true, 50, false, 200);

    // Place samplePosBig less than one combinedIncrement before loopEnd (200), with PLAY_HEAD_NEWER
    // inactive and samplesTilHopEnd still positive (500). C TimeStretcher::reInit
    // (time_stretcher.cpp:163-165) calls hopEnd directly so PLAY_HEAD_NEWER is reactivated.
    vs.timeStretcher.samplePosBig = (200L << 24) - (1L << 20);
    vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
    vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = false;
    vs.timeStretcher.samplesTilHopEnd = 500;

    int[] amp = {1 << 26};
    int[] out = new int[32];
    vs.renderTimeStretched(out, 32, 1, 1 << 24, 1 << 24, amp, 0);

    long energy = 0;
    for (int v : out) {
      energy += Math.abs((long) v);
    }
    assertTrue(
        vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER],
        "Loop wrap reInit must hop and reactivate PLAY_HEAD_NEWER when it was inactive");
    assertTrue(energy > 0, "Loop wrap reInit must resume audible output");
    assertTrue(vs.active, "Looping VoiceSample must remain active");
  }
}
