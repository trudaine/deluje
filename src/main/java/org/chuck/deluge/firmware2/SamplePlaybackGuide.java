package org.chuck.deluge.firmware2;

/**
 * Faithful port of the position/loop part of {@code model/sample/sample_playback_guide.{h,cpp}}: the
 * playback bounds (start/end byte) the time-stretcher uses. The transport-sync methods
 * ({@code getSyncedNumSamplesIn}, {@code getNumSamplesLaggingBehindSync},
 * {@code adjustPitchToCorrectDriftFromSync}) depend on {@code playbackHandler} and are deferred to a
 * seam to be added when {@code hopEnd} is ported (Phase B). {@code getFinalClusterIndex} is omitted
 * (cluster-only, desktop is in-RAM).
 */
public class SamplePlaybackGuide {

  /** C: sample_playback_guide.h:43 — +1 forwards, -1 reversed. */
  public int playDirection;
  /** C: sample_playback_guide.h:44 — null ⇒ this source isn't currently playing. */
  public SampleHolder audioFileHolder;

  // C: sample_playback_guide.h:52-53 — byte offsets relative to the audio-file start (uint32). End is
  // left of start when reversed.
  public int startPlaybackAtByte;
  public int endPlaybackAtByte;

  // C: sample_playback_guide.h:55-56 — sequence sync (0 length ⇒ no syncing).
  public int sequenceSyncStartedAtTick;
  public int sequenceSyncLengthTicks;

  /** C: sample_playback_guide.h:32 */
  public int getBytePosToStartPlayback(boolean justLooped) {
    return startPlaybackAtByte;
  }

  /** C: sample_playback_guide.h:33-35 — basis for a lot of the hop math. */
  public int getBytePosToEndOrLoopPlayback() {
    return endPlaybackAtByte;
  }

  /** C: sample_playback_guide.h:37 */
  public int getLoopStartPlaybackAtByte() {
    return startPlaybackAtByte;
  }

  /** C: sample_playback_guide.h:38 */
  public int getLoopEndPlaybackAtByte() {
    return endPlaybackAtByte;
  }

  /** C: sample_playback_guide.cpp:62-85 — compute start/end bytes from the holder's sample markers. */
  public void setupPlaybackBounds(boolean reversed) {
    playDirection = reversed ? -1 : 1;

    int startPlaybackAtSample;
    int endPlaybackAtSample;

    Sample sample = audioFileHolder.audioFile;
    int bytesPerSample = sample.numChannels * sample.byteDepth;

    if (!reversed) {
      startPlaybackAtSample = (int) audioFileHolder.startPos;
      endPlaybackAtSample = (int) audioFileHolder.getEndPos();
    } else {
      startPlaybackAtSample = (int) audioFileHolder.getEndPos() - 1;
      endPlaybackAtSample = (int) audioFileHolder.startPos - 1;
    }

    startPlaybackAtByte = sample.audioDataStartPosBytes + startPlaybackAtSample * bytesPerSample;
    endPlaybackAtByte = sample.audioDataStartPosBytes + endPlaybackAtSample * bytesPerSample;
  }
}
