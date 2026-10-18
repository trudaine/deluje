package org.deluge.firmware2;

/**
 * Faithful port of the playback-relevant part of {@code model/sample/sample_holder.{h,cpp}}: the
 * start/end sample markers and the duration helpers the time-stretcher reads. The cluster-reason /
 * waveform-view / SD members are omitted (desktop, in-RAM — see {@link Sample}).
 */
public class SampleHolder {

  /** C: definitions_cxx.hpp kMaxSampleValue = 1 << 24. */
  public static final int K_MAX_SAMPLE_VALUE = 16777216;

  /** C: kSampleRate = 44100. */
  static final int K_SAMPLE_RATE = 44100;

  /** C: AudioFileHolder::audioFile (here always a {@link Sample}). */
  public Sample audioFile;

  /** C: sample_holder.h:47 — in samples. */
  public long startPos;

  /** C: sample_holder.h:48 — in samples; may be beyond the sample end (call {@link #getEndPos}). */
  public long endPos;

  /** C: sample_holder.h:53 — neutral phase increment (kMaxSampleValue at 44100 Hz). */
  public int neutralPhaseIncrement = K_MAX_SAMPLE_VALUE;

  /** C: sample_holder.cpp:29-32 — startPos = 0, endPos = 9999999. */
  public SampleHolder() {
    startPos = 0;
    endPos = 9999999L;
  }

  /** C: sample_holder.cpp:78-85 — clamp to the sample length unless time-stretching. */
  public long getEndPos(boolean forTimeStretching) {
    if (forTimeStretching) {
      return endPos;
    }
    return Math.min(endPos, audioFile.lengthInSamples);
  }

  public long getEndPos() {
    return getEndPos(false);
  }

  /** C: sample_holder.cpp:87-89 — getDurationInSamples. */
  public long getDurationInSamples(boolean forTimeStretching) {
    return getEndPos(forTimeStretching) - startPos;
  }

  /** C: sample_holder.cpp:91-99 — getLengthInSamplesAtSystemSampleRate. */
  public int getLengthInSamplesAtSystemSampleRate(boolean forTimeStretching) {
    long lengthInSamples = getDurationInSamples(forTimeStretching);
    if (neutralPhaseIncrement == K_MAX_SAMPLE_VALUE) {
      return (int) lengthInSamples;
    }
    return (int) Long.divideUnsigned(lengthInSamples << 24, neutralPhaseIncrement & 0xFFFFFFFFL);
  }

  /**
   * C: sample_holder.cpp:112-149 — assign sample and clamp or reset start/end markers. Cluster
   * claiming is omitted on desktop (in-RAM {@link Sample}).
   */
  public void setAudioFile(Sample newSample, boolean reversed, boolean manuallySelected) {
    this.audioFile = newSample;
    if (audioFile != null) {
      long lengthInSamples = audioFile.lengthInSamples & 0xFFFFFFFFL;
      if (manuallySelected) {
        startPos = 0;
        endPos = lengthInSamples;
      } else {
        startPos = Math.min(startPos, lengthInSamples);
        if (endPos == 0 || endPos == 9999999L) {
          endPos = lengthInSamples;
        }
        if (endPos <= startPos) {
          startPos = 0;
        }
      }
      sampleBeenSet(reversed, manuallySelected);
    }
  }

  /** C: sample_holder.h:62 / sample_holder_for_voice.cpp:179 — update neutralPhaseIncrement. */
  protected void sampleBeenSet(boolean reversed, boolean manuallySelected) {
    if (audioFile != null && audioFile.sampleRate > 0) {
      neutralPhaseIncrement =
          (int) Long.divideUnsigned(((long) audioFile.sampleRate) << 24, K_SAMPLE_RATE);
    }
  }
}
