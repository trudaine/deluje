package org.deluge.firmware2;

import org.deluge.firmware2.Oscillator.OscType;

/**
 * Port of C++ struct VoiceUnisonPartSource (voice_unison_part_source.h /
 * voice_unison_part_source.cpp). Represents a single oscillator/sample source within a unison part
 * of a Voice.
 */
public class VoiceSource {
  public Voice voiceRef;
  public int sourceIdx;

  public int oscPos; // 32-bit phase accumulator
  public OscType oscType = OscType.SINE;
  public int carrierFeedback; // FM carrier feedback memory
  public int phaseIncrementStoredValue;
  public int waveIndexLastTime;
  public boolean active;
  public boolean disabledByPitch;

  // Sample playback (when oscType == SAMPLE or source is sample-based).
  public final VoiceSample voiceSample = new VoiceSample();
  public Sample sampleRef; // the fw2 Sample backing this source

  /** C SampleHolder::getDurationInSamples(true): playing range endPos - startPos, file frames. */
  public int syncDurationFrames;

  /**
   * C VoiceSamplePlaybackGuide::sequenceSyncLengthTicks (voice.cpp:247) — STRETCH sync, 0 = off.
   */
  public int sequenceSyncLengthTicks;

  // Per-zone transpose from a matched multisample <sampleRange transpose=...>; INT_MIN = none, so
  // the pitch falls back to the WAV-root-derived transpose. The XML value is what the hardware
  // serialized (C SampleHolderForVoice::transpose = round(60 - midiNote)), so it's authoritative
  // when present and avoids depending on our midiNoteFromFile detection.
  public int zoneTranspose = Integer.MIN_VALUE;

  /** Matched multisample zone's cents fine-tune (C SampleHolderForVoice cents; voice.cpp:505). */
  public int zoneCents = 0;

  public int timeStretchRatio = 16777216; // 1 << 24 ≡ 1.0 (no time-stretch)

  // Live-input pitch shifting (C VoiceUnisonPartSource::livePitchShifter, voice.cpp:2236-2274).
  // Desktop seam: the per-source LiveInputBuffer + timer feed the shifter (the C uses shared
  // AudioEngine buffers; the fw2 shifter's render feeds its buffer itself).
  public LivePitchShifter livePitchShifter;
  public LiveInputBuffer liveInputBuffer;
  public int liveInputTimer;

  public final Dx7Voice dxVoice = new Dx7Voice();
  public final Dx7Voice.DxPatch dxPatch = new Dx7Voice.DxPatch();

  // Pre-allocated reusable buffers to prevent real-time GC churn
  public int[] tsBuf;
  public int[] shiftedBuf;

  /**
   * C VoiceUnisonPartSource::unassign (voice_unison_part_source.cpp:96-114) — clear sample state
   * across all unison parts so reusing a voice after switching from SAMPLE to a wave oscillator
   * never leaves stale sample playback active on higher unison parts.
   */
  public void clearSample() {
    if (voiceRef != null) {
      for (int u = 0; u < Sound.kMaxNumVoicesUnison; u++) {
        VoiceSource vs = voiceRef.unisonParts[u].sources[sourceIdx];
        vs.sampleRef = null;
        vs.voiceSample.active = false;
        vs.voiceSample.pendingSamplesLate = 0;
        vs.voiceSample.endTimeStretching();
      }
    } else {
      sampleRef = null;
      voiceSample.active = false;
      voiceSample.pendingSamplesLate = 0;
      voiceSample.endTimeStretching();
    }
  }

  /** C SampleControls::interpolationMode — apply linear interpolation across all unison parts. */
  public void setLinearInterpolation(boolean linear) {
    if (voiceRef != null) {
      for (int u = 0; u < Sound.kMaxNumVoicesUnison; u++) {
        voiceRef.unisonParts[u].sources[sourceIdx].voiceSample.linearInterpolation = linear;
      }
    } else {
      voiceSample.linearInterpolation = linear;
    }
  }

  public void setupSample(Sample fw2Sample, int startFrame, int playDirection) {
    setupSample(fw2Sample, startFrame, playDirection, 0);
  }

  public void setupSample(Sample fw2Sample, int startFrame, int playDirection, int samplesLate) {
    int end = (playDirection == 1) ? (int) fw2Sample.lengthInSamples : -1;
    setupSample(fw2Sample, startFrame, end, playDirection, false, startFrame, samplesLate);
  }

  public void setupSample(
      Sample fw2Sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame) {
    setupSample(fw2Sample, startFrame, endFrame, playDirection, looping, loopStartFrame, 0);
  }

  public void setupSample(
      Sample fw2Sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      int samplesLate) {
    setupSample(
        fw2Sample,
        startFrame,
        endFrame,
        playDirection,
        looping,
        loopStartFrame,
        false,
        endFrame,
        samplesLate);
  }

  public void setupSample(
      Sample fw2Sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      boolean hasLoopEnd,
      int loopEndFrame,
      int samplesLate) {
    if (voiceRef != null) {
      for (int u = 0; u < Sound.kMaxNumVoicesUnison; u++) {
        VoiceSource vs = voiceRef.unisonParts[u].sources[sourceIdx];
        if (u < voiceRef.sound.numUnison) {
          vs.sampleRef = fw2Sample;
          vs.zoneTranspose = this.zoneTranspose;
          vs.zoneCents = this.zoneCents;
          vs.voiceSample.linearInterpolation = this.voiceSample.linearInterpolation;
          vs.voiceSample.active = false;
          if (samplesLate == 0) {
            vs.voiceSample.setup(
                fw2Sample,
                startFrame,
                endFrame,
                playDirection,
                looping,
                loopStartFrame,
                hasLoopEnd,
                loopEndFrame);
          } else {
            vs.voiceSample.setupLate(
                fw2Sample,
                startFrame,
                endFrame,
                playDirection,
                looping,
                loopStartFrame,
                hasLoopEnd,
                loopEndFrame,
                samplesLate);
          }
        } else {
          vs.sampleRef = null;
          vs.voiceSample.active = false;
          vs.voiceSample.endTimeStretching();
        }
      }
    } else {
      sampleRef = fw2Sample;
      voiceSample.active = false;
      if (samplesLate == 0) {
        voiceSample.setup(
            fw2Sample,
            startFrame,
            endFrame,
            playDirection,
            looping,
            loopStartFrame,
            hasLoopEnd,
            loopEndFrame);
      } else {
        voiceSample.setupLate(
            fw2Sample,
            startFrame,
            endFrame,
            playDirection,
            looping,
            loopStartFrame,
            hasLoopEnd,
            loopEndFrame,
            samplesLate);
      }
    }
  }

  public void setupSampleTimeStretch(
      Sample fw2Sample, int startFrame, int playDirection, int tsRatio) {
    int end = (playDirection == 1) ? (int) fw2Sample.lengthInSamples : -1;
    setupSampleTimeStretch(
        fw2Sample, startFrame, end, playDirection, false, startFrame, false, end, tsRatio);
  }

  public void setupSampleTimeStretch(
      Sample fw2Sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      boolean hasLoopEnd,
      int loopEndFrame,
      int tsRatio) {
    if (voiceRef != null) {
      for (int u = 0; u < Sound.kMaxNumVoicesUnison; u++) {
        VoiceSource vs = voiceRef.unisonParts[u].sources[sourceIdx];
        if (u < voiceRef.sound.numUnison) {
          vs.sampleRef = fw2Sample;
          vs.zoneTranspose = this.zoneTranspose;
          vs.zoneCents = this.zoneCents;
          vs.voiceSample.linearInterpolation = this.voiceSample.linearInterpolation;
          vs.timeStretchRatio = tsRatio;
          vs.voiceSample.setupTimeStretch(
              fw2Sample,
              startFrame,
              endFrame,
              playDirection,
              looping,
              loopStartFrame,
              hasLoopEnd,
              loopEndFrame);
        } else {
          vs.sampleRef = null;
          vs.voiceSample.active = false;
          vs.voiceSample.endTimeStretching();
        }
      }
    } else {
      sampleRef = fw2Sample;
      timeStretchRatio = tsRatio;
      voiceSample.setupTimeStretch(
          fw2Sample,
          startFrame,
          endFrame,
          playDirection,
          looping,
          loopStartFrame,
          hasLoopEnd,
          loopEndFrame);
    }
  }
}
