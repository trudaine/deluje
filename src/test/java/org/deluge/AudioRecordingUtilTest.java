package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.AudioRecordingUtil;
import org.junit.jupiter.api.Test;

/** Phase 4 part 2: the testable core of audio-track overdub + transport-synced capture. */
public class AudioRecordingUtilTest {

  private static byte[] pcm(int... samples) {
    byte[] b = new byte[samples.length * 2];
    for (int i = 0; i < samples.length; i++) {
      b[i * 2] = (byte) (samples[i] & 0xff);
      b[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
    }
    return b;
  }

  private static int sampleAt(byte[] b, int i) {
    return (short) ((b[i * 2 + 1] << 8) | (b[i * 2] & 0xff));
  }

  @Test
  void overdubMixesAndSaturates() {
    byte[] base = pcm(10000, -10000, 30000, -30000);
    byte[] over = pcm(5000, -5000, 30000, -30000);
    byte[] mix = AudioRecordingUtil.mixPcm16LE(base, over);
    assertEquals(15000, sampleAt(mix, 0));
    assertEquals(-15000, sampleAt(mix, 1));
    assertEquals(32767, sampleAt(mix, 2)); // saturates +
    assertEquals(-32768, sampleAt(mix, 3)); // saturates -
  }

  @Test
  void overdubLayersOntoLongerBase() {
    byte[] base = pcm(100, 200, 300, 400);
    byte[] over = pcm(50, 60); // shorter
    byte[] mix = AudioRecordingUtil.mixPcm16LE(base, over);
    assertEquals(4, mix.length / 2, "result keeps the longer length");
    assertEquals(150, sampleAt(mix, 0));
    assertEquals(260, sampleAt(mix, 1));
    assertEquals(300, sampleAt(mix, 2)); // base only past the overdub end
    assertEquals(400, sampleAt(mix, 3));
  }

  @Test
  void syncedCaptureFramesMatchesBarsAtTempo() {
    // 1 bar @120bpm @44.1k = 2.0s = 88200 frames; 4 bars = 352800; 1 bar @60bpm = 176400.
    assertEquals(88200, AudioRecordingUtil.syncedCaptureFrames(1, 120.0, 44100));
    assertEquals(352800, AudioRecordingUtil.syncedCaptureFrames(4, 120.0, 44100));
    assertEquals(176400, AudioRecordingUtil.syncedCaptureFrames(1, 60.0, 44100));
    assertEquals(0, AudioRecordingUtil.syncedCaptureFrames(0, 120.0, 44100));
  }
}
