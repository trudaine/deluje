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
    sound.sampleLoopMode[0] = 1; // LOOP
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
    sound.sampleLoopMode[0] = 1;
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
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    sound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;

    sound.triggerNote(60, 100);
    Voice v = sound.voices.get(0);
    Patcher.performInitialPatching(sound, v.sourceValues, v.paramFinalValues);
    int[] buf = new int[128];
    v.render(buf, 64, false, false);
    for (int sample : buf) {
      assertEquals(0, sample, "Muted sample oscillator must render silence (no 1<<26 floor)");
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
    assertTrue(out[0] > 0, "Half-step phase must interpolate between table[0]=0 and table[1]>0");
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
  void finding19_releaseVoiceMatchesMidiChannelForMpeSamePitchNotes() {
    Sound sound = makeInitializedSound();
    sound.polyphonic = Sound.PolyphonyMode.POLY;

    sound.triggerNote(60, 100, 2); // MPE member channel 2
    sound.triggerNote(60, 100, 3); // MPE member channel 3
    assertEquals(2, sound.voices.size());

    // Release note 60 on channel 2 only.
    sound.releaseNote(60, 2);
    Voice vCh2 = sound.voices.get(0);
    Voice vCh3 = sound.voices.get(1);
    assertTrue(
        vCh2.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) >= 0,
        "Channel 2 voice must enter release/fast-release");
    assertTrue(
        vCh3.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0,
        "Note 60 on MPE channel 3 must stay held when channel 2 releases");
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
}
