package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Parity tests for dynamic per-block {@link TimeStretcher} lifecycle, {@code REPEAT_STRETCH}
 * bounds, multisample {@code REPEAT_LOOP} inheritance, silent older-head advance, and native-pitch
 * amplitude scaling.
 *
 * <p>C++ references: {@code voice_sample.cpp:52, 310-336, 529-619, 1392-1399}, {@code
 * time_stretcher.cpp:39-145}, {@code voice_unison_part_source.cpp:116-215}, {@code
 * voice_sample_playback_guide.cpp:35-51, 96-101}, {@code sample_playback_guide.cpp:88-108}, and
 * {@code sample_holder.cpp:78-89}.
 */
class SampleTimeStretchLifecycleParityTest {

  private static Sample makeRampSample(int frames, int sampleRate) {
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 4;
    s.sampleRate = sampleRate;
    s.lengthInSamples = frames;
    s.audioDataStartPosBytes = 0;
    s.audioDataLengthBytes = frames * 4L;
    s.data = new int[frames];
    for (int i = 0; i < frames; i++) {
      s.data[i] = (i + 1) * 500_000;
    }
    return s;
  }

  private static Sound makeInitializedSampleSound(Sample sample) {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.srrParam = Integer.MIN_VALUE;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = sample;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    return sound;
  }

  @Test
  void rootPitchWithPitchSpeedIndependentStaysOffTimeStretcher() {
    Sample sample = makeRampSample(1024, 44100);
    sample.midiNoteFromFile = 60.0f;
    Sound sound = makeInitializedSampleSound(sample);
    sound.sampleTimestretch[0] = true; // pitchAndSpeedAreIndependent = true
    sound.sampleTimeStretchAmount[0] = 0;

    // Trigger at root pitch (MIDI 60): phaseIncrement == neutral == 16777216, so
    // timeStretchRatio == 16777216 (C voice_unison_part_source.cpp:203-207).
    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];
    assertFalse(
        vs.voiceSample.isTimeStretching(),
        "VoiceSample::noteOn must start with timeStretcher inactive (voice_sample.cpp:52)");

    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    assertEquals(16777216, vs.timeStretchRatio);
    assertFalse(
        vs.voiceSample.isTimeStretching(),
        "Root-pitch playback with timeStretchRatio == kMaxSampleValue must not start TimeStretcher");
    assertEquals(64, vs.voiceSample.reader.playPos);
  }

  @Test
  void nonRootPitchRepeatStretchWithLinkedPitchSpeedEngagesTimeStretcher() {
    Sample sample = makeRampSample(2048, 44100);
    sample.midiNoteFromFile = 60.0f;
    Sound sound = makeInitializedSampleSound(sample);
    sound.sampleLoopMode[0] = Sound.REPEAT_STRETCH;
    sound.sampleTimestretch[0] = false; // linked pitch + speed (sync by pitch-stretching)
    sound.sampleTimeStretchAmount[0] = 0;
    sound.samplesPerSequencerTick = 100.0;
    sound.pendingSampleSyncLength = 10; // noteLen = 1000 samples

    // Trigger 1 octave above root (MIDI 72 -> pre-sync pInc = 2 * neutral).
    // C voice_unison_part_source.cpp:173-176 sets syncTimeStretchRatio = neutral << 24 / pInc =
    // 8388608 so the note still lasts 1000 output samples despite the +12 st pitch shift.
    sound.triggerNote(72, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];
    assertEquals(8388608, vs.timeStretchRatio, 1024);
    assertTrue(
        vs.voiceSample.isTimeStretching(),
        "Non-root REPEAT_STRETCH with linked pitch/speed must dynamically engage TimeStretcher");
  }

  @Test
  void midNotePitchModulationEntersInPlaceAndExitsCleanly() {
    Sample sample = makeRampSample(4096, 44100);
    sample.midiNoteFromFile = 60.0f;
    Sound sound = makeInitializedSampleSound(sample);
    sound.sampleTimestretch[0] = true; // pitchAndSpeedAreIndependent = true
    sound.sampleTimeStretchAmount[0] = 0;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];
    int[] buf = new int[128];
    int[] rev = new int[64];

    // Block 1 at root pitch: native playback, TimeStretcher inactive, playPos advances to 64.
    sound.renderInternal(buf, 64, rev);
    assertFalse(vs.voiceSample.isTimeStretching());
    assertEquals(64, vs.voiceSample.reader.playPos);

    // Block 2: bend pitch up via monophonic pitch bend mid-note.
    // TimeStretcher must start in place from playPos=64 (NOT rewind to startFrame=0).
    sound.monophonicExpressionValues[0] = 1 << 28;
    sound.renderInternal(buf, 64, rev);
    assertTrue(
        vs.voiceSample.isTimeStretching(),
        "Mid-note pitch bend must start TimeStretcher in place (voice_sample.cpp:530)");
    assertTrue(
        (vs.voiceSample.timeStretcher.samplePosBig >> 24) >= 64,
        "In-place TimeStretcher init must start from current playPos, not rewind to 0");
    assertFalse(
        vs.voiceSample.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER],
        "Silent older head must clear PLAY_HEAD_OLDER after chunk 1 when crossfadeProgress == kMaxSampleValue");
    assertEquals(
        vs.voiceSample.reader.playPos,
        vs.voiceSample.olderReader.playPos,
        "Silent older head advance on chunk 1 must keep olderReader.playPos aligned with reader.playPos");

    // Block 3: return pitch bend to neutral before any hop occurs; playPos == olderReader.playPos,
    // so TimeStretcher exits cleanly (voice_sample.cpp:552-589).
    sound.monophonicExpressionValues[0] = 0;
    sound.renderInternal(buf, 64, rev);
    assertFalse(
        vs.voiceSample.isTimeStretching(),
        "TimeStretcher must exit when timeStretchRatio returns to kMaxSampleValue and heads align");
    assertTrue(
        vs.voiceSample.active, "VoiceSample must continue playing after exiting TimeStretcher");
  }

  @Test
  void repeatStretchIgnoresLoopPointsAndUsesUnclampedSyncDurationFrames() {
    Sample sample = makeRampSample(1000, 44100);
    sample.midiNoteFromFile = 60.0f;
    Sound sound = makeInitializedSampleSound(sample);
    sound.sampleLoopMode[0] = Sound.REPEAT_STRETCH;
    sound.sampleStartPoint[0] = 100;
    sound.sampleEndPoint[0] = 1500; // past lengthInSamples=1000 (trailing silence)
    sound.sampleLoopStart[0] = 200;
    sound.sampleLoopEnd[0] = 400;
    sound.samplesPerSequencerTick = 50.0;
    sound.pendingSampleSyncLength = 20;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];

    // C sample_holder.cpp:78-89 — getDurationInSamples(true) uses unclamped endPos (1500 - 100 =
    // 1400), while getEndPos(false) clamps playback endFrame to lengthInSamples (1000).
    assertEquals(1400, vs.syncDurationFrames);
    assertEquals(1000, vs.voiceSample.endFrame);
    // C voice_sample_playback_guide.cpp:35-51, 96-101 — STRETCH ignores loop points and LOOP mode.
    assertFalse(vs.voiceSample.looping);
    assertFalse(vs.voiceSample.hasLoopEnd);
    assertEquals(100, vs.voiceSample.loopStartFrame);
  }

  @Test
  void multisampleZoneInheritsSourceRepeatLoopMode() {
    Sample sample = makeRampSample(256, 44100);
    Sound sound = makeInitializedSampleSound(sample);
    sound.sampleLoopMode[0] = Sound.REPEAT_LOOP;

    Sound.CompiledKeyZone kz = new Sound.CompiledKeyZone();
    kz.minPitch = 0;
    kz.maxPitch = 127;
    kz.minVelocity = 0;
    kz.maxVelocity = 127;
    kz.sample = sample;
    kz.transpose = 0;
    kz.startSamplePos = 0;
    kz.endSamplePos = -1;
    kz.looping = false; // XML <zone> has no loop attribute; source repeatMode governs
    sound.sourceZones[0].add(kz);

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];
    assertTrue(
        vs.voiceSample.looping,
        "Multisample zone must loop when source repeatMode == REPEAT_LOOP (voice_sample_playback_guide.cpp:100)");
  }

  @Test
  void renderTimeStretchedNativePitchExitAmplitudeShiftsRightByFour() {
    Sample sample = makeRampSample(1024, 44100);
    VoiceSample vs = new VoiceSample();
    vs.setupTimeStretch(sample, 0, 1024, 1, false, 0, false, 1024);

    int startAmp = 1 << 24;
    int ampInc = 1 << 16;
    int[] amp = {startAmp};
    int[] out = new int[32];
    vs.renderTimeStretched(out, 32, 1, 16777216, 12_000_000, amp, ampInc);

    // C voice_sample.cpp:602-619 — native_ shifts amplitude left by 4 (3 + 1), so exit write-back
    // must shift right by 4, returning startAmp + ampInc * 32.
    assertEquals(startAmp + ampInc * 32, amp[0]);
  }

  @Test
  void lateStartInRepeatStretchAndTimeStretchModesStartsAtExpectedSampleOffset() {
    Sample sample = makeRampSample(1000, 44100);
    sample.midiNoteFromFile = 60.0f;
    Sound sound = makeInitializedSampleSound(sample);
    sound.sampleLoopMode[0] = Sound.REPEAT_STRETCH;
    sound.sampleTimestretch[0] = true;
    sound.samplesPerSequencerTick = 100.0;
    sound.pendingSampleSyncLength = 20; // noteLen = 2000 output samples; syncDurationFrames = 1000

    // Trigger 500 output samples late (25% into the 2000-sample note -> frame 250 of 1000).
    sound.triggerNoteLate(60, 100, 500);
    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];
    assertEquals(500, vs.voiceSample.pendingSamplesLate);

    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    assertEquals(0, vs.voiceSample.pendingSamplesLate);
    assertTrue(vs.voiceSample.isTimeStretching());
    // Started at frame 250 and advanced 64 output samples at 0.5x stretch (~32 source frames).
    long currentFrame = vs.voiceSample.timeStretcher.samplePosBig >> 24;
    assertEquals(282L, currentFrame, 2L);
  }

  @Test
  void sampleReaderNativeToResampledTransitionPrimesFromCurrentPlayPos() {
    Sample sample = makeRampSample(512, 44100);
    SampleReader rd = new SampleReader();
    rd.sample = sample;
    rd.playDirection = 1;
    rd.setBounds(512, false, 0);
    rd.init(0);

    // Advance 100 frames natively so playPos = 100 while bufL still holds frames around 0.
    int[] nativeOut = new int[100];
    rd.readNative(nativeOut, 100, 1, new int[] {1 << 26}, 0);
    assertEquals(100, rd.playPos);

    // Now switch to resampled playback at 0.5x pitch and compare against a fresh reader initialized
    // directly at frame 100.
    int[] transitionedOut = new int[32];
    rd.readResampled(transitionedOut, 32, 1, 0, 1 << 23, 16, new int[] {1 << 26}, 0);

    SampleReader freshAt100 = new SampleReader();
    freshAt100.sample = sample;
    freshAt100.playDirection = 1;
    freshAt100.setBounds(512, false, 0);
    freshAt100.init(100);
    int[] freshOut = new int[32];
    freshAt100.readResampled(freshOut, 32, 1, 0, 1 << 23, 16, new int[] {1 << 26}, 0);

    assertTrue(
        Arrays.equals(freshOut, transitionedOut),
        "Native -> resampled transition must prime interpolation history from current playPos=100");
  }
}
