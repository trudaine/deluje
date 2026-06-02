package org.chuck.deluge.firmware.dsp.delay;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;

public class DelayBuffer {
  public static final int kMaxSize = 88200;
  public static final int kMinSize = 1;
  public static final int kNeutralSize = 16384;
  public static final int delaySpaceBetweenReadAndWrite = 20;
  public static final int kMaxSampleValue = 16777216;

  public static class ResampleConfig {
    public int actualSpinRate;
    public int spinRateForSpedUpWriting;
    public int divideByRate;
    public int rateMultiple;
    public int writeSizeAdjustment;
  }

  private int nativeRate = 0;
  private StereoSample[] buffer;
  private int size;
  public int sizeIncludingExtra;

  private int startOffset;
  private int endOffset;
  private int currentOffset;

  public int longPos;
  public int lastShortPos;

  public ResampleConfig resampleConfig = null;

  public enum Error {
    NONE,
    UNSPECIFIED,
    INSUFFICIENT_RAM
  }

  public DelayBuffer() {}

  public void discard() {
    if (buffer != null) {
      buffer = null;
    }
  }

  public Error init(int rate, int failIfThisSize, boolean includeExtraSpace) {
    int[] sizeInfo = getIdealBufferSizeFromRate(rate);
    int newSize = sizeInfo[0];
    boolean makePrecise = sizeInfo[1] == 1;

    this.nativeRate = rate;
    this.size = newSize;

    if (this.size == failIfThisSize) {
      return Error.UNSPECIFIED;
    }

    if (makePrecise) {
      makeNativeRatePrecise();
    }

    this.sizeIncludingExtra = this.size + (includeExtraSpace ? delaySpaceBetweenReadAndWrite : 0);

    try {
      this.buffer = new StereoSample[this.sizeIncludingExtra];
      for (int i = 0; i < this.sizeIncludingExtra; i++) {
        this.buffer[i] = new StereoSample();
      }
    } catch (OutOfMemoryError e) {
      return Error.INSUFFICIENT_RAM;
    }

    this.startOffset = 0;
    this.endOffset = this.sizeIncludingExtra;
    clear();
    return Error.NONE;
  }

  public Error init(int rate) {
    return init(rate, 0, true);
  }

  public void clear() {
    for (int i = 0; i < delaySpaceBetweenReadAndWrite + 2; i++) {
      if (i < buffer.length) {
        buffer[i].l = 0;
        buffer[i].r = 0;
      }
    }
    currentOffset = startOffset + delaySpaceBetweenReadAndWrite;
    resampleConfig = null;
  }

  public static int[] getIdealBufferSizeFromRate(int newRate) {
    long bufferSize = (long) kNeutralSize * kMaxSampleValue / newRate;
    boolean clamped = false;

    if (bufferSize > kMaxSize) {
      bufferSize = kMaxSize;
      clamped = true;
    }

    if (bufferSize < kMinSize) {
      bufferSize = kMinSize;
      clamped = true;
    }

    return new int[] {(int) bufferSize, clamped ? 1 : 0};
  }

  public void makeNativeRatePrecise() {
    this.nativeRate =
        (int) Math.round((double) kNeutralSize * (double) kMaxSampleValue / (double) this.size);
  }

  public void makeNativeRatePreciseRelativeToOtherBuffer(DelayBuffer otherBuffer) {
    double otherBufferAmountTooFast =
        (double) otherBuffer.nativeRate
            * (double) otherBuffer.size
            / ((double) kNeutralSize * (double) kMaxSampleValue);
    this.nativeRate =
        (int)
            Math.round(
                (double) kNeutralSize
                    * (double) kMaxSampleValue
                    * otherBufferAmountTooFast
                    / (double) this.size);
  }

  public void invalidate() {
    buffer = null;
  }

  public boolean isActive() {
    return buffer != null;
  }

  public boolean isNative() {
    return resampleConfig == null;
  }

  public boolean resampling() {
    return resampleConfig != null;
  }

  public int nativeRate() {
    return nativeRate;
  }

  public StereoSample current() {
    return buffer[currentOffset];
  }

  public int size() {
    return size;
  }

  public void setCurrentOffset(int offset) {
    this.currentOffset = offset;
  }

  public int getCurrentOffset() {
    return currentOffset;
  }

  public StereoSample bufferAt(int offset) {
    return buffer[offset];
  }

  public boolean clearAndMoveOn() {
    buffer[currentOffset].l = 0;
    buffer[currentOffset].r = 0;
    return moveOn();
  }

  public boolean moveOn() {
    currentOffset++;
    boolean wrapped = (currentOffset == endOffset);
    if (wrapped) {
      currentOffset = startOffset;
    }
    return wrapped;
  }

  public boolean moveBack() {
    if (currentOffset == startOffset) {
      currentOffset = endOffset - 1;
      return true;
    } else {
      currentOffset--;
      return false;
    }
  }

  public interface Callback {
    void call();
  }

  public int advance(Callback callback) {
    longPos += resampleConfig.actualSpinRate;
    // C++ uses uint8_t for these, so the position and the diff wrap mod 256. Without the masks the
    // diff goes negative at every high-byte wrap and the callback (which advances the write head
    // and
    // the buffer-swap counter) is skipped — freezing the delay so it never starts reading back.
    int newShortPos = (longPos >>> 24) & 0xFF;
    int shortPosDiff = (newShortPos - lastShortPos) & 0xFF;
    lastShortPos = newShortPos;

    while (shortPosDiff > 0) {
      callback.call();
      shortPosDiff--;
    }
    return (longPos >>> 8) & 65535;
  }

  public StereoSample readResampled(int strength1, int strength2) {
    StereoSample s1 = buffer[currentOffset];
    int nextOffset = currentOffset + 1;
    if (nextOffset >= endOffset) nextOffset = startOffset;
    StereoSample s2 = buffer[nextOffset];

    int l = (int) (((long) s1.l * strength1 + (long) s2.l * strength2) >> 16);
    int r = (int) (((long) s1.r * strength1 + (long) s2.r * strength2) >> 16);
    return new StereoSample(l, r);
  }

  public void writeNative(StereoSample toDelay) {
    int writePos = currentOffset - delaySpaceBetweenReadAndWrite;
    if (writePos < startOffset) {
      writePos += sizeIncludingExtra;
    }
    buffer[writePos].l = toDelay.l;
    buffer[writePos].r = toDelay.r;
  }

  public void writeNativeAndMoveOn(StereoSample toDelay, int[] writePosRef) {
    int writePos = writePosRef[0];
    buffer[writePos].l = toDelay.l;
    buffer[writePos].r = toDelay.r;

    writePos++;
    if (writePos == endOffset) {
      writePos = startOffset;
    }
    writePosRef[0] = writePos;
  }

  public void writeResampled(StereoSample toDelay, int strength1, int strength2) {
    if (resampleConfig == null) {
      return;
    }

    if (resampleConfig.actualSpinRate >= kMaxSampleValue) {
      int howFarRightToStart = (strength2 + (resampleConfig.spinRateForSpedUpWriting >>> 8)) >>> 16;
      int distanceFromMainWrite = howFarRightToStart << 16;

      int writePos = currentOffset - delaySpaceBetweenReadAndWrite + howFarRightToStart;
      while (writePos < startOffset) {
        writePos += sizeIncludingExtra;
      }
      while (writePos >= endOffset) {
        writePos -= sizeIncludingExtra;
      }

      while (distanceFromMainWrite != 0) {
        long temp = (long) (distanceFromMainWrite - strength2) >> 4;
        int strengthThisWrite = (int) ((0xFFFFFFFFL >>> 4) - (temp * resampleConfig.divideByRate));

        buffer[writePos].l += multiply_32x32_rshift32(toDelay.l, strengthThisWrite) << 3;
        buffer[writePos].r += multiply_32x32_rshift32(toDelay.r, strengthThisWrite) << 3;

        writePos--;
        if (writePos < startOffset) {
          writePos = endOffset - 1;
        }
        distanceFromMainWrite -= 65536;
      }

      while (true) {
        long temp = (long) (distanceFromMainWrite + strength2) >> 4;
        int strengthThisWrite = (int) ((0xFFFFFFFFL >>> 4) - (temp * resampleConfig.divideByRate));
        if (strengthThisWrite <= 0) {
          break;
        }

        buffer[writePos].l += multiply_32x32_rshift32(toDelay.l, strengthThisWrite) << 3;
        buffer[writePos].r += multiply_32x32_rshift32(toDelay.r, strengthThisWrite) << 3;

        writePos--;
        if (writePos < startOffset) {
          writePos = endOffset - 1;
        }
        distanceFromMainWrite += 65536;
      }
    } else {
      int writePos = currentOffset - delaySpaceBetweenReadAndWrite + 2;
      while (writePos < startOffset) {
        writePos += sizeIncludingExtra;
      }

      int[] strength = new int[4];
      strength[1] = strength1 + resampleConfig.rateMultiple - 65536;
      strength[2] = strength2 + resampleConfig.rateMultiple - 65536;
      strength[0] = strength[1] - 65536;
      strength[3] = strength[2] - 65536;

      int i = 3;
      while (true) {
        if (strength[i] > 0) {
          buffer[writePos].l +=
              multiply_32x32_rshift32(
                      toDelay.l, (strength[i] >> 2) * resampleConfig.writeSizeAdjustment)
                  << 2;
          buffer[writePos].r +=
              multiply_32x32_rshift32(
                      toDelay.r, (strength[i] >> 2) * resampleConfig.writeSizeAdjustment)
                  << 2;
        }
        if (--i < 0) {
          break;
        }

        writePos--;
        if (writePos < startOffset) {
          writePos = endOffset - 1;
        }
      }
    }
  }

  private void setupResample() {
    longPos = 0;
    lastShortPos = 0;

    int writePos = currentOffset - delaySpaceBetweenReadAndWrite;
    while (writePos < startOffset) {
      writePos += sizeIncludingExtra;
    }

    int writePosPlusOne = writePos + 1;
    while (writePosPlusOne >= endOffset) {
      writePosPlusOne -= sizeIncludingExtra;
    }

    buffer[writePosPlusOne].l = buffer[writePos].l >> 2;
    buffer[writePosPlusOne].r = buffer[writePos].r >> 2;

    buffer[writePos].l -= buffer[writePosPlusOne].l;
    buffer[writePos].r -= buffer[writePosPlusOne].r;
  }

  public void setupForRender(int rate) {
    if (!resampling()) {
      if (rate == nativeRate || buffer == null) {
        return;
      }
      setupResample();
    }

    long actualSpinRateLong = (long) (((double) ((long) rate << 24)) / (double) nativeRate);
    int actualSpinRate = (int) actualSpinRateLong;
    int divideByRate = (int) ((double) 0xFFFFFFFFL / (double) (actualSpinRate >>> 8));

    int spinRateForSpedUpWriting = 0;
    int rateMultiple = 0;
    int writeSizeAdjustment = 0;

    if (actualSpinRate < kMaxSampleValue) {
      int timesSlowerRead = divideByRate >>> 16;
      rateMultiple = (actualSpinRate >>> 8) * (timesSlowerRead + 1);
      writeSizeAdjustment =
          (int) ((double) 0xFFFFFFFFL / (double) ((long) rateMultiple * (timesSlowerRead + 1)));
    } else {
      spinRateForSpedUpWriting = Math.min(actualSpinRate, kMaxSampleValue * 8);
      spinRateForSpedUpWriting <<= 1;
      divideByRate >>>= 1;
    }

    resampleConfig = new ResampleConfig();
    resampleConfig.actualSpinRate = actualSpinRate;
    resampleConfig.spinRateForSpedUpWriting = spinRateForSpedUpWriting;
    resampleConfig.divideByRate = divideByRate;
    resampleConfig.rateMultiple = rateMultiple;
    resampleConfig.writeSizeAdjustment = writeSizeAdjustment;
  }
}
