package org.deluge.firmware2;

/**
 * In-RAM resampling reader — the desktop replacement for the cluster-streaming {@code
 * SampleLowLevelReader} (Phase A/B of docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md).
 *
 * <p>The C walks a {@code char* currentPlayPos} through SD {@code Cluster}s and feeds the
 * interpolator the top-16-bits of each sample. Here {@code currentPlayPos} becomes a frame index
 * into {@link Sample#data} and the interpolation runs at FULL precision (Phase-A option (b), a
 * sanctioned deviation): the {@code oscPos}/jump-forward structure and the amplitude ramp are
 * ported faithfully from {@code readSamplesResampled} (sample_low_level_reader.cpp:943-1110); only
 * the sample word fed to the interpolator is full-precision int instead of int16 (see {@link
 * SincInterpolator#interpolateWide}).
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

  /** C: interpolationBufferSizeLastTime — sinc taps engaged on the last read (0 = native/none). */
  int interpolationBufferSizeLastTime;

  // Full-precision interpolation history; [0] is the newest frame (matches SincInterpolator order).
  final int[] bufL = new int[K_TAPS];
  final int[] bufR = new int[K_TAPS];

  private final int[] interpOut = new int[2];

  private int numFrames() {
    return sample.data.length / sample.numChannels;
  }

  /**
   * Capture another reader's full play state (C: SampleLowLevelReader copy — used to fork the older
   * head).
   */
  public void copyStateFrom(SampleReader other) {
    this.sample = other.sample;
    this.playDirection = other.playDirection;
    this.oscPos = other.oscPos;
    this.playPos = other.playPos;
    this.doneAnySamplesYet = other.doneAnySamplesYet;
    this.interpolationBufferSizeLastTime = other.interpolationBufferSizeLastTime;
    System.arraycopy(other.bufL, 0, bufL, 0, K_TAPS);
    System.arraycopy(other.bufR, 0, bufR, 0, K_TAPS);
  }

  /**
   * C: getPlayByteLowLevel (sample_low_level_reader.cpp:42-58) — the reader's byte position within
   * the audio file, optionally compensated for the interpolation buffer (shift back by half the
   * engaged taps, in the play direction). In-RAM: the play byte is {@code audioDataStart +
   * playPos*bytesPerSample} (no cluster misalignment).
   */
  public int getPlayByteLowLevel(boolean compensateForInterpolationBuffer) {
    int bytesPerSample = sample.byteDepth * sample.numChannels;
    int withinAudioData = playPos * bytesPerSample;
    if (compensateForInterpolationBuffer && interpolationBufferSizeLastTime != 0) {
      int extraSamples = -(interpolationBufferSizeLastTime >> 1); // C:49
      withinAudioData += extraSamples * bytesPerSample * playDirection; // C:52
    }
    return sample.audioDataStartPosBytes + withinAudioData;
  }

  /**
   * Read the frame at {@link #playPos} (0 outside the sample), shift it into the history, advance.
   */
  private void pushFrame() {
    for (int i = K_TAPS - 1; i >= 1; i--) {
      bufL[i] = bufL[i - 1];
      bufR[i] = bufR[i - 1];
    }
    if (playPos >= 0 && playPos < numFrames()) {
      int base = playPos * sample.numChannels;
      bufL[0] = sample.data[base];
      bufR[0] = (sample.numChannels == 2) ? sample.data[base + 1] : sample.data[base];
    } else {
      bufL[0] = 0;
      bufR[0] = 0;
    }
    playPos += playDirection;
  }

  /**
   * Prime the interpolation history with the {@value #K_TAPS} frames BEFORE {@code startFrame},
   * leaving {@link #playPos} at {@code startFrame} — so the native and resampled paths start
   * consistently (native reads {@code startFrame} first; resampled has real context, no zero
   * transient). Adapter-level start; the C does a cluster-based retrospective fill.
   */
  public void init(int startFrame) {
    playPos = startFrame - K_TAPS * playDirection;
    for (int i = 0; i < K_TAPS; i++) {
      pushFrame();
    }
    // playPos is now startFrame; buf[0] = the frame just before it.
    oscPos = 0;
    doneAnySamplesYet = false;
    interpolationBufferSizeLastTime = 0; // C: setupNewPlayHead (time_stretcher.cpp:997)
  }

  /**
   * C: readSamplesResampled (sample_low_level_reader.cpp:943-1110), forward/reverse, full-precision
   * (b). Accumulates {@code numSamples} interpolated, amplitude-ramped samples into {@code
   * oscBuffer} (interleaved when {@code numChannels == 2}).
   *
   * @param amplitude single-element {l} ramp accumulator (C: {@code *amplitude})
   */
  public void readResampled(
      int[] oscBuffer,
      int numSamples,
      int numChannels,
      int whichKernel,
      int phaseIncrement,
      int[] amplitude,
      int amplitudeIncrement) {
    if (interpolationBufferSizeLastTime == 0) {
      for (int i = 0; i < 9; i++) {
        pushFrame();
      }
      interpolationBufferSizeLastTime = K_TAPS;
    }
    int o = 0;
    for (int s = 0; s < numSamples; s++) {
      if (!doneAnySamplesYet) {
        doneAnySamplesYet = true; // C: goto skipFirstSmooth — no advance on the first sample
      } else {
        oscPos += phaseIncrement; // C:975
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

      if (org.deluge.firmware.engine.FirmwareAudioEngine.realTimeMode) {
        // Fast Linear Interpolation (2 taps) - 12x CPU savings for real-time monitoring!
        int strength2 = (oscPos >>> 5) & 0x7FFF;
        int strength1 = 32768 - strength2;
        interpOut[0] = (int) (((long) bufL[1] * strength1 + (long) bufL[0] * strength2) >> 15);
        if (sample.numChannels == 2) {
          interpOut[1] = (int) (((long) bufR[1] * strength1 + (long) bufR[0] * strength2) >> 15);
        }
      } else {
        // Ultra-High-Fidelity Sinc Interpolation (24 taps) - mathematically perfect offline export
        SincInterpolator.interpolateWide(
            bufL, bufR, sample.numChannels, whichKernel, oscPos, interpOut); // C:1024
      }
      if (sample.numChannels == 2) {
        if (numChannels == 1) {
          // Condense stereo to mono
          interpOut[0] = ((interpOut[0] >> 1) + (interpOut[1] >> 1));
        }
      } else {
        // Mono sample: copy L to R for stereo output compatibility
        interpOut[1] = interpOut[0];
      }
      readResampledTail(oscBuffer, numChannels, amplitude, amplitudeIncrement, interpOut, o);
      o += numChannels;
    }
    interpolationBufferSizeLastTime = K_TAPS; // C:568/672 — windowed-sinc taps engaged
  }

  // shared C:1048-1052 tail (amplitude ramp + MAC), so readNative/readResampled stay identical
  // there
  private static void readResampledTail(
      int[] oscBuffer,
      int numChannels,
      int[] amplitude,
      int amplitudeIncrement,
      int[] sampleRead,
      int o) {
    amplitude[0] += amplitudeIncrement; // C:1048
    oscBuffer[o] =
        Functions.multiply_accumulate_32x32_rshift32_rounded(
            oscBuffer[o], sampleRead[0], amplitude[0]); // C:1051
    if (numChannels == 2) {
      oscBuffer[o + 1] =
          Functions.multiply_accumulate_32x32_rshift32_rounded(
              oscBuffer[o + 1], sampleRead[1], amplitude[0]);
    }
  }

  /**
   * C: readSamplesNative (sample_low_level_reader.cpp:1111-1159) — 1:1 native-rate read (no
   * resampling), used when {@code phaseIncrement == kMaxSampleValue}. Reads consecutive frames
   * directly (full precision; the C {@code & bitMask} is a no-op on clean decoded data),
   * amplitude-ramped.
   */
  public void readNative(
      int[] oscBuffer, int numSamples, int numChannels, int[] amplitude, int amplitudeIncrement) {
    int o = 0;
    int n = numFrames();
    for (int s = 0; s < numSamples; s++) {
      int sampleReadL = 0;
      int sampleReadR = 0;
      if (playPos >= 0 && playPos < n) {
        int base = playPos * sample.numChannels;
        sampleReadL = sample.data[base]; // C:1125
        if (sample.numChannels == 2) {
          sampleReadR = sample.data[base + 1]; // C:1133
          if (numChannels == 1) {
            // Condense stereo to mono
            sampleReadL = ((sampleReadL >> 1) + (sampleReadR >> 1));
          }
        } else {
          // Mono sample: copy L to R for stereo output compatibility
          sampleReadR = sampleReadL;
        }
      }
      playPos += playDirection; // C:1141 (jumpAmount = bytesPerSample * playDirection ⇒ ±1 frame)

      amplitude[0] += amplitudeIncrement; // C:1128
      oscBuffer[o] =
          Functions.multiply_accumulate_32x32_rshift32_rounded(
              oscBuffer[o], sampleReadL, amplitude[0]); // C:1144
      o++;
      if (numChannels == 2) {
        oscBuffer[o] =
            Functions.multiply_accumulate_32x32_rshift32_rounded(
                oscBuffer[o], sampleReadR, amplitude[0]); // C:1152
        o++;
      }
    }
    interpolationBufferSizeLastTime = 0; // C:832 — native read, no interpolation buffer
  }
}
