package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code processing/live/live_pitch_shifter_play_head.{cpp,h}}: one play head of
 * the live pitch shifter. Reads the {@link LiveInputBuffer} ring either repitched (windowed-sinc
 * via the int16 {@link SincInterpolator}) or directly (1:1). {@code INPUT_ENABLE_REPITCHED_BUFFER
 * == 0}, so the REPITCHED_BUFFER mode is omitted.
 */
public class LivePitchShifterPlayHead {

  static final int K_TAPS = SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES; // 16
  static final int RAW_MASK = LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1;
  static final int K_MAX_SAMPLE_VALUE = 16777216;

  public enum PlayHeadMode {
    REPITCHED_BUFFER,
    RAW_REPITCHING,
    RAW_DIRECT
  }

  public PlayHeadMode mode;
  public int rawBufferReadPos;
  public int oscPos; // uint32
  public int percPos; // uint32
  public final SincInterpolator interpolator = new SincInterpolator();

  /**
   * C: render (live_pitch_shifter_play_head.cpp:31-120) — accumulate into {@code outputBuffer}
   * (interleaved), reading the live {@code rawBuffer} repitched or direct.
   */
  public void render(
      int[] outputBuffer,
      int numSamples,
      int numChannels,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement,
      int[] rawBuffer,
      int whichKernel,
      int interpolationBufferSize) {
    int o = 0;
    int end = numSamples * numChannels;

    if (mode == PlayHeadMode.RAW_REPITCHING) { // C:59-104
      do {
        oscPos += phaseIncrement; // C:62
        int numSamplesToJumpForward = oscPos >>> 24;
        if (numSamplesToJumpForward != 0) {
          oscPos &= 16777215;
          if (numSamplesToJumpForward > K_TAPS) { // C:69-76
            rawBufferReadPos = (rawBufferReadPos + (numSamplesToJumpForward - K_TAPS)) & RAW_MASK;
            numSamplesToJumpForward = K_TAPS;
          }
          interpolator.jumpForward(numSamplesToJumpForward); // C:78
          while (numSamplesToJumpForward-- > 0) { // C:80-87
            interpolator.bufferL[numSamplesToJumpForward] =
                (short) (rawBuffer[rawBufferReadPos * numChannels] >> 16);
            if (numChannels == 2) {
              interpolator.bufferR[numSamplesToJumpForward] =
                  (short) (rawBuffer[rawBufferReadPos * 2 + 1] >> 16);
            }
            rawBufferReadPos = (rawBufferReadPos + 1) & RAW_MASK;
          }
        }

        amplitude += amplitudeIncrement; // C:90
        int[] sampleRead =
            (interpolationBufferSize > 2)
                ? interpolator.interpolate(numChannels, whichKernel, oscPos)
                : interpolator.interpolateLinear(numChannels, oscPos); // C:92-94
        outputBuffer[o++] +=
            Functions.multiply_32x32_rshift32_rounded(sampleRead[0], amplitude) << 5; // C:96
        if (numChannels == 2) {
          outputBuffer[o++] +=
              Functions.multiply_32x32_rshift32_rounded(sampleRead[1], amplitude) << 5;
        }
      } while (o != end);
    } else { // RAW_DIRECT (C:106-119)
      do {
        amplitude += amplitudeIncrement;
        outputBuffer[o++] +=
            Functions.multiply_32x32_rshift32_rounded(
                    rawBuffer[rawBufferReadPos * numChannels], amplitude)
                << 4;
        if (numChannels == 2) {
          outputBuffer[o++] +=
              Functions.multiply_32x32_rshift32_rounded(
                      rawBuffer[rawBufferReadPos * 2 + 1], amplitude)
                  << 4;
        }
        rawBufferReadPos = (rawBufferReadPos + 1) & RAW_MASK;
      } while (o != end);
    }
  }

  /**
   * C: getEstimatedPlaytimeRemaining (cpp:124-150) — raw samples this head can still play before it
   * catches up to "now". Only meaningful for {@code phaseIncrement > kMaxSampleValue}.
   */
  public int getEstimatedPlaytimeRemaining(LiveInputBuffer liveInputBuffer, int phaseIncrement) {
    long howFarBack;
    if (mode == PlayHeadMode.RAW_REPITCHING) {
      long howFarBackRaw = (liveInputBuffer.numRawSamplesProcessed - rawBufferReadPos) & RAW_MASK;
      howFarBack = (howFarBackRaw << 24) / (phaseIncrement & 0xFFFFFFFFL);
    } else { // DIRECT
      return 2147483647;
    }
    long estimate = (howFarBack << 24) / ((phaseIncrement - K_MAX_SAMPLE_VALUE) & 0xFFFFFFFFL);
    return (estimate >= 2147483647L) ? 2147483647 : (int) estimate;
  }

  /** C: getNumRawSamplesBehindInput (cpp:152-170). */
  public int getNumRawSamplesBehindInput(LiveInputBuffer liveInputBuffer, int phaseIncrement) {
    if (mode == PlayHeadMode.RAW_REPITCHING) {
      return (liveInputBuffer.numRawSamplesProcessed - rawBufferReadPos) & RAW_MASK;
    }
    return 0; // DIRECT
  }

  /**
   * C: fillInterpolationBuffer (cpp:173-192) — prime the interpolator history from the ring,
   * looking back.
   */
  public void fillInterpolationBuffer(LiveInputBuffer liveInputBuffer, int numChannels) {
    for (int i = 1; i <= K_TAPS; i++) {
      int pos = (rawBufferReadPos - i) & RAW_MASK;
      interpolator.bufferL[i - 1] =
          (short)
              (Integer.compareUnsigned(pos, liveInputBuffer.numRawSamplesProcessed) < 0
                  ? liveInputBuffer.rawBuffer[pos * numChannels] >> 16
                  : 0);
    }
    if (numChannels > 1) {
      for (int i = 1; i <= K_TAPS; i++) {
        int pos = (rawBufferReadPos - i) & RAW_MASK;
        interpolator.bufferR[i - 1] =
            (short)
                (Integer.compareUnsigned(pos, liveInputBuffer.numRawSamplesProcessed) < 0
                    ? liveInputBuffer.rawBuffer[pos * numChannels + 1] >> 16
                    : 0);
      }
    }
  }
}
