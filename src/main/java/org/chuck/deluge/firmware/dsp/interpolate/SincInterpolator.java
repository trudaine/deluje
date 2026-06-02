package org.chuck.deluge.firmware.dsp.interpolate;

/**
 * Windowed-sinc sample interpolation, adapted from DelugeFirmware dsp/interpolate/interpolate.cpp
 * and util/functions.cpp::getWhichKernel. The Deluge uses a 16-tap windowed-sinc with one of 7
 * anti-aliasing kernels selected by playback rate (sharper kernels resist aliasing as the sample is
 * pitched up); the firmware default sample interpolation is sinc, not linear. This operates on the
 * float sample pipeline used by {@link org.chuck.deluge.firmware.engine.VoiceSample}.
 *
 * <p>Kernel[k][phase][tap] sums to ~1.0 (DC gain 1) at each phase, so output stays in the input
 * scale. Phase 0 is an impulse at tap 8, so a phase-aligned read returns the sample unchanged.
 */
public final class SincInterpolator {
  private SincInterpolator() {}

  /** Q24 phase-increment thresholds → kernel index (getWhichKernel). 16777216 == 1.0 (no pitch). */
  public static int getWhichKernel(int phaseIncrement) {
    if (phaseIncrement < 17268826) {
      return 0; // up to half a semitone up: sharpest kernel
    }
    int whichKernel = 1;
    while (phaseIncrement >= 32599202) { // ~11.5 semitones up
      phaseIncrement >>= 1;
      whichKernel += 2;
      if (whichKernel == 5) break;
    }
    if (phaseIncrement >= 23051117) { // ~5.5 semitones up
      whichKernel++;
    }
    return whichKernel;
  }

  /**
   * Interpolate one channel at {@code intPos + frac32/2^32} using the windowed-sinc kernel.
   *
   * @param data interleaved float sample data
   * @param numChannels channel stride
   * @param channel channel index (0 = L, 1 = R)
   * @param intPos integer sample index
   * @param frac32 fractional position as an unsigned 32-bit value (0 .. 2^32)
   * @param whichKernel kernel index from {@link #getWhichKernel}
   */
  public static float interpolate(
      float[] data, int numChannels, int channel, int intPos, long frac32, int whichKernel) {
    // oscPos in Q24 (firmware uses 24-bit sub-sample phase); progressSmall picks the 17-phase row,
    // strength2 (Q15) linearly interpolates between adjacent phase rows.
    long oscPos = (frac32 & 0xFFFFFFFFL) >>> 8; // 0 .. 2^24
    int progressSmall = (int) (oscPos >>> 20); // 0 .. 16
    if (progressSmall > 15) progressSmall = 15; // guard the [progressSmall+1] access
    float strength2 = (float) ((oscPos >>> 5) & 0x7FFF) / 32768.0f;

    short[] row0 = WindowedSincKernel.KERNEL[whichKernel][progressSmall];
    short[] row1 = WindowedSincKernel.KERNEL[whichKernel][progressSmall + 1];

    int maxIndex = data.length - 1;
    float out = 0.0f;
    for (int i = 0; i < WindowedSincKernel.NUM_TAPS; i++) {
      float kv = (row0[i] + (row1[i] - row0[i]) * strength2) / 32768.0f;
      int idx = (intPos + 8 - i) * numChannels + channel;
      if (idx < 0) idx = channel;
      else if (idx > maxIndex) idx = maxIndex;
      out += kv * data[idx];
    }
    return out;
  }
}
