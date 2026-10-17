package org.deluge.firmware2;

/**
 * Faithful standalone port of {@code dsp/delay/delay_buffer.{cpp,h}} (191 + 309 lines): the
 * resampling ring buffer used by the delay engine AND the stutterer. The C uses {@code
 * StereoSample*} pointers; fw2 uses {@code int[][]} ({@code [l, r]} per frame), matching the fw2
 * DSP convention.
 *
 * <p>Extracted from the Delay inner class so Stutterer can also import it without depending on the
 * full delay engine — the last blocker to deleting {@code firmware/dsp/delay/}.
 */
public class DelayBuffer {

  public static final int K_MAX_SIZE = 88200; // C: delay_buffer.h:32
  public static final int K_MIN_SIZE = 1; // C:33
  public static final int K_NEUTRAL_SIZE = 16384; // C:34
  public static final int DELAY_SPACE_BETWEEN_READ_AND_WRITE = 20; // C:28
  public static final int K_MAX_SAMPLE_VALUE = 16777216; // 1 << 24

  // ── ResampleConfig (delay_buffer.h:289-295) ──

  static class ResampleConfig {
    int actualSpinRate; // 1 is represented as 16777216
    int spinRateForSpedUpWriting; // subject to some limits for safety
    int divideByRate; // 1 is represented as 65536
    int rateMultiple;
    int writeSizeAdjustment;
  }

  // ── State (delay_buffer.h:283-308) ──

  private int nativeRate;
  private int[][] buffer; // [l, r] per frame (C: StereoSample* start_)
  private int endOffset; // C: end_
  private int currentOffset; // C: current_
  public int sizeIncludingExtra; // C:286 (public in C for the stutterer)
  private int bufferSize; // C: size_

  public int longPos; // C:283 (uint32)
  public int lastShortPos; // C:284 (uint8)

  public ResampleConfig resampleConfig;

  public enum Error {
    NONE,
    UNSPECIFIED,
    INSUFFICIENT_RAM
  }

  // ── init (delay_buffer.cpp:26-56) ──

  /** C: delay_buffer.cpp:26-56 */
  public Error init(int rate, int failIfThisSize, boolean includeExtraSpace) {
    int[] sizeInfo = getIdealBufferSizeFromRate(rate);
    int newSize = sizeInfo[0];
    boolean makePrecise = sizeInfo[1] == 1;

    this.nativeRate = rate;
    this.bufferSize = newSize;

    if (this.bufferSize == failIfThisSize) {
      return Error.UNSPECIFIED; // C:37-39
    }

    if (makePrecise) {
      makeNativeRatePrecise(); // C:41-43
    }

    this.sizeIncludingExtra =
        this.bufferSize + (includeExtraSpace ? DELAY_SPACE_BETWEEN_READ_AND_WRITE : 0); // C:45

    try {
      this.buffer = new int[this.sizeIncludingExtra][2]; // C:47 — allocLowSpeed
    } catch (OutOfMemoryError e) {
      return Error.INSUFFICIENT_RAM; // C:49-51
    }

    endOffset = this.sizeIncludingExtra; // C:53
    clear(); // C:54
    return Error.NONE; // C:55
  }

  public Error init(int rate) {
    return init(rate, 0, true);
  }

  // ── clear (delay_buffer.cpp:58-62) ──

  /** C: delay_buffer.cpp:58-62 */
  public void clear() {
    for (int i = 0;
        i < DELAY_SPACE_BETWEEN_READ_AND_WRITE + 2;
        i++) { // C:59 — memset first N slots
      buffer[i][0] = 0;
      buffer[i][1] = 0;
    }
    currentOffset = DELAY_SPACE_BETWEEN_READ_AND_WRITE; // C:60
    resampleConfig = null; // C:61
  }

  // ── getIdealBufferSizeFromRate (delay_buffer.cpp:64-80) ──

  /** C: delay_buffer.cpp:64-80 */
  public static int[] getIdealBufferSizeFromRate(int newRate) {
    long bufferSz = (long) K_NEUTRAL_SIZE * K_MAX_SAMPLE_VALUE / (newRate & 0xFFFFFFFFL); // C:65
    boolean clamped = false;

    if (bufferSz > K_MAX_SIZE) { // C:69-72
      bufferSz = K_MAX_SIZE;
      clamped = true;
    }
    if (bufferSz < K_MIN_SIZE) { // C:74-77
      bufferSz = K_MIN_SIZE;
      clamped = true;
    }
    return new int[] {(int) bufferSz, clamped ? 1 : 0};
  }

  // ── rate precision (delay_buffer.cpp:82-90) ──

  /** C: delay_buffer.cpp:82-84 */
  public void makeNativeRatePrecise() {
    this.nativeRate =
        (int)
            Math.round(
                (double) K_NEUTRAL_SIZE * (double) K_MAX_SAMPLE_VALUE / (double) this.bufferSize);
  }

  /** C: delay_buffer.cpp:86-90 */
  public void makeNativeRatePreciseRelativeToOtherBuffer(DelayBuffer otherBuffer) {
    double otherBufferAmountTooFast =
        (double) otherBuffer.nativeRate
            * (double) otherBuffer.bufferSize
            / ((double) K_NEUTRAL_SIZE * (double) K_MAX_SAMPLE_VALUE);
    this.nativeRate =
        (int)
            Math.round(
                (double) K_NEUTRAL_SIZE
                    * (double) K_MAX_SAMPLE_VALUE
                    * otherBufferAmountTooFast
                    / (double) this.bufferSize);
  }

  // ── discard / invalidate / isActive (delay_buffer.cpp:92-97, h:42,80) ──

  /** C: delay_buffer.cpp:92-97 */
  public void discard() {
    if (buffer != null) {
      buffer = null; // C:94 — delugeDealloc
    }
  }

  /** C: delay_buffer.h:42 */
  public void invalidate() {
    buffer = null;
  }

  /** C: delay_buffer.h:80 */
  public boolean isActive() {
    return buffer != null;
  }

  /** C: delay_buffer.h:267 */
  public boolean isNative() {
    return resampleConfig == null;
  }

  public boolean resampling() {
    return resampleConfig != null;
  }

  public int nativeRate() {
    return nativeRate;
  }

  /** C: delay_buffer.h:276 */
  public int size() {
    return bufferSize;
  }

  // ── pointer ops (delay_buffer.h:82-106,108-115,117-125,272-279) ──

  /** C: delay_buffer.h:272 */
  public int[] current() {
    return buffer[currentOffset];
  }

  /** C: delay_buffer.h:279 */
  public void setCurrentOffset(int offset) {
    this.currentOffset = offset;
  }

  /** Stutterer accessor. */
  public int getCurrentOffset() {
    return currentOffset;
  }

  /** Stutterer accessor — indexed read into the buffer. */
  public int[] at(int offset) {
    return buffer[offset];
  }

  /** C: delay_buffer.h:82-86 */
  public boolean clearAndMoveOn() {
    buffer[currentOffset][0] = 0;
    buffer[currentOffset][1] = 0;
    return moveOn();
  }

  /** C: delay_buffer.h:88-95 */
  public boolean moveOn() {
    currentOffset++;
    boolean wrapped = (currentOffset == endOffset);
    if (wrapped) {
      currentOffset = 0; // C: start_ (always 0 in this port)
    }
    return wrapped;
  }

  /** C: delay_buffer.h:97-106 */
  public boolean moveBack() {
    if (currentOffset == 0) {
      currentOffset = endOffset - 1;
      return true;
    } else {
      currentOffset--;
      return false;
    }
  }

  // ── writeNative (delay_buffer.h:108-115) ──

  /** C: delay_buffer.h:108-115 — write one frame at the write position. */
  public void writeNative(int sampleL, int sampleR) {
    int writePos = currentOffset - DELAY_SPACE_BETWEEN_READ_AND_WRITE;
    while (writePos < 0) {
      writePos += sizeIncludingExtra;
    }
    buffer[writePos][0] = sampleL;
    buffer[writePos][1] = sampleR;
  }

  // ── advance / retreat (delay_buffer.h:50-74) ──

  /**
   * C: delay_buffer.h:50-61 — advance by actualSpinRate, invoke callback at each 256-step boundary.
   */
  public int advance(Runnable callback) {
    longPos += resampleConfig.actualSpinRate; // C:51
    int newShortPos = longPos >>> 24; // C:52 (uint8)
    int shortPosDiff = (newShortPos - lastShortPos) & 0xFF; // C:53
    lastShortPos = (byte) newShortPos; // C:54
    while (shortPosDiff-- > 0) { // C:56-59
      callback.run();
    }
    return (longPos >>> 8) & 65535; // C:60
  }

  /** C: delay_buffer.h:63-74 — retreat by actualSpinRate. */
  public int retreat(Runnable callback) {
    longPos -= resampleConfig.actualSpinRate; // C:64
    int newShortPos = longPos >>> 24; // C:65
    int shortPosDiff = (lastShortPos - newShortPos) & 0xFF; // C:66
    lastShortPos = (byte) newShortPos; // C:67
    while (shortPosDiff-- > 0) { // C:69-71
      callback.run();
    }
    return (longPos >>> 8) & 65535; // C:73
  }

  // ── writeResampled (delay_buffer.h:142-265) ──

  /**
   * C: delay_buffer.h:142-265 — spread one input frame across the ring as a triangle window
   * (anti-alias for non-native rates).
   */
  public void writeResampled(int sampleL, int sampleR, int strength1, int strength2) {
    if (resampleConfig == null) {
      return;
    }
    if (resampleConfig.actualSpinRate >= K_MAX_SAMPLE_VALUE) { // C:147 — spinning fast
      int howFarRightToStart =
          (strength2 + (resampleConfig.spinRateForSpedUpWriting >>> 8)) >>> 16; // C:158
      int distanceFromMainWrite = howFarRightToStart << 16; // C:162

      int writePos =
          currentOffset - DELAY_SPACE_BETWEEN_READ_AND_WRITE + howFarRightToStart; // C:165
      while (writePos < 0) writePos += sizeIncludingExtra; // C:166-168
      while (writePos >= endOffset) writePos -= sizeIncludingExtra;

      // Right of the main write pos (C:174-187)
      while (distanceFromMainWrite != 0) {
        int strengthThisWrite =
            (0xFFFFFFFF >>> 4)
                - (((distanceFromMainWrite - strength2) >>> 4)
                    * resampleConfig.divideByRate); // C:176-177
        buffer[writePos][0] +=
            Functions.multiply_32x32_rshift32(sampleL, strengthThisWrite) << 3; // C:179
        buffer[writePos][1] +=
            Functions.multiply_32x32_rshift32(sampleR, strengthThisWrite) << 3; // C:180
        writePos--;
        if (writePos < 0) writePos = endOffset - 1; // C:182-184
        distanceFromMainWrite -= 65536; // C:186
      }

      // Left of (and including) the main write pos (C:190-207)
      while (true) {
        int strengthThisWrite =
            (0xFFFFFFFF >>> 4)
                - (((distanceFromMainWrite + strength2) >>> 4)
                    * resampleConfig.divideByRate); // C:191-192
        if (strengthThisWrite <= 0) break; // C:193-195
        buffer[writePos][0] +=
            Functions.multiply_32x32_rshift32(sampleL, strengthThisWrite) << 3; // C:197
        buffer[writePos][1] +=
            Functions.multiply_32x32_rshift32(sampleR, strengthThisWrite) << 3; // C:198
        writePos--;
        if (writePos < 0) writePos = endOffset - 1; // C:203-205
        distanceFromMainWrite += 65536; // C:206
      }
    } else { // C:211 — spinning slow
      int writePos = currentOffset - DELAY_SPACE_BETWEEN_READ_AND_WRITE + 2; // C:225
      while (writePos < 0) writePos += sizeIncludingExtra; // C:227-229

      int[] strength = new int[4];
      strength[1] = strength1 + resampleConfig.rateMultiple - 65536; // C:236
      strength[2] = strength2 + resampleConfig.rateMultiple - 65536; // C:237
      strength[0] = strength[1] - 65536; // C:240
      strength[3] = strength[2] - 65536; // C:241

      int i = 3;
      while (true) { // C:244
        if (strength[i] > 0) { // C:245
          buffer[writePos][0] +=
              Functions.multiply_32x32_rshift32(
                      sampleL, (strength[i] >>> 2) * resampleConfig.writeSizeAdjustment)
                  << 2; // C:246-248
          buffer[writePos][1] +=
              Functions.multiply_32x32_rshift32(
                      sampleR, (strength[i] >>> 2) * resampleConfig.writeSizeAdjustment)
                  << 2; // C:249-251
        }
        if (--i < 0) break; // C:253-255
        writePos--;
        if (writePos < 0) writePos = endOffset - 1; // C:257-262
      }
    }
  }

  // ── setupResample (delay_buffer.cpp:99-124) ──

  /** C: delay_buffer.cpp:99-124 — prep the buffer when switching to non-native rate. */
  private void setupResample() {
    longPos = 0; // C:100
    lastShortPos = 0; // C:101

    int writePos = currentOffset - DELAY_SPACE_BETWEEN_READ_AND_WRITE; // C:109
    while (writePos < 0) writePos += sizeIncludingExtra; // C:110-112

    int writePosPlusOne = writePos + 1; // C:114
    while (writePosPlusOne >= endOffset) writePosPlusOne -= sizeIncludingExtra; // C:115-117

    buffer[writePosPlusOne][0] = buffer[writePos][0] >> 2; // C:119
    buffer[writePosPlusOne][1] = buffer[writePos][1] >> 2; // C:120

    buffer[writePos][0] -= buffer[writePosPlusOne][0]; // C:122
    buffer[writePos][1] -= buffer[writePosPlusOne][1]; // C:123
  }

  // ── setupForRender (delay_buffer.cpp:126-191) ──

  /** C: delay_buffer.cpp:126-191 */
  public void setupForRender(int rate) {
    if (!resampling()) { // C:127
      if (rate == nativeRate || buffer == null) { // C:128-131
        return;
      }
      setupResample(); // C:134
    }

    int actualSpinRate =
        (int)
            ((((rate & 0xFFFFFFFFL) << 24)
                / (nativeRate & 0xFFFFFFFFL))); // C:143 (uint64 division)
    int divideByRate = (int) ((double) 0xFFFFFFFFL / (double) (actualSpinRate >>> 8)); // C:144

    int spinRateForSpedUpWriting = 0;
    int rateMultiple = 0;
    int writeSizeAdjustment = 0;

    if (actualSpinRate < K_MAX_SAMPLE_VALUE) { // C:147 — spinning slow
      int timesSlowerRead = divideByRate >>> 16; // C:149
      rateMultiple = (actualSpinRate >>> 8) * (timesSlowerRead + 1); // C:154
      writeSizeAdjustment =
          (int)
              ((double) 0xFFFFFFFFL
                  / (double) ((long) rateMultiple * (timesSlowerRead + 1))); // C:163
    } else { // C:166 — spinning fast
      spinRateForSpedUpWriting = Math.min(actualSpinRate, K_MAX_SAMPLE_VALUE * 8); // C:171
      spinRateForSpedUpWriting <<= 1; // C:180
      divideByRate >>>= 1; // C:182
    }

    resampleConfig = new ResampleConfig();
    resampleConfig.actualSpinRate = actualSpinRate;
    resampleConfig.spinRateForSpedUpWriting = spinRateForSpedUpWriting;
    resampleConfig.divideByRate = divideByRate;
    resampleConfig.rateMultiple = rateMultiple;
    resampleConfig.writeSizeAdjustment = writeSizeAdjustment;
  }
}
