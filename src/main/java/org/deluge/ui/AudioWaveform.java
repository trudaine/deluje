package org.deluge.ui;

/**
 * Pure audio-view projection: reduces an (unbounded-length) mono sample buffer to a fixed-size
 * amplitude envelope for the waveform overlay drawn across an audio track's row.
 *
 * <p>This is the audio analog of the grid projectors: a variable/unbounded model (the sample
 * buffer, arbitrarily long) mapped onto a fixed-size visual (a constant number of envelope points
 * spread across the visible pad columns), so the length "on the right" is absorbed by decimation,
 * never by growing the output. Separated from the file-decode I/O in {@code
 * SwingGridPanel#getCachedWaveform} so the decimation/smoothing is pure and unit-testable.
 */
final class AudioWaveform {
  private AudioWaveform() {}

  /** Points per row the audio overlay decimates to (was inlined as a literal in the renderer). */
  static final int DEFAULT_POINTS = 256;

  /**
   * Decimates {@code samples} to a {@code targetPoints}-long absolute-amplitude envelope, then
   * applies a 5-tap box smoothing — faithful to the former inline waveform reduction. Point-samples
   * every {@code max(1, samples.length / targetPoints)}-th sample (not peak), matching the
   * original. Points past the end of the buffer stay 0.
   *
   * @param samples normalized mono samples in roughly [-1, 1] (sign ignored — abs is taken)
   * @param targetPoints number of envelope points to produce (e.g. {@link #DEFAULT_POINTS})
   * @return a {@code targetPoints}-long smoothed absolute-amplitude envelope
   */
  static float[] envelope(float[] samples, int targetPoints) {
    float[] points = new float[targetPoints];
    if (samples != null && samples.length > 0) {
      int step = Math.max(1, samples.length / targetPoints);
      for (int i = 0; i < targetPoints; i++) {
        int idx = i * step;
        if (idx >= samples.length) break; // remaining points stay 0
        points[i] = Math.abs(samples[idx]);
      }
    }
    float[] smoothed = new float[targetPoints];
    for (int i = 0; i < targetPoints; i++) {
      float sum = 0;
      int count = 0;
      for (int w = -2; w <= 2; w++) {
        int idx = i + w;
        if (idx >= 0 && idx < targetPoints) {
          sum += points[idx];
          count++;
        }
      }
      smoothed[i] = sum / count;
    }
    return smoothed;
  }
}
