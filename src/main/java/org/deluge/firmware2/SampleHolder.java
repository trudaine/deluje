package org.deluge.firmware2;

/**
 * Faithful port of the playback-relevant part of {@code model/sample/sample_holder.{h,cpp}}: the
 * start/end sample markers and the duration helpers the time-stretcher reads. The cluster-reason /
 * waveform-view / SD members are omitted (desktop, in-RAM — see {@link Sample}).
 */
public class SampleHolder {

  /** C: AudioFileHolder::audioFile (here always a {@link Sample}). */
  public Sample audioFile;

  /** C: sample_holder.h:47 — in samples. */
  public long startPos;

  /** C: sample_holder.h:48 — in samples; may be beyond the sample end (call {@link #getEndPos}). */
  public long endPos;

  /** C: sample_holder.cpp getEndPos — clamp to the sample length unless time-stretching. */
  public long getEndPos(boolean forTimeStretching) {
    if (forTimeStretching) {
      return endPos;
    }
    return Math.min(endPos, audioFile.lengthInSamples);
  }

  public long getEndPos() {
    return getEndPos(false);
  }

  /** C: sample_holder.cpp getDurationInSamples. */
  public long getDurationInSamples(boolean forTimeStretching) {
    return getEndPos(forTimeStretching) - startPos;
  }
}
