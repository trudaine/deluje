package org.deluge.engine;

/**
 * Pure helpers for audio-track recording (Phase 4 part 2). The live capture is device-dependent and
 * threshold-triggered (see {@link AudioInputCaptureLine}); these are the testable computational
 * pieces it relies on: overdub mixdown and transport-synced capture length.
 */
public final class AudioRecordingUtil {
  private AudioRecordingUtil() {}

  /**
   * Overdub mix of two little-endian signed-16-bit PCM streams (interleaved, any channel count)
   * with saturation. The result is the length of the longer input; where one runs out, its
   * contribution is silence (so a short overdub layers onto a longer base, or vice-versa).
   */
  public static byte[] mixPcm16LE(byte[] base, byte[] overdub) {
    int n = Math.max(base.length, overdub.length) & ~1; // whole 16-bit samples
    byte[] out = new byte[n];
    for (int i = 0; i + 1 < n; i += 2) {
      int a = (i + 1 < base.length) ? (short) ((base[i + 1] << 8) | (base[i] & 0xff)) : 0;
      int b = (i + 1 < overdub.length) ? (short) ((overdub[i + 1] << 8) | (overdub[i] & 0xff)) : 0;
      int s = a + b;
      if (s > 32767) s = 32767;
      else if (s < -32768) s = -32768;
      out[i] = (byte) (s & 0xff);
      out[i + 1] = (byte) ((s >> 8) & 0xff);
    }
    return out;
  }

  /**
   * Exact number of frames to capture for a transport-synced loop of {@code bars} bars (4/4) at the
   * given tempo. One bar = 4 beats = 240/bpm seconds, so frames = bars * sampleRate * 240 / bpm.
   */
  public static long syncedCaptureFrames(int bars, double bpm, int sampleRate) {
    if (bars <= 0 || bpm <= 0 || sampleRate <= 0) return 0;
    return Math.round(bars * (double) sampleRate * 240.0 / bpm);
  }
}
