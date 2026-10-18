package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AudioOutput} reverse playback bounds (C {@code
 * SamplePlaybackGuide::setupPlaybackBounds}, {@code sample_playback_guide.cpp:71-81}) and
 * time-stretched loop/bounds setup.
 */
class AudioOutputBoundsAndLoopTest {

  @BeforeEach
  void resetSeeds() {
    Functions.resetNoiseSeed();
  }

  private static Sample makeMonoSample(int... frames) {
    Sample s = new Sample();
    s.numChannels = 1;
    s.byteDepth = 4;
    s.sampleRate = 44100;
    s.audioDataStartPosBytes = 0;
    s.lengthInSamples = frames.length;
    s.audioDataLengthBytes = (long) frames.length * s.byteDepth;
    s.data = frames.clone();
    return s;
  }

  @Test
  void reversePlaybackStartsOnLastFrameAndIncludesFrameZero() {
    // 4 frames with distinct amplitudes (1x, 2x, 3x, 4x).
    // C sample_playback_guide.cpp:78-81: reverse bounds for [0, 4) are start = 3, end = -1.
    Sample sample = makeMonoSample(100_000_000, 200_000_000, 300_000_000, 400_000_000);

    AudioOutput ao = new AudioOutput();
    ao.setReversed(true);
    ao.setClip(sample, true);
    ao.setAmplitude(1 << 27);
    ao.onTransportStart();

    int numFrames = 8;
    int[] buf = new int[numFrames * 2];
    int[] rev = new int[numFrames];
    ao.renderInternal(buf, numFrames, rev);

    // Frame order must be [3, 2, 1, 0, 3, 2, 1, 0] — no leading zero at index 4 and no skipped
    // frame 0 before loop wrap.
    int f3 = buf[0];
    int f2 = buf[2];
    int f1 = buf[4];
    int f0 = buf[6];
    assertTrue(f3 > 0, "First reverse sample must be frame 3 (non-zero), got " + f3);
    assertTrue(f0 > 0, "Fourth reverse sample must be frame 0 (non-zero), got " + f0);
    assertTrue(f3 > f2 && f2 > f1 && f1 > f0, "Reverse frames must step strictly 3 -> 2 -> 1 -> 0");

    // Second loop iteration must match the first 1:1.
    assertEquals(f3, buf[8], "Frame 4 must wrap cleanly back to sample frame 3");
    assertEquals(f2, buf[10], "Frame 5 must match sample frame 2");
    assertEquals(f1, buf[12], "Frame 6 must match sample frame 1");
    assertEquals(f0, buf[14], "Frame 7 must match sample frame 0");
  }

  @Test
  void reversePlaybackRespectsLoopLengthSamples() {
    Sample sample =
        makeMonoSample(
            100_000_000, 200_000_000, 300_000_000, 400_000_000, 500_000_000, 600_000_000);

    AudioOutput ao = new AudioOutput();
    ao.setReversed(true);
    ao.setClip(sample, true);
    ao.setLoopLengthSamples(3); // musical length = first 3 frames [0, 3) -> reverse [2, -1)
    ao.setAmplitude(1 << 27);
    ao.onTransportStart();

    int numFrames = 6;
    int[] buf = new int[numFrames * 2];
    int[] rev = new int[numFrames];
    ao.renderInternal(buf, numFrames, rev);

    int f2 = buf[0];
    int f1 = buf[2];
    int f0 = buf[4];
    assertTrue(f2 > f1 && f1 > f0 && f0 > 0, "Must play frames 2 -> 1 -> 0 within loop length 3");
    assertEquals(f2, buf[6], "Must wrap back to frame 2 after frame 0");
    assertEquals(f1, buf[8], "Must continue with frame 1 after wrap");
    assertEquals(f0, buf[10], "Must continue with frame 0 after wrap");
  }

  @Test
  void timeStretchedAudioClipLoopsPastEndFrame() {
    int[] pcm = new int[512];
    for (int i = 0; i < pcm.length; i++) {
      pcm[i] = 200_000_000;
    }
    Sample sample = makeMonoSample(pcm);

    AudioOutput ao = new AudioOutput();
    ao.setClip(sample, true);
    ao.setPlayback(0.75f, true); // time-stretch active
    ao.setLoopLengthSamples(256);
    ao.setAmplitude(1 << 27);
    ao.onTransportStart();

    int block = 128;
    int[] buf = new int[block * 2];
    int[] rev = new int[block];
    long tailPeak = 0;
    // Render 2048 output frames (well past both the 256-frame loop length and 512-frame sample).
    for (int b = 0; b < 16; b++) {
      java.util.Arrays.fill(buf, 0);
      ao.renderInternal(buf, block, rev);
      if (b >= 12) {
        for (int i = 0; i < buf.length; i++) {
          tailPeak = Math.max(tailPeak, Math.abs((long) buf[i]));
        }
      }
    }
    assertTrue(
        tailPeak > 0,
        "Time-stretched looping AudioOutput must remain audible past endFrame, got tailPeak=0");
  }

  @Test
  void setPlayingArmsTimeStretcherWhenTimeStretchEnabled() {
    int[] pcm = new int[512];
    for (int i = 0; i < pcm.length; i++) {
      pcm[i] = 200_000_000;
    }
    Sample sample = makeMonoSample(pcm);

    AudioOutput ao = new AudioOutput();
    ao.setClip(sample, true);
    ao.setPlayback(0.75f, true);
    ao.setAmplitude(1 << 27);
    ao.setPlaying(true);

    int block = 128;
    int[] buf = new int[block * 2];
    int[] rev = new int[block];
    ao.renderInternal(buf, block, rev);

    long peak = 0;
    for (int s : buf) {
      peak = Math.max(peak, Math.abs((long) s));
    }
    assertTrue(peak > 0, "setPlaying(true) with timeStretch enabled must produce audio");
  }
}
