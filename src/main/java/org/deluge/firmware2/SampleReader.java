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

  /**
   * Directional playback bounds (C SampleLowLevelReader / SamplePlaybackGuide). When {@link
   * #hasBounds} is true, {@link #pushFrame()} and {@link #readNative} wrap at {@link #endFrame}
   * back to {@link #loopStartFrame} when {@link #looping}, or inject zeroes beyond {@link
   * #endFrame} when not looping (sample_low_level_reader.cpp:402-434, 489, 680-699).
   */
  public boolean hasBounds;

  public int endFrame;
  public boolean looping;
  public int loopStartFrame;

  /**
   * True once playback has wrapped at {@link #endFrame} back to {@link #loopStartFrame}, so
   * backward cursor realignment on interpolation-mode changes wraps back into the loop instead of
   * rewinding into the pre-loop attack.
   */
  public boolean hasWrappedLoop;

  // Full-precision interpolation history; [0] is the newest frame (matches SincInterpolator order).
  final int[] bufL = new int[K_TAPS];
  final int[] bufR = new int[K_TAPS];

  // Loop-aware frame index history matching bufL/bufR ([0] is the frame index of bufL[0]).
  final int[] posHistory = new int[K_TAPS];
  private int posHistoryHead = Integer.MIN_VALUE;

  private final int[] interpOut = new int[2];

  private int numFrames() {
    return sample.data.length / sample.numChannels;
  }

  public void setBounds(int endFrame, boolean looping, int loopStartFrame) {
    this.hasBounds = true;
    this.endFrame = endFrame;
    this.looping = looping;
    this.loopStartFrame = loopStartFrame;
    if (!looping) {
      this.hasWrappedLoop = false;
    }
    wrapPlayPosIfNeeded();
  }

  public void clearBounds() {
    this.hasBounds = false;
    this.looping = false;
    this.hasWrappedLoop = false;
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
    this.hasBounds = other.hasBounds;
    this.endFrame = other.endFrame;
    this.looping = other.looping;
    this.loopStartFrame = other.loopStartFrame;
    this.hasWrappedLoop = other.hasWrappedLoop;
    this.posHistoryHead = other.posHistoryHead;
    System.arraycopy(other.bufL, 0, bufL, 0, K_TAPS);
    System.arraycopy(other.bufR, 0, bufR, 0, K_TAPS);
    System.arraycopy(other.posHistory, 0, posHistory, 0, K_TAPS);
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

  // C sample_low_level_reader.cpp:402-434 — normalize playPos across loop boundaries in O(1),
  // preserving multi-period overshoot across very short loops or fast pitch shifts.
  private void wrapPlayPosIfNeeded() {
    if (!hasBounds || !looping) {
      return;
    }
    int loopSpan = endFrame - loopStartFrame;
    if (loopSpan * playDirection <= 0) {
      return;
    }
    if ((playPos - endFrame) * playDirection >= 0) {
      long overshoot = (long) (playPos - loopStartFrame) * playDirection;
      long span = (long) loopSpan * playDirection;
      playPos = loopStartFrame + (int) (overshoot % span) * playDirection;
      hasWrappedLoop = true;
    }
  }

  /**
   * Rewind {@link #playPos} by {@code count} frames along the actual playback trajectory (using
   * {@link #posHistory} when valid, or wrapping backwards around {@code [loopStartFrame, endFrame)}
   * if a loop has already wrapped). Initial pre-loop attack stays unwrapped.
   */
  private void jumpBackFrames(int count) {
    if (count <= 0) {
      return;
    }
    if (playPos == posHistoryHead && count <= K_TAPS) {
      playPos = posHistory[count - 1];
      return;
    }
    playPos -= count * playDirection;
    if (hasBounds && looping && hasWrappedLoop) {
      int loopSpan = endFrame - loopStartFrame;
      if (loopSpan * playDirection > 0 && (playPos - loopStartFrame) * playDirection < 0) {
        long underflow = (long) (loopStartFrame - playPos) * playDirection;
        long span = (long) loopSpan * playDirection;
        long rem = underflow % span;
        playPos = (rem == 0) ? loopStartFrame : (endFrame - (int) rem * playDirection);
        return;
      }
    }
    wrapPlayPosIfNeeded();
  }

  private boolean isPastNonLoopingEnd() {
    return hasBounds && !looping && (playPos - endFrame) * playDirection >= 0;
  }

  private void readFrameAtPlayPos(int[] dstL, int[] dstR, int idx) {
    wrapPlayPosIfNeeded();
    posHistory[idx] = playPos;
    if (!isPastNonLoopingEnd() && playPos >= 0 && playPos < numFrames()) {
      int base = playPos * sample.numChannels;
      dstL[idx] = sample.data[base];
      dstR[idx] = (sample.numChannels == 2) ? sample.data[base + 1] : sample.data[base];
    } else {
      dstL[idx] = 0;
      dstR[idx] = 0;
    }
    playPos += playDirection;
    wrapPlayPosIfNeeded();
    posHistoryHead = playPos;
  }

  /**
   * Read the frame at {@link #playPos} (0 outside the sample or past a non-looping trim end), shift
   * it into the history, advance.
   */
  private void pushFrame() {
    System.arraycopy(bufL, 0, bufL, 1, K_TAPS - 1);
    System.arraycopy(bufR, 0, bufR, 1, K_TAPS - 1);
    System.arraycopy(posHistory, 0, posHistory, 1, K_TAPS - 1);
    readFrameAtPlayPos(bufL, bufR, 0);
  }

  /**
   * Prime the interpolation history with the {@value #K_TAPS} frames BEFORE {@code startFrame},
   * leaving {@link #playPos} at {@code startFrame} — so the native and resampled paths start
   * consistently (native reads {@code startFrame} first; resampled has real context, no zero
   * transient). C fillInterpolationBufferRetrospectively (sample_low_level_reader.cpp:446-481)
   * reads backwards through the audio file without loop-wrapping.
   */
  public void init(int startFrame) {
    hasWrappedLoop = false;
    boolean savedHasBounds = hasBounds;
    hasBounds = false;
    playPos = startFrame - K_TAPS * playDirection;
    for (int i = 0; i < K_TAPS; i++) {
      pushFrame();
    }
    hasBounds = savedHasBounds;
    // playPos is now startFrame; buf[0] = the frame just before it.
    oscPos = 0;
    doneAnySamplesYet = false;
    interpolationBufferSizeLastTime = 0; // C: setupNewPlayHead (time_stretcher.cpp:997)
  }

  /**
   * Loop wrap (C sample_low_level_reader.cpp:404-433): jump the read position back by the loop
   * length, PRESERVING the frame overshoot past the loop end, the 24-bit {@code oscPos} fraction,
   * and the interpolation history — the seam renders continuously (no re-init, no re-prime).
   */
  public void wrapBy(int loopLengthFrames) {
    playPos -= loopLengthFrames;
    hasWrappedLoop = true;
    wrapPlayPosIfNeeded();
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
      int interpolationBufferSize,
      int[] amplitude,
      int amplitudeIncrement) {
    // C sample_low_level_reader.cpp:553-659 — prime or resize the engaged interpolation buffer.
    if (interpolationBufferSizeLastTime == 0) {
      int primeCount = (interpolationBufferSize >> 1) + 1;
      for (int i = 0; i < primeCount; i++) {
        pushFrame();
      }
      interpolationBufferSizeLastTime = interpolationBufferSize;
    } else if (interpolationBufferSizeLastTime != interpolationBufferSize) {
      if (interpolationBufferSize < interpolationBufferSizeLastTime) {
        // C:582-602 — shrink buffer (e.g. 16-tap sinc -> 2-tap linear).
        int offset = (interpolationBufferSizeLastTime - interpolationBufferSize) >> 1;
        jumpBackFrames(offset);
        for (int i = 0; i < interpolationBufferSize; i++) {
          bufL[i] = bufL[i + offset];
          bufR[i] = bufR[i + offset];
          posHistory[i] = posHistory[i + offset];
        }
        posHistoryHead = playPos;
      } else {
        // C:614-647 — expand buffer (e.g. 2-tap linear -> 16-tap sinc).
        int offset = (interpolationBufferSize - interpolationBufferSizeLastTime) >> 1;
        for (int i = interpolationBufferSizeLastTime - 1; i >= 0; i--) {
          bufL[i + offset] = bufL[i];
          bufR[i + offset] = bufR[i];
          posHistory[i + offset] = posHistory[i];
        }
        for (int i = offset - 1; i >= 0; i--) {
          readFrameAtPlayPos(bufL, bufR, i);
        }
        for (int i = interpolationBufferSize - offset; i < interpolationBufferSize; i++) {
          bufL[i] = 0;
          bufR[i] = 0;
        }
      }
      interpolationBufferSizeLastTime = interpolationBufferSize;
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
          // C:671-675 — only the last interpolationBufferSize samples matter for the engaged
          // kernel.
          if (numSamplesToJumpForward > interpolationBufferSize) {
            playPos += (numSamplesToJumpForward - interpolationBufferSize) * playDirection;
            wrapPlayPosIfNeeded();
            numSamplesToJumpForward = interpolationBufferSize;
          }
          for (int k = 0; k < numSamplesToJumpForward; k++) {
            pushFrame();
          }
        }
      }

      if (interpolationBufferSize > 2) {
        // Faithful 24-tap windowed-sinc (interpolate.cpp:9; sample_low_level_reader.cpp:1024). Used
        // whenever the engine has headroom (cpuDireness 0), plus all offline export / fidelity
        // tests.
        SincInterpolator.interpolateWide(
            bufL, bufR, sample.numChannels, whichKernel, oscPos, interpOut); // C:1024
      } else {
        // 2-tap linear (Interpolator::interpolateLinear, interpolate.cpp:70 — strength2 = phase >>
        // 9),
        // selected at sample_low_level_reader.cpp:1081 when getInterpolationBufferSize() == 2 — the
        // CPU-direness fallback for pitched-up samples under load (sample_controls.cpp:29).
        // C: strength1 = int16_max - strength2 (interpolate.cpp:73); the C's int16-sample
        // products land at ~sample32 >> 1, the same scale as the sinc path — the full-precision
        // history here needs >> 16 to match (>> 15 made the linear fallback +6 dB vs sinc).
        int strength2 = (oscPos >>> 9) & 0x7FFF;
        int strength1 = 32767 - strength2;
        interpOut[0] = (int) (((long) bufL[1] * strength1 + (long) bufL[0] * strength2) >> 16);
        if (sample.numChannels == 2) {
          interpOut[1] = (int) (((long) bufR[1] * strength1 + (long) bufR[0] * strength2) >> 16);
        }
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
    interpolationBufferSizeLastTime = interpolationBufferSize; // C:554/658 — engaged tap count
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
    // C sample_low_level_reader.cpp:824-835 — "if we were interpolating last time": jump back by
    // half the interpolation buffer minus the fractional lead, so returning to native (e.g. a
    // pitch modulation crossing unity) doesn't drift ~half a buffer of source frames.
    if (interpolationBufferSizeLastTime != 0) {
      int numToJumpBack = (interpolationBufferSizeLastTime >> 1) - (oscPos >>> 23);
      jumpBackFrames(numToJumpBack);
      interpolationBufferSizeLastTime = 0;
      posHistoryHead = Integer.MIN_VALUE;
      oscPos = 0;
    }
    int o = 0;
    int n = numFrames();
    for (int s = 0; s < numSamples; s++) {
      wrapPlayPosIfNeeded();
      int sampleReadL = 0;
      int sampleReadR = 0;
      if (!isPastNonLoopingEnd() && playPos >= 0 && playPos < n) {
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
      wrapPlayPosIfNeeded();

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
