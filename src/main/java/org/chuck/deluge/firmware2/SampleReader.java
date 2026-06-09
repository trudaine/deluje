package org.chuck.deluge.firmware2;

/**
 * In-RAM resampling reader — the desktop replacement for the cluster-streaming
 * {@code SampleLowLevelReader} (Phase A/B of docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md).
 *
 * <p>The C walks a {@code char* currentPlayPos} through SD {@code Cluster}s and feeds the interpolator
 * the top-16-bits of each sample. Here {@code currentPlayPos} becomes a frame index into
 * {@link Sample#data} and the interpolation runs at FULL precision (Phase-A option (b), a sanctioned
 * deviation): the {@code oscPos}/jump-forward structure and the amplitude ramp are ported faithfully
 * from {@code readSamplesResampled} (sample_low_level_reader.cpp:943-1110); only the sample word fed to
 * the interpolator is full-precision int instead of int16 (see {@link SincInterpolator#interpolateWide}).
 *
 * <p>Scope: forward + reverse resampled playback of an in-RAM sample, no cluster boundaries, no
 * cache/condensing. Loop/jump-back/boundary handling is deferred to later increments.
 */
public class SampleReader {

  static final int K_TAPS = SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES; // 16

  public Sample sample;
  public int playDirection = 1; // +1 forward, -1 reverse (C: guide->playDirection)

  /** C: oscPos — sub-sample phase, 24-bit fractional (0..2^24). */
  public int oscPos;
  /** Frame index into {@link Sample#data} (replaces the C {@code currentPlayPos} byte pointer). */
  public int playPos;
  /** C: *doneAnySamplesYet — the first output uses the primed history without advancing. */
  boolean doneAnySamplesYet;

  // Full-precision interpolation history; [0] is the newest frame (matches SincInterpolator order).
  final int[] bufL = new int[K_TAPS];
  final int[] bufR = new int[K_TAPS];

  private int numFrames() {
    return sample.data.length / sample.numChannels;
  }

  /** Read the frame at {@link #playPos} (0 outside the sample), shift it into the history, advance. */
  private void pushFrame() {
    for (int i = K_TAPS - 1; i >= 1; i--) {
      bufL[i] = bufL[i - 1];
      bufR[i] = bufR[i - 1];
    }
    if (playPos >= 0 && playPos < numFrames()) {
      int base = playPos * sample.numChannels;
      bufL[0] = sample.data[base];
      bufR[0] = (sample.numChannels == 2) ? sample.data[base + 1] : 0;
    } else {
      bufL[0] = 0;
      bufR[0] = 0;
    }
    playPos += playDirection;
  }

  /**
   * Prime the interpolation history so {@code buf[0]} is {@code startFrame} and the read position sits
   * just after it. Adapter-level start (the C primes via the cluster fill path).
   */
  public void init(int startFrame) {
    playPos = startFrame - (K_TAPS - 1) * playDirection;
    for (int i = 0; i < K_TAPS; i++) {
      pushFrame();
    }
    oscPos = 0;
    doneAnySamplesYet = false;
  }

  /**
   * C: readSamplesResampled (sample_low_level_reader.cpp:943-1110), forward/reverse, full-precision (b).
   * Accumulates {@code numSamples} interpolated, amplitude-ramped samples into {@code oscBuffer}
   * (interleaved when {@code numChannels == 2}).
   *
   * @param amplitude single-element {l} ramp accumulator (C: {@code *amplitude})
   */
  public void readResampled(int[] oscBuffer, int numSamples, int numChannels, int whichKernel,
                            int phaseIncrement, int[] amplitude, int amplitudeIncrement) {
    int o = 0;
    for (int s = 0; s < numSamples; s++) {
      if (!doneAnySamplesYet) {
        doneAnySamplesYet = true; // C: goto skipFirstSmooth — no advance on the first sample
      } else {
        oscPos += phaseIncrement;            // C:975
        int numSamplesToJumpForward = oscPos >>> 24; // C:976
        if (numSamplesToJumpForward != 0) {
          oscPos &= 16777215; // C:978
          // C:982-985 — only the last kInterpolationMaxNumSamples matter for the kernel.
          if (numSamplesToJumpForward > K_TAPS) {
            playPos += (numSamplesToJumpForward - K_TAPS) * playDirection;
            numSamplesToJumpForward = K_TAPS;
          }
          for (int k = 0; k < numSamplesToJumpForward; k++) {
            pushFrame();
          }
        }
      }

      int[] sampleRead = SincInterpolator.interpolateWide(bufL, bufR, numChannels, whichKernel, oscPos); // C:1024

      amplitude[0] += amplitudeIncrement; // C:1048
      oscBuffer[o] = Functions.multiply_accumulate_32x32_rshift32_rounded(oscBuffer[o], sampleRead[0], amplitude[0]); // C:1051
      o++;
      if (numChannels == 2) {
        oscBuffer[o] =
            Functions.multiply_accumulate_32x32_rshift32_rounded(oscBuffer[o], sampleRead[1], amplitude[0]);
        o++;
      }
    }
  }
}
