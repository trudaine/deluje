package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.deluge.engine.FirmwareSound;
import org.deluge.modulation.params.ParamManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for all 20 findings from the firmware2 Slice 1 C++ faithfulness audit
 * (Oscillator, Lfo, Envelope, Voice, VoiceSource, VoiceSample, SampleReader, Sound, Arpeggiator,
 * Stutterer).
 */
class Fw2Slice1FindingsTest {

  @AfterEach
  void resetGlobals() {
    LiveInput.currentBlock = null;
    LiveInput.lineInPluggedIn = false;
    LiveInput.micPluggedIn = false;
  }

  private static Sample makeRampSample(int frames, int sampleRate) {
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 4;
    s.sampleRate = sampleRate;
    s.lengthInSamples = frames;
    s.audioDataLengthBytes = frames * 4L;
    s.data = new int[frames];
    for (int i = 0; i < frames; i++) {
      s.data[i] = (i + 1) * 1_000_000;
    }
    return s;
  }

  private static Sound makeInitializedSound() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.srrParam = Integer.MIN_VALUE;
    return sound;
  }

  @Test
  void finding1_firmwareSoundDelegatesPostVolumeDynamicsToFw2Compressor() {
    FirmwareSound fs = new FirmwareSound();
    fs.paramNeutralValues[Param.UNPATCHED_COMPRESSOR_THRESHOLD] = 1 << 28;
    fs.syncParamsToFw2();

    assertFalse(fs.fw2Sound.compressor.onLastTime);
    fs.triggerNote(60, 100);
    int[] buf = new int[256];
    int[] rev = new int[128];
    fs.renderOutput(buf, 128, rev);
    assertTrue(
        fs.fw2Sound.compressor.onLastTime,
        "FirmwareSound.render must delegate processPostVolumeDynamics to fw2Sound.compressor");
  }

  @Test
  void finding2_stutterProcessesOnlyRequestedNumSamples() {
    Stutterer stutterer = new Stutterer();
    ParamManager pm = new ParamManager();
    Stutterer.Config cfg = new Stutterer.Config();
    cfg.quantized = false;
    Object owner = new Object();
    stutterer.beginStutter(owner, pm, cfg, 1 << 20);
    assertTrue(stutterer.isStuttering(owner));

    StereoSample[] buf = new StereoSample[256];
    for (int i = 0; i < 256; i++) {
      buf[i] = new StereoSample(1000 + i, 2000 + i);
    }
    // Drain recording into PLAYING state using 64-sample blocks, leaving [64..255] untouched.
    for (int b = 0; b < 400; b++) {
      stutterer.processStutter(buf, 64, 1 << 20);
    }
    for (int i = 64; i < 256; i++) {
      buf[i].l = 777_777;
      buf[i].r = 888_888;
    }
    stutterer.processStutter(buf, 64, 1 << 20);
    assertEquals(777_777, buf[64].l, "Samples past numSamples must not be touched by stutter");
    assertEquals(888_888, buf[255].r, "Tail of oversized scratch buffer must stay untouched");
    stutterer.endStutter(pm);
  }

  @Test
  void finding3_sampleLoopEndPointObeyedWhileHeldAndReleasedOnNoteOff() {
    Sample sample = makeRampSample(64, 44100);
    VoiceSample vs = new VoiceSample();
    // Loop between 10 and 20 while held; after noteOff, play out to 50 and stop.
    vs.setup(sample, 0, 50, 1, true, 10, true, 20);
    assertTrue(vs.shouldObeyLoopEndPointNow());
    assertEquals(20, vs.currentEndOrLoopFrame());
    assertTrue(vs.isLoopingNow());

    int[] out = new int[32];
    int[] amp = {1 << 26};
    vs.render(out, 32, 1, 16777216, amp, 0);
    assertTrue(vs.active, "VoiceSample must stay active while looping between 10 and 20");
    assertTrue(
        vs.reader.playPos >= 10 && vs.reader.playPos < 20,
        "Play pos must wrap inside [10, 20) while held, got " + vs.reader.playPos);

    vs.onNoteOff();
    assertFalse(vs.shouldObeyLoopEndPointNow());
    assertEquals(50, vs.currentEndOrLoopFrame());
    assertFalse(vs.isLoopingNow(), "Loop-end sustain loop must release into one-shot tail");

    int[] tail = new int[64];
    vs.render(tail, 64, 1, 16777216, amp, 0);
    assertFalse(vs.active, "VoiceSample must stop at endFrame=50 after noteOff exits loopEnd");
  }

  @Test
  void finding4_5_timeStretchRatioCompensatesPitchAndReassessesLoopEnd() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.sampleTimestretch[0] = true;
    sound.sampleLoopMode[0] = Sound.REPEAT_LOOP;
    sound.sampleLoopStart[0] = 10;
    sound.sampleLoopEnd[0] = 30;
    Sample sample = makeRampSample(128, 44100);
    sample.midiNoteFromFile = 60.0f;
    sound.samples[0] = sample;

    // Trigger 1 octave above root (MIDI 72 -> pInc = 2x neutral)
    sound.triggerNote(72, 100);
    Voice v = sound.voices.get(0);
    int[] buf = new int[128];
    sound.renderInternal(buf, 64, new int[128]);

    VoiceSource vs0 = v.unisonParts[0].sources[0];
    // At +12 semitones, pInc = 2 * 16777216, so timeStretchRatio = 16777216 / 2 = 8388608.
    assertEquals(
        8388608,
        vs0.timeStretchRatio,
        1024,
        "timeStretchRatio must counteract 2x pitch increment so duration stays constant");
    // And samplePosBig must wrap inside [loopStart=10, loopEnd=30) after advancing 64 frames.
    long sampleFrameNow = vs0.voiceSample.timeStretcher.samplePosBig >> 24;
    assertTrue(
        sampleFrameNow >= 10 && sampleFrameNow < 30,
        "Time-stretched samplePosBig must wrap at loopEnd=30 back to loopStart=10, got "
            + sampleFrameNow);
  }

  @Test
  void finding6_reverseSampleSetupUsesEndMinusOneStartAndSwapsLoopPoints() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.sampleReverse[0] = true;
    sound.sampleStartPoint[0] = 10;
    sound.sampleEndPoint[0] = 100;
    sound.sampleLoopMode[0] = Sound.REPEAT_LOOP;
    sound.sampleLoopStart[0] = 20;
    sound.sampleLoopEnd[0] = 80;
    sound.samples[0] = makeRampSample(128, 44100);

    sound.triggerNote(60, 100);
    VoiceSample vs = sound.voices.get(0).unisonParts[0].sources[0].voiceSample;
    assertEquals(-1, vs.reader.playDirection);
    assertEquals(99, vs.startFrame, "Reversed startFrame must be endFrame - 1");
    assertEquals(9, vs.endFrame, "Reversed endFrame must be startFrame - 1");
    assertEquals(79, vs.loopStartFrame, "Reversed loopStart must come from loopEnd - 1");
    assertTrue(vs.hasLoopEnd);
    assertEquals(19, vs.loopEndFrame, "Reversed loopEnd must come from loopStart - 1");
  }

  @Test
  void finding7_overNyquistOscADeactivatesOnlySource0NotWholeVoice() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SINE;
    sound.oscTypes[1] = Oscillator.OscType.SINE;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    // Push osc 0 above 22.05 kHz (note 60 + 84 = 144 -> shiftRightAmount < 0 -> carrierIncA = 0)
    sound.sourceTranspose[0] = 84;
    sound.sourceTranspose[1] = 0;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    int[] buf = new int[128];
    boolean stillActive = v.render(buf, 64, false, false);

    assertTrue(stillActive, "Voice must remain active when only osc A exceeds Nyquist");
    assertFalse(v.unisonParts[0].sources[0].active, "Osc A must be marked inactive");
    assertTrue(v.unisonParts[0].sources[1].active, "Osc B must stay active");
    long energy = 0;
    for (int sample : buf) {
      energy += Math.abs((long) sample);
    }
    assertTrue(energy > 0, "Osc B must still produce audible output");
  }

  @Test
  void finding8_sampleSourceVolumeZeroIsSilentWithoutNonZeroFloor() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(256, 44100);
    // Keep the knob enabled so Sound.isSourceActiveCurrently(0) stays true, then zero the final
    // patched amplitude so renderSubtractivePath reaches the sample-amplitude calculation.
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE + 1;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    Patcher.performInitialPatching(sound, v.sourceValues, v.paramFinalValues);
    v.paramFinalValues[Param.LOCAL_OSC_A_VOLUME] = 0;
    int[] buf = new int[128];
    v.render(buf, 64, false, false);
    assertTrue(
        v.unisonParts[0].sources[0].voiceSample.reader.playPos > 0,
        "Sample reader must advance while source is active");
    for (int sample : buf) {
      assertEquals(
          0, sample, "Zero-amplitude sample oscillator must render silence (no 1<<26 floor)");
    }
  }

  @Test
  void finding9_liveInputAppliesPitchModulationInsteadOfResettingToStoredIncrement() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.INPUT_L;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_A_PITCH_ADJUST] = 0x10000000;

    int[] liveIn = new int[128];
    for (int i = 0; i < 64; i++) {
      liveIn[i * 2] = 100_000_000;
    }
    LiveInput.currentBlock = liveIn;
    LiveInput.lineInPluggedIn = true;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    Patcher.performInitialPatching(sound, v.sourceValues, v.paramFinalValues);
    int[] buf = new int[128];
    v.render(buf, 64, false, false);
    assertTrue(
        v.unisonParts[0].sources[0].livePitchShifter != null,
        "Pitch-modulated live input must instantiate LivePitchShifter (pInc != neutral)");
  }

  @Test
  void finding10_11_13_sampleReaderO1WrapRetrospectiveInitAndBufferResize() {
    Sample sample = makeRampSample(32, 44100);
    SampleReader reader = new SampleReader();
    reader.sample = sample;
    reader.playDirection = 1;

    // Retrospective init at startPos=2 with looping bounds [10, 20): must NOT wrap to loop end.
    reader.setBounds(20, true, 10);
    reader.init(2);
    assertEquals(2, reader.playPos);
    assertEquals(0, reader.interpolationBufferSizeLastTime);
    // Older pre-start history beyond frame 0 must be zero-padded, not wrapped from frame 19.
    assertEquals(0, reader.bufL[5], "Retrospective init must zero-pad past frame 0");

    // O(1) modular wrap when jumping far past loop end.
    reader.playPos = 10_000_005;
    reader.setBounds(20, true, 10);
    assertEquals(15, reader.playPos, "O(1) wrap must fold playPos into [10, 20)");

    // Resize 16 -> 2 -> 16 across resampled reads.
    int[] out = new int[8];
    int[] amp = {1 << 26};
    reader.readResampled(out, 4, 1, 0, 16777216, 16, amp, 0);
    assertEquals(16, reader.interpolationBufferSizeLastTime);
    reader.readResampled(out, 4, 1, 0, 16777216, 2, amp, 0);
    assertEquals(2, reader.interpolationBufferSizeLastTime);
    reader.readResampled(out, 4, 1, 0, 16777216, 16, amp, 0);
    assertEquals(16, reader.interpolationBufferSizeLastTime);
  }

  @Test
  void finding12_oscillatorRenderWaveIntTableInterpolatesBetweenTableEntries() {
    int[] table = new int[20];
    for (int i = 0; i < table.length; i++) {
      table[i] = i * 1000;
    }
    // tableSizeMagnitude = 4 (16 entries). Halfway between index 0 and index 1 -> phase = 1 << 27.
    int[] out = new int[1];
    Oscillator.renderWave(table, 4, (1 << 30), out, 0, 1, 0, 1 << 27, false, 0, 0);
    assertEquals(32_768_000, out[0], "Half-step phase must interpolate to exact midpoint");

    // Odd 16-bit fraction (1 << 12): C drops the low bit via strength2 = uint16 >> 1 -> 0,
    // whereas the old formula kept the odd bit and produced 1000.
    out[0] = 0;
    Oscillator.renderWave(table, 4, (1 << 30), out, 0, 1, 0, 1 << 12, false, 0, 0);
    assertEquals(0, out[0], "Odd low fraction bit must be dropped per C vector_rendering_function");
  }

  @Test
  void finding14_arpReturnInstructionResetClearsStaleNotesAndReleasesGlideNotes() {
    Arpeggiator.ArpReturnInstruction instr = new Arpeggiator.ArpReturnInstruction();
    instr.arpNoteOn = new Arpeggiator.ArpNote();
    instr.noteCodeOffPostArp[0] = 60;
    instr.glideNoteCodeOffPostArp[0] = 62;
    instr.reset();
    assertNull(instr.arpNoteOn);
    assertEquals(Arpeggiator.ARP_NOTE_NONE, instr.noteCodeOffPostArp[0]);
    assertEquals(Arpeggiator.ARP_NOTE_NONE, instr.glideNoteCodeOffPostArp[0]);
  }

  @Test
  void finding15_globalLfosReceiveCustomWaveBeforeRender() {
    Sound sound = makeInitializedSound();
    Arrays.fill(sound.customLfoWave, 0x40000000);
    sound.lfoConfig[0].waveType = Lfo.LfoType.CUSTOM;
    sound.lfoConfig[2].waveType = Lfo.LfoType.CUSTOM;
    sound.renderInternal(new int[128], 64, new int[128]);
    assertSame(sound.customLfoWave, sound.globalLfos[0].customWave);
    assertSame(sound.customLfoWave, sound.globalLfos[1].customWave);
    assertNotEquals(0, sound.globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()]);
  }

  @Test
  void finding16_17_voiceSourcePropagatesLinearInterpolationAndClearSampleAcrossUnison() {
    Sound sound = makeInitializedSound();
    sound.numUnison = 4;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.sourceLinearInterpolation[0] = true;
    sound.samples[0] = makeRampSample(64, 44100);

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    for (int u = 0; u < 4; u++) {
      assertTrue(
          v.unisonParts[u].sources[0].voiceSample.linearInterpolation,
          "linearInterpolation must propagate to unison part " + u);
      assertNotEquals(null, v.unisonParts[u].sources[0].sampleRef);
    }

    // Clear sample on source 0 and trigger again on a mono voice reuse.
    sound.polyphonic = Sound.PolyphonyMode.MONO;
    sound.samples[0] = null;
    sound.triggerNote(62, 100);
    for (int u = 0; u < 4; u++) {
      assertNull(
          v.unisonParts[u].sources[0].sampleRef,
          "clearSample must null sampleRef on unison part " + u);
      assertFalse(v.unisonParts[u].sources[0].voiceSample.active);
    }
  }

  @Test
  void finding18_legatoModeChangesNoteCodeWithoutRetriggeringEnvelopeOrSample() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.patchedParamValues[Param.LOCAL_ENV_0_ATTACK] = 0x20000000; // slow attack

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    int[] buf = new int[128];
    sound.renderInternal(buf, 64, new int[128]);
    int posBefore = v.envelopes[0].pos;
    int oscPosBefore = v.unisonParts[0].sources[0].oscPos;
    assertTrue(posBefore > 0);

    // Legato second note while first is still in ATTACK/DECAY/SUSTAIN.
    sound.triggerNote(67, 100);
    assertEquals(1, sound.voices.size());
    assertSame(v, sound.voices.get(0));
    assertEquals(67, v.noteCode, "Legato must update noteCode on the existing voice");
    assertEquals(posBefore, v.envelopes[0].pos, "Legato must not reset envelope pos");
    assertEquals(oscPosBefore, v.unisonParts[0].sources[0].oscPos, "Legato must not reset oscPos");
  }

  @Test
  void finding19_releaseVoiceMatchesNoteWithoutChannelFilterAndSkipsReleasingVoices() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.POLY;

    sound.triggerNote(60, 100, 2); // MPE member channel 2
    sound.triggerNote(60, 100, 3); // MPE member channel 3
    assertEquals(2, sound.voices.size());

    // C sound.cpp:1874-1876 — noteOffPostArpeggiator matches noteCode without channel filtering
    // and only acts on voices with envelopes[0].state < RELEASE.
    Voice vCh2 = sound.voices.get(0);
    Voice vCh3 = sound.voices.get(1);
    vCh2.envelopes[0].state = Envelope.Stage.RELEASE;
    vCh2.envelopes[0].pos = 12345;

    sound.releaseNote(60, 2);
    assertEquals(
        12345, vCh2.envelopes[0].pos, "Already-releasing voice must be skipped on noteOff");
    assertTrue(
        vCh3.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) >= 0,
        "Non-releasing voice on same note must release regardless of MIDI channel");
  }

  @Test
  void finding20_voiceSampleSetupClearsStaleTimeStretcherPlayHeads() {
    Sample sample = makeRampSample(256, 44100);
    VoiceSample vs = new VoiceSample();
    vs.setupTimeStretch(sample, 0, 256, 1, false, 0, false, 256);
    assertTrue(vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]);

    // Reuse VoiceSample for normal (non-time-stretched) playback.
    vs.setup(sample, 0, 256, 1, false, 0, false, 256);
    assertFalse(
        vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER],
        "VoiceSample.setup must clear timeStretcher NEWER playhead");
    assertFalse(
        vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER],
        "VoiceSample.setup must clear timeStretcher OLDER playhead");
  }

  @Test
  void finding21_sampleReaderModeSwitchAfterLoopWrapRewindsInsideLoopNotAttack() {
    Sample sample = makeRampSample(128, 44100);
    SampleReader reader = new SampleReader();
    reader.sample = sample;
    reader.playDirection = 1;
    reader.setBounds(100, true, 40);
    reader.init(90);

    int[] out = new int[4];
    int[] amp = {1 << 26};
    // Prime 16-tap (9 pushes: 90..98 -> playPos=99) + 3 advances (99, 40 -> playPos=41, oscPos=0).
    reader.readResampled(out, 3, 1, 0, 16777216, 16, amp, 0);
    assertEquals(41, reader.playPos);
    assertEquals(0, reader.oscPos);

    // Switch 16-tap -> native: must rewind to 93 (inside [40, 100)), not 33 (pre-loop attack).
    SampleReader toNative = new SampleReader();
    toNative.copyStateFrom(reader);
    toNative.readNative(out, 1, 1, amp, 0);
    assertEquals(94, toNative.playPos, "Native read must start at 93 and advance to 94");

    // Shrink 16-tap -> 2-tap: must rewind to 94, not 34.
    SampleReader toTwoTap = new SampleReader();
    toTwoTap.copyStateFrom(reader);
    toTwoTap.readResampled(out, 1, 1, 0, 8388608, 2, amp, 0);
    assertEquals(94, toTwoTap.posHistory[0] + 1, "2-tap shrink must realign cursor to 94");

    // Pre-loop initial attack (no wrap yet): playPos=41 must rewind to 33, NOT wrap into [40, 100).
    SampleReader preLoop = new SampleReader();
    preLoop.sample = sample;
    preLoop.playDirection = 1;
    preLoop.setBounds(100, true, 40);
    preLoop.init(30);
    preLoop.readResampled(out, 3, 1, 0, 16777216, 16, amp, 0);
    assertEquals(41, preLoop.playPos);
    preLoop.readNative(out, 1, 1, amp, 0);
    assertEquals(34, preLoop.playPos, "Pre-loop attack must rewind to 33 and advance to 34");
  }

  @Test
  void finding22_legatoRetunePreservesLatchedNotePatchSourceAndRestoresPitchDisabledSource() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.oscTypes[1] = Oscillator.OscType.SAW;
    sound.sourceTranspose[0] = 84;
    sound.sourceTranspose[1] = 0;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    int latchedNoteSource = v.sourceValues[PatchSource.NOTE.ordinal()];
    assertEquals((60 - 64) * 33554432, latchedNoteSource);

    sound.renderInternal(new int[128], 64, new int[128]);
    assertFalse(
        v.unisonParts[0].sources[0].active,
        "Osc A must be disabled as over-Nyquist on note 60 (+84 transpose)");
    assertTrue(v.unisonParts[0].sources[0].disabledByPitch);
    assertTrue(v.unisonParts[0].sources[1].active);

    // Legato retune down to note 24: NOTE patch source stays latched from noteOn, while Osc A
    // (disabled solely by pitch) is re-enabled and renders at the valid lower pitch.
    sound.triggerNote(24, 100);
    assertEquals(
        latchedNoteSource,
        v.sourceValues[PatchSource.NOTE.ordinal()],
        "Legato changeNoteCode must leave NOTE patch source latched from noteOn");
    assertTrue(
        v.unisonParts[0].sources[0].active,
        "Legato changeNoteCode must restore a pitch-disabled source when retuning below Nyquist");
    sound.renderInternal(new int[128], 64, new int[128]);
    assertTrue(
        v.unisonParts[0].sources[0].active,
        "Osc A must remain active after rendering at valid note 24 (+84 transpose)");
  }

  @Test
  void finding23_reverseSampleLoopMarkerCheckedAfterDecrement() {
    // C voice_sample_playback_guide.cpp:38-68: reverse conversion decrements nonzero raw markers
    // BEFORE checking marker presence (!= 0). Raw position 1 becomes 0 (no directional marker).
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(64, 44100);
    sound.sampleReverse[0] = true;
    sound.sampleStartPoint[0] = 0;
    sound.sampleEndPoint[0] = 64;
    sound.sampleLoopStart[0] = 1; // In reverse, rawLoopStart=1 -> loopEndPlaybackAtSample = 0
    sound.sampleLoopEnd[0] = 20;
    sound.sampleLoopMode[0] = Sound.REPEAT_LOOP;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    VoiceSample vs = v.unisonParts[0].sources[0].voiceSample;
    assertFalse(
        vs.hasLoopEnd, "Raw loop marker at position 1 converts to 0 in reverse -> no loop-end");
    assertEquals(
        -1, vs.loopEndFrame, "With no directional loop-end marker, reverse playback runs to -1");
    assertEquals(19, vs.loopStartFrame, "Reverse conversion of position 20 must land at frame 19");

    // Raw position 2 converts to 1 in reverse -> valid directional loop-end marker at frame 1.
    sound.releaseAllNotes();
    sound.voices.clear();
    sound.sampleLoopStart[0] = 2;
    sound.triggerNote(60, 100);
    VoiceSample vs2 = sound.voices.get(0).unisonParts[0].sources[0].voiceSample;
    assertTrue(vs2.hasLoopEnd);
    assertEquals(1, vs2.loopEndFrame);
  }

  @Test
  void finding24_sustainLoopEndLoopsForNonRepeatingSourceUntilNoteOff() {
    Sample sample = makeRampSample(64, 44100);
    VoiceSample vs = new VoiceSample();
    // looping = false (SampleRepeatMode::ONCE / CUT), but hasLoopEnd = true (sustain loop marker).
    vs.setup(sample, 0, 64, 1, false, 10, true, 20);
    assertTrue(vs.shouldObeyLoopEndPointNow(), "Sustain loop-end must be obeyed while held");
    assertTrue(vs.isLoopingNow(), "Sustain loop-end must loop while held even when looping=false");
    assertEquals(20, vs.currentEndOrLoopFrame());

    vs.onNoteOff();
    assertFalse(vs.shouldObeyLoopEndPointNow(), "Sustain loop-end must release on noteOff");
    assertFalse(vs.isLoopingNow(), "Non-repeating source must stop looping on noteOff");
    assertEquals(64, vs.currentEndOrLoopFrame());
  }

  @Test
  void round3_firmwareSoundPreservesExplicitLoopEndAtFrame65535() {
    FirmwareSound fs = new FirmwareSound();
    fs.oscTypes[0] = Oscillator.OscType.SAMPLE;
    org.deluge.playback.Sample pbSample = new org.deluge.playback.Sample();
    pbSample.data = new float[70000];
    fs.samples[0] = pbSample;

    // Default SampleVoiceSettings.loopEnd = 0 means no explicit loop-end marker.
    fs.syncParamsToFw2();
    assertEquals(0, fs.fw2Sound.sampleLoopEnd[0], "Absent loopEnd marker must sync as 0");

    // Explicit frame 65535 must be preserved instead of being discarded as a sentinel.
    fs.sampleSettings[0].loopEnd = 65535;
    fs.syncParamsToFw2();
    assertEquals(65535, fs.fw2Sound.sampleLoopEnd[0], "Explicit loopEnd=65535 must be preserved");
  }

  @Test
  void round3_legatoOscTypeChangeStopsRenderingOldSampleRef() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(256, 44100);
    sound.sampleLoopMode[0] = Sound.REPEAT_LOOP;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    sound.triggerVoice(60, 100, 0);
    Voice v = sound.voices.get(0);
    assertTrue(v.unisonParts[0].sources[0].sampleRef != null);

    // Switch oscillator A from SAMPLE to SAW while the legato voice is held, then legato-retune.
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.triggerVoice(64, 100, 0);
    assertSame(v, sound.voices.get(0));

    int playPosBefore = v.unisonParts[0].sources[0].voiceSample.reader.playPos;
    int[] out = new int[64];
    v.render(out, 32, false, false);
    int playPosAfter = v.unisonParts[0].sources[0].voiceSample.reader.playPos;
    assertEquals(
        playPosBefore,
        playPosAfter,
        "Non-SAMPLE oscType must not advance the retained sample reader during legato");
  }

  @Test
  void round3_changeNoteCodeWithNullMpeResetsExpressionTargets() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    Voice v = new Voice(sound);
    v.noteOn(60, 100, 2, new int[] {1000, 2000, 3000});
    assertNotEquals(0, v.localExpressionSourceValuesBeforeSmoothing[0]);

    // Legato note from a channel with no MPE values must smooth expression targets back to 0.
    v.changeNoteCode(64, 3, null);
    assertEquals(0, v.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(0, v.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(0, v.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(0b111, v.expressionSourcesCurrentlySmoothing);
  }

  @Test
  void round3_timeStretchHopWrapsCursorBeforePrimingReaderHistory() {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 2;
    sample.sampleRate = 44100;
    sample.audioDataStartPosBytes = 0;
    sample.lengthInSamples = 4000;
    sample.audioDataLengthBytes = 8000;
    sample.data = new int[4000];
    // Mark frames [100, 200) distinctly from frames outside the loop [200, 4000).
    Arrays.fill(sample.data, 100, 200, 111_111);
    Arrays.fill(sample.data, 200, 4000, 999_999);

    VoiceSample vs = new VoiceSample();
    vs.setupTimeStretch(sample, 100, 200, 1, true, 100, true, 200);
    // Place samplePosBig near loop end (195) with samplesTilHopEnd = 0 and 2x timeStretchRatio so
    // hopEndVoid places newHeadBytePos past loopEnd (200) before VoiceSample wraps it.
    vs.timeStretcher.samplePosBig = 195L << 24;
    vs.timeStretcher.samplesTilHopEnd = 0;

    int[] out = new int[8];
    int[] amp = new int[] {1 << 24};
    vs.renderTimeStretched(out, 1, 1, 1 << 24, 1 << 25, amp, 0);

    assertTrue(
        vs.reader.playPos >= 100 && vs.reader.playPos < 200,
        "Wrapped hop playPos must stay inside [100, 200), got " + vs.reader.playPos);
    for (int i = 0; i < SampleReader.K_TAPS; i++) {
      assertNotEquals(
          999_999,
          vs.reader.bufL[i],
          "Wrapped hop history tap " + i + " must not contain frames from outside loop [100, 200)");
    }
  }

  @Test
  void round4_exhaustedSampleOnlyVoiceRetiresOrFastReleasesSoLegatoNoteRetriggers() {
    // 1. Filters OFF: exhausted sample-only voice retires immediately (active = false).
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(16, 44100);
    sound.sampleEndPoint[0] = 16;
    sound.sampleLoopMode[0] = Sound.REPEAT_CUT; // not looping, plays to its end
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    sound.renderInternal(new int[128], 64, new int[128]);
    assertFalse(v.active, "Exhausted sample-only voice with filters off must unassign");

    // Next overlapping legato note must retrigger instead of reusing the silent dead voice.
    sound.triggerNote(64, 100);
    Voice vNext = sound.voices.get(0);
    assertTrue(vNext.active);
    assertTrue(vNext.unisonParts[0].sources[0].voiceSample.active);
    int[] out = new int[128];
    sound.renderInternal(out, 64, new int[128]);
    boolean anyNonZero = false;
    for (int sampleVal : out) {
      if (sampleVal != 0) {
        anyNonZero = true;
        break;
      }
    }
    assertTrue(anyNonZero, "Retriggered legato note after sample exhaustion must render audio");

    // 2. Filters ON: exhausted sample-only voice enters FAST_RELEASE (>= RELEASE), so legato
    // note also falls through to retrigger instead of reusing it.
    Sound filtered = makeInitializedSound();
    filtered.polyphonic = Sound.PolyphonyMode.LEGATO;
    filtered.oscTypes[0] = Oscillator.OscType.SAMPLE;
    filtered.samples[0] = makeRampSample(16, 44100);
    filtered.sampleEndPoint[0] = 16;
    filtered.sampleLoopMode[0] = Sound.REPEAT_CUT; // not looping, plays to its end
    filtered.lpfMode = FilterSet.FilterMode.TRANSISTOR_24DB;
    filtered.patchedParamValues[Param.LOCAL_LPF_FREQ] = 0;
    filtered.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    filtered.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    filtered.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    filtered.triggerNote(60, 100);
    Voice vf = filtered.voices.get(0);
    filtered.renderInternal(new int[128], 64, new int[128]);
    assertTrue(
        vf.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) >= 0,
        "Exhausted sample-only voice with filter on must enter FAST_RELEASE");
    filtered.triggerNote(64, 100);
    assertEquals(64, filtered.voices.get(0).noteCode);
    assertTrue(filtered.voices.get(0).unisonParts[0].sources[0].voiceSample.active);

    // 3. When Osc B can still sound, sample exhaustion on Osc A must NOT retire the legato voice,
    // and legato retune must NOT re-enable the completed Osc A sample source.
    Sound dual = makeInitializedSound();
    dual.polyphonic = Sound.PolyphonyMode.LEGATO;
    dual.oscTypes[0] = Oscillator.OscType.SAMPLE;
    dual.oscTypes[1] = Oscillator.OscType.SAW;
    dual.samples[0] = makeRampSample(16, 44100);
    dual.sampleEndPoint[0] = 16;
    dual.sampleLoopMode[0] = Sound.REPEAT_CUT; // not looping, plays to its end
    dual.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    dual.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    dual.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    dual.triggerNote(60, 100);
    Voice vd = dual.voices.get(0);
    dual.renderInternal(new int[128], 64, new int[128]);
    assertTrue(vd.active, "Voice must remain active when Osc B can still sound");
    assertFalse(vd.unisonParts[0].sources[0].active, "Completed sample source must be inactive");

    dual.triggerNote(62, 100);
    assertSame(vd, dual.voices.get(0), "Legato reuse must be preserved when Osc B still sounds");
    assertFalse(
        vd.unisonParts[0].sources[0].active,
        "Legato retune must not re-enable a completed sample source");
  }

  @Test
  void round4_shortOctaveDownLoopedTimeStretchSurvivesMultipleHops() {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 2;
    sample.sampleRate = 44100;
    sample.audioDataStartPosBytes = 0;
    sample.lengthInSamples = 128;
    sample.audioDataLengthBytes = 256;
    sample.data = new int[128];
    for (int i = 0; i < 128; i++) {
      sample.data[i] = (i - 64) * (1 << 20);
    }

    VoiceSample vs = new VoiceSample();
    vs.setupTimeStretch(sample, 0, 128, 1, true, 0, false, 128);

    // Octave down (phaseIncrement = 1 << 23) with pitch-independent speed (timeStretchRatio = 1 <<
    // 25). Render enough blocks to cross multiple hops and loops without losing both heads.
    int[] out = new int[128];
    int[] amp = new int[] {1 << 24};
    for (int block = 0; block < 40; block++) {
      Arrays.fill(out, 0);
      amp[0] = 1 << 24;
      vs.renderTimeStretched(out, 64, 1, 1 << 23, 1 << 25, amp, 0);
      assertTrue(vs.active, "Looped time-stretch must stay active at block " + block);
      assertTrue(
          vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]
              || vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER],
          "At least one time-stretch playhead must stay active at block " + block);
    }
  }

  @Test
  void round4_resampledLateStartPastSustainLoopEndWrapsBeforePrimingHistory() {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 2;
    sample.sampleRate = 44100;
    sample.audioDataStartPosBytes = 0;
    sample.lengthInSamples = 64;
    sample.audioDataLengthBytes = 128;
    sample.data = new int[64];
    Arrays.fill(sample.data, 0, 20, 111_111);
    Arrays.fill(sample.data, 20, 64, 999_999);

    VoiceSample vs = new VoiceSample();
    // Sample end 64, sustain loop [10, 20), late start by 40 frames -> startAtFrame = 40.
    vs.setupLate(sample, 0, 64, 1, false, 10, true, 20, 40);
    assertTrue(vs.attemptLateSampleStart(40));
    assertEquals(10, vs.reader.playPos, "Late start at 40 must wrap into sustain loop [10, 20)");

    int[] out = new int[8];
    int[] amp = new int[] {1 << 24};
    // Resampled read (phaseIncrement = 20_000_000 != 1<<24) uses 16-tap sinc window.
    vs.render(out, 2, 1, 20_000_000, amp, 0);
    for (int i = 0; i < SampleReader.K_TAPS; i++) {
      assertNotEquals(
          999_999,
          vs.reader.bufL[i],
          "Late-start 16-tap history tap " + i + " must not contain post-loop audio");
    }
  }

  @Test
  void round5_deferredSampleStartPreservesNoteOffReleaseTail() {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 2;
    sample.sampleRate = 44100;
    sample.audioDataStartPosBytes = 0;
    sample.lengthInSamples = 64;
    sample.audioDataLengthBytes = 128;
    sample.data = new int[64];
    Arrays.fill(sample.data, 0, 20, 111_111);
    Arrays.fill(sample.data, 20, 64, 777_777);

    VoiceSample vs = new VoiceSample();
    // Sample end 64, sustain loop [10, 20), late start by 40 frames -> startAtFrame = 40.
    vs.setupLate(sample, 0, 64, 1, false, 10, true, 20, 40);
    // Note-off arrives before attemptLateSampleStart runs.
    vs.onNoteOff();
    assertTrue(vs.attemptLateSampleStart(40));
    assertTrue(vs.noteOffReceived);
    assertEquals(
        40,
        vs.reader.playPos,
        "Deferred sample start after note-off must stay in release tail at frame 40, not wrap to 10");
    assertEquals(64, vs.reader.endFrame);
    assertFalse(vs.reader.looping);
  }

  @Test
  void round5_legatoVoiceSwitchedFromWaveformToSampleInitializesSampleSource() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.oscTypes[1] = Oscillator.OscType.SINE;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);
    assertEquals(1, sound.voices.size());
    assertEquals(null, sound.voices.get(0).sources[0].sampleRef);

    // Switch Osc A to SAMPLE while Note 60 is still held in LEGATO mode.
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(128, 44100);
    sound.sampleLoopMode[0] = Sound.REPEAT_LOOP;

    sound.triggerNote(64, 100);
    Voice v = sound.voices.get(0);
    assertNotEquals(
        null,
        v.sources[0].sampleRef,
        "Overlapping legato note after waveform->SAMPLE switch must initialize sampleRef");
    assertTrue(v.sources[0].voiceSample.active, "Newly initialized sample source must be active");

    Arrays.fill(buf, 0);
    sound.renderInternal(buf, 64, rev);
    long energy = 0;
    for (int sampleVal : buf) {
      energy += Math.abs((long) sampleVal);
    }
    assertTrue(energy > 0, "Legato note after waveform->SAMPLE switch must render audible audio");
  }

  @Test
  void round6_legatoVoiceSwitchedFromExhaustedSampleToWaveformReactivatesSource() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(16, 44100);
    sound.sampleLoopMode[0] = Sound.REPEAT_CUT;
    sound.oscTypes[1] = Oscillator.OscType.SINE;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    assertTrue(v.active, "Osc B must keep the legato voice alive after Osc A sample finishes");
    assertFalse(
        v.unisonParts[0].sources[0].active, "Osc A sample must be exhausted and inactive now");

    // Switch Osc A to SAW and silence Osc B, then trigger an overlapping legato note.
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.triggerNote(64, 100);

    assertEquals(null, v.sources[0].sampleRef, "Transition away from SAMPLE must clear sampleRef");
    assertTrue(
        v.unisonParts[0].sources[0].active,
        "Transition from exhausted SAMPLE to SAW on legato note must reactivate Osc A");
    Arrays.fill(buf, 0);
    sound.renderInternal(buf, 64, rev);
    long energy = 0;
    for (int sampleVal : buf) {
      energy += Math.abs((long) sampleVal);
    }
    assertTrue(energy > 0, "Reactivated SAW source must produce audible output");
  }

  @Test
  void round6_liveTransposeLoweredOnHeldVoiceRestoresPitchDisabledSource() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.oscTypes[1] = Oscillator.OscType.SINE;
    sound.sourceTranspose[0] = 84; // Over-Nyquist on note 60
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    assertFalse(v.unisonParts[0].sources[0].active);
    assertTrue(v.unisonParts[0].sources[0].disabledByPitch);

    // Lower transpose live on the held voice without triggering a new note.
    sound.sourceTranspose[0] = 0;
    sound.renderInternal(buf, 64, rev);

    assertTrue(
        v.unisonParts[0].sources[0].active,
        "Voice.render must restore a pitch-disabled source once transpose drops below Nyquist");
    assertFalse(v.unisonParts[0].sources[0].disabledByPitch);
  }

  @Test
  void round6_onceSampleWithSustainLoopEndDoesNotReleaseEnvelopeOnNoteOff() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(512, 44100);
    sound.sampleLoopMode[0] = Sound.REPEAT_ONCE;
    sound.sampleLoopStart[0] = 10;
    sound.sampleLoopEnd[0] = 20;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    // Fast release so if envelope 0 released on noteOff, the voice would cut off immediately.
    sound.patchedParamValues[Param.LOCAL_ENV_0_RELEASE] = Integer.MAX_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    VoiceSample vs = v.unisonParts[0].sources[0].voiceSample;
    assertTrue(vs.shouldObeyLoopEndPointNow(), "Held ONCE sample must loop at sustain loop-end");

    sound.releaseNote(60);
    assertTrue(vs.noteOffReceived, "Note-off must notify sample reader to exit sustain loop");
    assertTrue(
        v.previouslyIgnoredNoteOff,
        "Note-off on ONCE sample must record previouslyIgnoredNoteOff instead of releasing env0");
    assertTrue(
        v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0,
        "Envelope 0 must stay pre-release so the ONCE sample tail plays out in full");

    for (int b = 0; b < 4; b++) {
      Arrays.fill(buf, 0);
      sound.renderInternal(buf, 64, rev);
      long energy = 0;
      for (int s : buf) {
        energy += Math.abs((long) s);
      }
      assertTrue(energy > 0, "ONCE sample tail past loopEnd must keep sounding on block " + b);
    }
  }

  @Test
  void round6_timeStretchCrossfadeWithNonzeroAmplitudeIncrementMatchesRamp() {
    Sample sample = new Sample();
    sample.numChannels = 1;
    sample.byteDepth = 4;
    sample.sampleRate = 44100;
    sample.lengthInSamples = 1024;
    sample.audioDataStartPosBytes = 0;
    sample.audioDataLengthBytes = 1024 * 4;
    sample.data = new int[1024];
    Arrays.fill(sample.data, 1 << 29);

    for (int dir : new int[] {1, -1}) {
      VoiceSample vs = new VoiceSample();
      vs.setupTimeStretch(sample, 0, 1024, 1, false, 0, false, 1024);
      vs.olderReader.copyStateFrom(vs.reader);
      vs.olderReader.init(64);
      vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = true;
      vs.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = true;
      vs.timeStretcher.crossfadeProgress = 0;
      vs.timeStretcher.crossfadeIncrement = (1 << 24) / 32;
      vs.timeStretcher.samplesTilHopEnd = 32;

      int startAmp = dir > 0 ? (1 << 24) : (1 << 26);
      int ampInc = dir * (1 << 18);
      int[] ampHolder = {startAmp};
      int[] out = new int[32];
      vs.renderTimeStretched(out, 32, 1, 1 << 24, 1 << 23, ampHolder, ampInc);

      // Compare against a non-crossfading single head with the exact same amplitude ramp.
      VoiceSample single = new VoiceSample();
      single.setupTimeStretch(sample, 0, 1024, 1, false, 0, false, 1024);
      single.timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
      single.timeStretcher.crossfadeProgress = 1 << 24;
      single.timeStretcher.samplesTilHopEnd = 32;
      int[] singleAmpHolder = {startAmp};
      int[] singleOut = new int[32];
      single.renderTimeStretched(singleOut, 32, 1, 1 << 24, 1 << 23, singleAmpHolder, ampInc);

      assertEquals(singleAmpHolder[0], ampHolder[0]);
      // On a constant-valued sample, the crossfade sum at the end of the chunk must closely track
      // the single-head ramped level (within rounding tolerance), NOT drift by 2x the ramp slope.
      long diffEnd = Math.abs((long) out[31] - singleOut[31]);
      long rampChange = Math.abs((long) singleOut[31] - singleOut[0]);
      assertTrue(
          diffEnd < rampChange / 4,
          "Crossfade end amplitude must follow the requested ramp slope (dir=" + dir + ")");
    }
  }

  @Test
  void round6_oneShotQualityFallbackFromSincToLinearDoesNotTruncateEnd() {
    Sample sample = makeRampSample(130, 44100);
    VoiceSample vs = new VoiceSample();
    vs.setup(sample, 0, 130, 1, false, 0, false, 130);
    int phaseInc = 2 << 24; // 2x pitch
    int[] amp = {1 << 26};

    // Block 1: 64 output samples with 16-tap sinc leaves playPos at 135 (< stopFrame 146).
    int[] out1 = new int[64];
    vs.linearInterpolation = false;
    vs.render(out1, 64, 1, phaseInc, amp, 0);
    assertTrue(vs.active, "VoiceSample must still be active after block 1");
    assertEquals(135, vs.reader.playPos);

    // Block 2: switch to 2-tap linear. Reader must rewind 7 frames to 128 before checking
    // stopFrame = 132, so the remaining frames render instead of truncating immediately.
    int[] out2 = new int[64];
    vs.linearInterpolation = true;
    vs.render(out2, 64, 1, phaseInc, amp, 0);
    long energy2 = 0;
    for (int s : out2) {
      energy2 += Math.abs((long) s);
    }
    assertTrue(energy2 > 0, "16->2 tap fallback near sample end must render remaining frames");
  }

  @Test
  void round7_heldSampleVoiceRecoversWhenExcessiveTransposeIsLowered() {
    for (int sampleOsc = 0; sampleOsc < 2; sampleOsc++) {
      int otherOsc = 1 - sampleOsc;
      Sound sound = makeInitializedSound();
      sound.oscTypes[sampleOsc] = Oscillator.OscType.SAMPLE;
      sound.samples[sampleOsc] = makeRampSample(256, 44100);
      sound.sampleLoopMode[sampleOsc] = Sound.REPEAT_LOOP;
      sound.oscTypes[otherOsc] = Oscillator.OscType.SINE;
      sound.sourceTranspose[otherOsc] = -84; // Keep otherOsc below Nyquist when masterTranspose=84
      sound.masterTranspose = 84; // Puts sampleOsc over Nyquist on note 60
      sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
      sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;

      sound.triggerNote(60, 100);
      int[] buf = new int[128];
      int[] rev = new int[64];
      sound.renderInternal(buf, 64, rev);

      Voice v = sound.voices.get(0);
      VoiceSource vs = v.unisonParts[0].sources[sampleOsc];
      assertFalse(vs.active, "Sample osc " + sampleOsc + " must be pitch-disabled at +84");
      assertTrue(vs.disabledByPitch);
      assertTrue(vs.voiceSample.active, "Underlying sample reader must stay active");

      // Lower masterTranspose on the held voice and mute otherOsc so only sampleOsc can sound.
      sound.masterTranspose = 0;
      sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME + otherOsc] = Integer.MIN_VALUE;
      Arrays.fill(buf, 0);
      sound.renderInternal(buf, 64, rev);

      assertTrue(vs.active, "Held sample osc " + sampleOsc + " must recover when transpose drops");
      assertFalse(vs.disabledByPitch);
      long energy = 0;
      for (int s : buf) {
        energy += Math.abs((long) s);
      }
      assertTrue(energy > 0, "Recovered sample osc " + sampleOsc + " must output audio");
    }
  }

  @Test
  void round7_pitchDisabledWaveformMutedDuringPitchDropRecoversAfterUnmuting() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.oscTypes[1] = Oscillator.OscType.SINE;
    sound.sourceTranspose[0] = 84; // Over-Nyquist on note 60
    sound.sourceTranspose[1] = 0;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    VoiceSource vsA = v.unisonParts[0].sources[0];
    assertFalse(vsA.active);
    assertTrue(vsA.disabledByPitch);

    // Mute Osc A while lowering transpose below Nyquist.
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    sound.sourceTranspose[0] = 0;
    sound.renderInternal(buf, 64, rev);
    assertFalse(vsA.active, "Muted source must not activate while volume is MIN_VALUE");
    assertTrue(
        vsA.disabledByPitch,
        "disabledByPitch must remain set while muted so unmuting later can restore the source");

    // Unmute Osc A and mute Osc B on the held voice.
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    Arrays.fill(buf, 0);
    sound.renderInternal(buf, 64, rev);

    assertTrue(vsA.active, "Pitch-disabled waveform must recover once unmuted below Nyquist");
    assertFalse(vsA.disabledByPitch);
    long energy = 0;
    for (int s : buf) {
      energy += Math.abs((long) s);
    }
    assertTrue(energy > 0, "Unmuted recovered waveform must output audio");
  }

  @Test
  void round7_increasingUnisonDuringHeldLegatoNoteInitializesNewParts() {
    // 1. Waveform (SAW) legato voice: numUnison 1 -> 4 during held note.
    Sound sawSound = makeInitializedSound();
    sawSound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sawSound.oscTypes[0] = Oscillator.OscType.SAW;
    sawSound.numUnison = 1;
    sawSound.unisonDetune = 20;
    sawSound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sawSound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    sawSound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sawSound.renderInternal(buf, 64, rev);
    Voice vSaw = sawSound.voices.get(0);
    assertEquals(1, vSaw.initializedUnisonParts);
    int part0PhaseBefore = vSaw.unisonParts[0].sources[0].oscPos;

    // Increase unison to 4 and trigger an overlapping legato note.
    sawSound.numUnison = 4;
    sawSound.triggerNote(64, 100);
    assertSame(vSaw, sawSound.voices.get(0));
    assertEquals(4, vSaw.initializedUnisonParts);
    assertEquals(
        part0PhaseBefore,
        vSaw.unisonParts[0].sources[0].oscPos,
        "Existing unison part 0 phase must not be reset by legato unison increase");
    for (int u = 0; u < 4; u++) {
      assertTrue(
          vSaw.unisonParts[u].sources[0].active, "Unison part " + u + " must be active on SAW");
    }

    int[] phasesBeforeRender = new int[4];
    for (int u = 0; u < 4; u++) {
      phasesBeforeRender[u] = vSaw.unisonParts[u].sources[0].oscPos;
    }
    Arrays.fill(buf, 0);
    sawSound.renderInternal(buf, 64, rev);
    for (int u = 0; u < 4; u++) {
      assertNotEquals(
          phasesBeforeRender[u],
          vSaw.unisonParts[u].sources[0].oscPos,
          "Unison part " + u + " must render and advance oscPos");
    }

    // 2. Sample legato voice: numUnison 1 -> 4 during held note clones active sample state.
    Sound sampleSound = makeInitializedSound();
    sampleSound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sampleSound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sampleSound.samples[0] = makeRampSample(256, 44100);
    sampleSound.sampleLoopMode[0] = Sound.REPEAT_LOOP;
    sampleSound.numUnison = 1;
    sampleSound.unisonDetune = 15;
    sampleSound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sampleSound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;

    sampleSound.triggerNote(60, 100);
    sampleSound.renderInternal(buf, 64, rev);
    Voice vSample = sampleSound.voices.get(0);
    int part0PlayPos = vSample.unisonParts[0].sources[0].voiceSample.reader.playPos;
    assertTrue(part0PlayPos > 0);

    sampleSound.numUnison = 4;
    sampleSound.triggerNote(64, 100);
    assertSame(vSample, sampleSound.voices.get(0));
    assertEquals(4, vSample.initializedUnisonParts);
    for (int u = 0; u < 4; u++) {
      VoiceSource vs = vSample.unisonParts[u].sources[0];
      assertTrue(vs.active, "Sample unison part " + u + " must be active");
      assertNotEquals(null, vs.sampleRef, "Sample unison part " + u + " must have sampleRef");
      assertTrue(vs.voiceSample.active, "Sample unison part " + u + " reader must be active");
      assertEquals(
          part0PlayPos,
          vs.voiceSample.reader.playPos,
          "Newly added sample unison part " + u + " must clone current playPos");
    }

    Arrays.fill(buf, 0);
    sampleSound.renderInternal(buf, 64, rev);
    for (int u = 0; u < 4; u++) {
      assertNotEquals(
          part0PlayPos,
          vSample.unisonParts[u].sources[0].voiceSample.reader.playPos,
          "Sample unison part " + u + " reader must advance during render");
    }
  }

  @Test
  void round8_unloadedSampleOscillatorDoesNotEnableNoteTailsOrTruncateOnceSample() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.oscTypes[1] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(512, 44100);
    sound.samples[1] = null; // Osc B is SAMPLE with no file loaded
    sound.sampleLoopMode[0] = Sound.REPEAT_ONCE; // ONCE on Osc A
    sound.sampleLoopMode[1] = Sound.REPEAT_CUT; // default (source.cpp:39) on unloaded Osc B
    sound.sampleLoopStart[0] = 10;
    sound.sampleLoopEnd[0] = 20;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_ENV_0_RELEASE] = Integer.MAX_VALUE;

    assertFalse(
        sound.allowNoteTails(true),
        "Unloaded Osc B SAMPLE source must not make allowNoteTails(true) return true");

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    sound.releaseNote(60);
    assertTrue(
        v.previouslyIgnoredNoteOff,
        "Unloaded Osc B must not cause Voice.noteOff to release Osc A's ONCE tail");
    assertTrue(v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0);

    Arrays.fill(buf, 0);
    sound.renderInternal(buf, 64, rev);
    long energy = 0;
    for (int s : buf) {
      energy += Math.abs((long) s);
    }
    assertTrue(energy > 0, "Osc A ONCE tail must continue sounding after note-off");

    // Loading a key zone sample on Osc B makes Osc B a real active source, enabling note tails.
    Sound.CompiledKeyZone kz = new Sound.CompiledKeyZone();
    kz.sample = makeRampSample(64, 44100);
    sound.sourceZones[1].add(kz);
    assertTrue(sound.hasAnySampleLoaded(1));
    assertTrue(sound.allowNoteTails(true));
  }

  @Test
  void round8_legatoDoesNotReuseVoiceWithDeferredNoteOff() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.LEGATO;
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    sound.samples[0] = makeRampSample(512, 44100);
    sound.sampleLoopMode[0] = Sound.REPEAT_ONCE; // ONCE -> allowNoteTails(true) is false
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_ENV_0_RELEASE] = Integer.MAX_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    sound.releaseNote(60);
    assertTrue(v.previouslyIgnoredNoteOff, "ONCE note-off must set previouslyIgnoredNoteOff");
    assertTrue(v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0);

    // Before the next render, switch to SAW (so allowNoteTails(true) is now true) and trigger a
    // new note. It must NOT take the held-legato reuse path with previouslyIgnoredNoteOff still
    // set.
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.triggerNote(64, 100);
    Voice vNew = sound.voices.get(0);
    assertEquals(64, vNew.noteCode);
    assertFalse(
        vNew.previouslyIgnoredNoteOff,
        "New note after released ONCE note must clear previouslyIgnoredNoteOff via full noteOn");

    sound.renderInternal(buf, 64, rev);
    assertTrue(
        vNew.active && vNew.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0,
        "New note must stay held and must not be prematurely released by stale deferred note-off");
  }

  @Test
  void round9_oscSyncClearsCachedIncrementWhenOscillatorADisabledByPitch() {
    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.oscTypes[1] = Oscillator.OscType.SAW;
    sound.oscillatorSync = true;
    // Mute Osc A so only Osc B's audio is written into the output buffer.
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    sound.triggerNote(60, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    long energyValid = 0;
    for (int s : buf) {
      energyValid += Math.abs((long) s);
    }
    assertTrue(energyValid > 0, "Synced Osc B must render audio when Osc A is below Nyquist");

    Voice v = sound.voices.get(0);
    int posBeforeDisable = v.unisonParts[0].sources[1].oscPos;

    // Transpose Osc A above Nyquist while Osc B stays in range.
    sound.sourceTranspose[0] = 84;
    Arrays.fill(buf, 0);
    sound.renderInternal(buf, 64, rev);

    assertFalse(v.unisonParts[0].sources[0].active, "Osc A must be disabled above Nyquist");
    assertTrue(v.unisonParts[0].sources[0].disabledByPitch);
    assertEquals(
        posBeforeDisable,
        v.unisonParts[0].sources[1].oscPos,
        "Synced Osc B must skip rendering when Osc A's sync increment is cleared to 0");
    long energyDisabled = 0;
    for (int s : buf) {
      energyDisabled += Math.abs((long) s);
    }
    assertEquals(0L, energyDisabled, "Synced Osc B must not render using a stale sync increment");

    // Bring Osc A back below Nyquist; Osc B must resume synced rendering.
    sound.sourceTranspose[0] = 0;
    Arrays.fill(buf, 0);
    sound.renderInternal(buf, 64, rev);
    long energyRestored = 0;
    for (int s : buf) {
      energyRestored += Math.abs((long) s);
    }
    assertTrue(energyRestored > 0, "Synced Osc B must resume rendering once Osc A recovers");
  }

  @Test
  void round9_eightOctaveDownPitchShiftDoesNotOverflowTimeStretchRatioToZero() {
    assertEquals(
        Integer.MAX_VALUE,
        Voice.computePitchCompensatedTimeStretchRatio(16777216, 65536),
        "8-octave downward shift (pInc=65536) must clamp ratio to Integer.MAX_VALUE, not wrap to 0");

    Sound sound = makeInitializedSound();
    sound.oscTypes[0] = Oscillator.OscType.SAMPLE;
    Sample sample = makeRampSample(1024, 44100);
    sound.samples[0] = sample;
    sound.sampleTimestretch[0] = true;
    sound.sampleLoopMode[0] = Sound.REPEAT_CUT;
    Sound.CompiledKeyZone kz = new Sound.CompiledKeyZone();
    kz.minPitch = 0;
    kz.maxPitch = 127;
    kz.minVelocity = 0;
    kz.maxVelocity = 127;
    kz.sample = sample;
    kz.transpose = -48; // Root note MIDI 108 (60 - 108 = -48)
    kz.endSamplePos = 1024;
    sound.sourceZones[0].add(kz);
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    // Trigger MIDI 12 -> 96 semitones (8 octaves) below root MIDI 108 -> pInc = 65536.
    sound.triggerNote(12, 100);
    int[] buf = new int[128];
    int[] rev = new int[64];
    sound.renderInternal(buf, 64, rev);

    Voice v = sound.voices.get(0);
    VoiceSource vs = v.unisonParts[0].sources[0];
    assertEquals(
        Integer.MAX_VALUE,
        vs.timeStretchRatio,
        "Time-stretch ratio must clamp to Integer.MAX_VALUE instead of wrapping to 0");
    long posAfterBlock1 = vs.voiceSample.timeStretcher.samplePosBig;
    assertTrue(posAfterBlock1 > 0L, "samplePosBig must advance on block 1 instead of freezing");

    sound.renderInternal(buf, 64, rev);
    long posAfterBlock2 = vs.voiceSample.timeStretcher.samplePosBig;
    assertTrue(
        posAfterBlock2 > posAfterBlock1,
        "samplePosBig must continue advancing across blocks under 8-octave downward pitch shift");
  }
}
