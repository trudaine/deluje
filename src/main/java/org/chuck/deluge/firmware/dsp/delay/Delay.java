package org.chuck.deluge.firmware.dsp.delay;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.convolution.ImpulseResponseProcessor;
import org.chuck.deluge.firmware.model.SyncLevel;
import org.chuck.deluge.firmware.model.SyncType;
import org.chuck.deluge.firmware.util.FirmwareUtils;

public class Delay {
  public static final int kSampleRate = 44100;
  public static boolean renderInStereo = true;

  public static class State {
    public boolean doDelay;
    public int userDelayRate;
    public int delayFeedbackAmount;
    public int analog_saturation = 8;
  }

  public DelayBuffer primaryBuffer = new DelayBuffer();
  public DelayBuffer secondaryBuffer = new DelayBuffer();
  public ImpulseResponseProcessor irProcessor = new ImpulseResponseProcessor();

  public int countCyclesWithoutChange;
  public int userRateLastTime;
  public boolean pingPong = true;
  public boolean analog = false;

  public SyncType syncType = SyncType.SYNC_TYPE_EVEN;
  public SyncLevel syncLevel = SyncLevel.SYNC_LEVEL_16TH;

  public int sizeLeftUntilBufferSwap;
  public int postLPFL;
  public int postLPFR;
  public int prevFeedback = 0;
  public int repeatsUntilAbandon = 0;

  public boolean isActive() {
    return primaryBuffer.isActive() || secondaryBuffer.isActive();
  }

  private int getAmountToWriteBeforeReadingBegins() {
    return secondaryBuffer.size();
  }

  public void informWhetherActive(boolean newActive, int userDelayRate) {
    boolean previouslyActive = isActive();

    if (previouslyActive != newActive) {
      if (!newActive) {
        discardBuffers();
        return;
      }

      DelayBuffer.Error result = secondaryBuffer.init(userDelayRate);
      if (result != DelayBuffer.Error.NONE) {
        return;
      }
      prepareToBeginWriting();
      postLPFL = 0;
      postLPFR = 0;
      return;
    }

    if (previouslyActive) {
      if (!primaryBuffer.isActive()
          && secondaryBuffer.isActive()
          && sizeLeftUntilBufferSwap == getAmountToWriteBeforeReadingBegins()) {

        int[] idealSizeInfo = DelayBuffer.getIdealBufferSizeFromRate(userDelayRate);
        int idealBufferSize = idealSizeInfo[0];

        if (idealBufferSize != secondaryBuffer.size()) {
          secondaryBuffer.discard();
          DelayBuffer.Error result = secondaryBuffer.init(userDelayRate);
          if (result != DelayBuffer.Error.NONE) {
            return;
          }
          prepareToBeginWriting();
          postLPFL = 0;
          postLPFR = 0;
        }
      }
    }
  }

  public void copySecondaryToPrimary() {
    primaryBuffer.discard();
    primaryBuffer = secondaryBuffer;
    secondaryBuffer =
        new DelayBuffer(); // the original invalidated the secondary so it wouldn't free memory
  }

  public void copyPrimaryToSecondary() {
    secondaryBuffer.discard();
    secondaryBuffer = primaryBuffer;
    primaryBuffer = new DelayBuffer();
  }

  private void prepareToBeginWriting() {
    sizeLeftUntilBufferSwap = getAmountToWriteBeforeReadingBegins();
  }

  public void setupWorkingState(
      State workingState, int timePerInternalTickInverse, boolean anySoundComingIn) {
    boolean mightDoDelay =
        (workingState.delayFeedbackAmount >= 256 && (anySoundComingIn || repeatsUntilAbandon > 0));

    if (mightDoDelay) {
      if (syncLevel.value != 0) {
        workingState.userDelayRate =
            multiply_32x32_rshift32_rounded(workingState.userDelayRate, timePerInternalTickInverse);

        int limit = 2147483647 >>> (syncLevel.value + 5);
        workingState.userDelayRate = Math.min(workingState.userDelayRate, limit);

        if (syncType == SyncType.SYNC_TYPE_EVEN) {
          // Do nothing
        } else if (syncType == SyncType.SYNC_TYPE_TRIPLET) {
          workingState.userDelayRate = workingState.userDelayRate * 3 / 2;
        } else if (syncType == SyncType.SYNC_TYPE_DOTTED) {
          workingState.userDelayRate = workingState.userDelayRate * 2 / 3;
        }
        workingState.userDelayRate <<= (syncLevel.value + 5);
      }
    }

    informWhetherActive(mightDoDelay, workingState.userDelayRate);
    workingState.doDelay = isActive();

    if (workingState.doDelay) {
      if (anySoundComingIn || workingState.delayFeedbackAmount != prevFeedback) {
        setTimeToAbandon(workingState);
        prevFeedback = workingState.delayFeedbackAmount;
      }
    }
  }

  public void setTimeToAbandon(State workingState) {
    if (!workingState.doDelay) {
      repeatsUntilAbandon = 0;
    } else if (workingState.delayFeedbackAmount < 33554432) {
      repeatsUntilAbandon = 1;
    } else if (workingState.delayFeedbackAmount <= 100663296) {
      repeatsUntilAbandon = 2;
    } else if (workingState.delayFeedbackAmount <= 218103808) {
      repeatsUntilAbandon = 3;
    } else if (workingState.delayFeedbackAmount < 318767104) {
      repeatsUntilAbandon = 4;
    } else if (workingState.delayFeedbackAmount < 352321536) {
      repeatsUntilAbandon = 5;
    } else if (workingState.delayFeedbackAmount < 452984832) {
      repeatsUntilAbandon = 6;
    } else if (workingState.delayFeedbackAmount < 520093696) {
      repeatsUntilAbandon = 9;
    } else if (workingState.delayFeedbackAmount < 637534208) {
      repeatsUntilAbandon = 12;
    } else if (workingState.delayFeedbackAmount < 704643072) {
      repeatsUntilAbandon = 13;
    } else if (workingState.delayFeedbackAmount < 771751936) {
      repeatsUntilAbandon = 18;
    } else if (workingState.delayFeedbackAmount < 838860800) {
      repeatsUntilAbandon = 24;
    } else if (workingState.delayFeedbackAmount < 939524096) {
      repeatsUntilAbandon = 40;
    } else if (workingState.delayFeedbackAmount < 1040187392) {
      repeatsUntilAbandon = 110;
    } else {
      repeatsUntilAbandon = 255;
    }
  }

  public void hasWrapped() {
    if (repeatsUntilAbandon == 255) {
      return;
    }

    repeatsUntilAbandon--;
    if (repeatsUntilAbandon == 0) {
      discardBuffers();
    }
  }

  public void discardBuffers() {
    primaryBuffer.discard();
    secondaryBuffer.discard();
    prevFeedback = 0;
    repeatsUntilAbandon = 0;
  }

  public void initializeSecondaryBuffer(
      int newNativeRate, boolean makeNativeRatePreciseRelativeToOtherBuffer) {
    DelayBuffer.Error result = secondaryBuffer.init(newNativeRate, primaryBuffer.size(), true);
    if (result != DelayBuffer.Error.NONE) {
      return;
    }

    if (makeNativeRatePreciseRelativeToOtherBuffer) {
      primaryBuffer.makeNativeRatePreciseRelativeToOtherBuffer(secondaryBuffer);
    } else {
      primaryBuffer.makeNativeRatePrecise();
      secondaryBuffer.makeNativeRatePrecise();
    }
    sizeLeftUntilBufferSwap = secondaryBuffer.size() + 5;
  }

  private int signedSaturate(int input, int numBitsInOutput) {
    int limit = (1 << (numBitsInOutput - 1)) - 1;
    if (input > limit) {
      return limit;
    } else if (input < -limit - 1) {
      return -limit - 1;
    } else {
      return input;
    }
  }

  public void process(StereoSample[] buffer, State delayWorkingState) {
    if (!delayWorkingState.doDelay) {
      return;
    }

    if (delayWorkingState.userDelayRate != userRateLastTime) {
      userRateLastTime = delayWorkingState.userDelayRate;
      countCyclesWithoutChange = 0;
    } else {
      countCyclesWithoutChange += buffer.length;
    }

    if (!secondaryBuffer.isActive()) {
      if (primaryBuffer.resampling()
          || delayWorkingState.userDelayRate != primaryBuffer.nativeRate()) {
        if (countCyclesWithoutChange >= (kSampleRate >> 3)) {
          initializeSecondaryBuffer(delayWorkingState.userDelayRate, true);
        } else if (delayWorkingState.userDelayRate >= (primaryBuffer.nativeRate() << 1)) {
          initializeSecondaryBuffer(delayWorkingState.userDelayRate, false);
        } else if (delayWorkingState.userDelayRate < primaryBuffer.nativeRate() >> 1) {
          initializeSecondaryBuffer(delayWorkingState.userDelayRate >> 1, false);
        }
      }
    }

    primaryBuffer.setupForRender(delayWorkingState.userDelayRate);

    if (secondaryBuffer.isActive()) {
      secondaryBuffer.setupForRender(delayWorkingState.userDelayRate);
    }

    boolean wrapped = false;
    boolean[] wrappedRef = new boolean[] {false};

    StereoSample[] workingBuffer = new StereoSample[buffer.length];
    for (int i = 0; i < workingBuffer.length; i++) workingBuffer[i] = new StereoSample();

    int primaryBufferOldPosOffset = -1;
    int primaryBufferOldLongPos = 0;
    int primaryBufferOldLastShortPos = 0;

    if (!primaryBuffer.isActive()) {
      for (StereoSample s : workingBuffer) {
        s.l = 0;
        s.r = 0;
      }
    } else {
      primaryBufferOldPosOffset = primaryBuffer.getCurrentOffset();
      primaryBufferOldLongPos = primaryBuffer.longPos;
      primaryBufferOldLastShortPos = primaryBuffer.lastShortPos;

      if (primaryBuffer.isNative()) {
        for (StereoSample sample : workingBuffer) {
          wrapped = primaryBuffer.clearAndMoveOn() || wrapped;
          StereoSample current = primaryBuffer.current();
          sample.l = current.l;
          sample.r = current.r;
        }
      } else {
        for (StereoSample sample : workingBuffer) {
          int primaryStrength2 =
              primaryBuffer.advance(
                  () -> {
                    wrappedRef[0] = primaryBuffer.clearAndMoveOn() || wrappedRef[0];
                  });
          wrapped = wrapped || wrappedRef[0];

          int primaryStrength1 = 65536 - primaryStrength2;

          int nextPosOffset = primaryBuffer.getCurrentOffset() + 1;
          if (nextPosOffset >= primaryBuffer.sizeIncludingExtra) {
            nextPosOffset = 0; // wrap around start_
          }
          StereoSample fromDelay1 = primaryBuffer.current();
          // In java we can just create a dummy object to hold next pos if needed, but it's safe to
          // assume end() means sizeIncludingExtra
          // actually endOffset = sizeIncludingExtra
          if (nextPosOffset
              == primaryBuffer.sizeIncludingExtra) { // Should not happen if correctly handled
            nextPosOffset = 0;
          }

          // Actually DelayBuffer logic for moveOn():
          // currentOffset++; if (currentOffset == endOffset) currentOffset = startOffset;
          // So we do the same:
          int next = primaryBuffer.getCurrentOffset() + 1;
          if (next == primaryBuffer.sizeIncludingExtra) {
            next = 0;
          }
          StereoSample fromDelay2 = primaryBuffer.bufferAt(next);

          sample.l =
              (multiply_32x32_rshift32(fromDelay1.l, primaryStrength1 << 14)
                      + multiply_32x32_rshift32(fromDelay2.l, primaryStrength2 << 14))
                  << 2;
          sample.r =
              (multiply_32x32_rshift32(fromDelay1.r, primaryStrength1 << 14)
                      + multiply_32x32_rshift32(fromDelay2.r, primaryStrength2 << 14))
                  << 2;
        }
      }
    }

    if (analog) {
      for (StereoSample sample : workingBuffer) {
        irProcessor.process(sample, sample);
      }

      for (StereoSample sample : workingBuffer) {
        sample.l =
            FirmwareUtils.getTanHUnknown(
                    multiply_32x32_rshift32(sample.l, delayWorkingState.delayFeedbackAmount),
                    delayWorkingState.analog_saturation)
                << 2;
        sample.r =
            FirmwareUtils.getTanHUnknown(
                    multiply_32x32_rshift32(sample.r, delayWorkingState.delayFeedbackAmount),
                    delayWorkingState.analog_saturation)
                << 2;
      }
    } else {
      for (StereoSample sample : workingBuffer) {
        sample.l =
            signedSaturate(
                    multiply_32x32_rshift32(sample.l, delayWorkingState.delayFeedbackAmount),
                    32 - 3)
                << 2;
        sample.r =
            signedSaturate(
                    multiply_32x32_rshift32(sample.r, delayWorkingState.delayFeedbackAmount),
                    32 - 3)
                << 2;
      }
    }

    for (StereoSample sample : workingBuffer) {
      int distanceToGoL = sample.l - postLPFL;
      postLPFL += distanceToGoL >> 11;
      sample.l -= postLPFL;

      int distanceToGoR = sample.r - postLPFR;
      postLPFR += distanceToGoR >> 11;
      sample.r -= postLPFR;
    }

    for (int i = 0; i < workingBuffer.length; i++) {
      StereoSample input = workingBuffer[i];
      StereoSample output = buffer[i];

      StereoSample current = new StereoSample(input.l, input.r);

      if (pingPong && renderInStereo) {
        input.l = current.r;
        input.r = ((output.l + output.r) >> 1) + current.l;
      } else {
        input.l += output.l;
        input.r += output.r;
      }

      output.l += current.l;
      output.r += current.r;
    }

    if (primaryBuffer.isActive()) {
      if (primaryBuffer.isNative()) {
        int[] writePosRef =
            new int[] {primaryBufferOldPosOffset - DelayBuffer.delaySpaceBetweenReadAndWrite};
        if (writePosRef[0] < 0) {
          writePosRef[0] += primaryBuffer.sizeIncludingExtra;
        }

        for (StereoSample sample : workingBuffer) {
          primaryBuffer.writeNativeAndMoveOn(sample, writePosRef);
        }
      } else {
        primaryBuffer.setCurrentOffset(primaryBufferOldPosOffset);
        primaryBuffer.longPos = primaryBufferOldLongPos;
        primaryBuffer.lastShortPos = primaryBufferOldLastShortPos;

        for (StereoSample sample : workingBuffer) {
          int primaryStrength2 =
              primaryBuffer.advance(
                  () -> {
                    primaryBuffer.moveOn();
                  });
          int primaryStrength1 = 65536 - primaryStrength2;

          primaryBuffer.writeResampled(sample, primaryStrength1, primaryStrength2);
        }
      }
    }

    if (secondaryBuffer.isActive()) {
      wrapped = false;
      wrappedRef[0] = false;

      if (secondaryBuffer.isNative()) {
        for (StereoSample sample : workingBuffer) {
          wrappedRef[0] = secondaryBuffer.clearAndMoveOn() || wrappedRef[0];
          sizeLeftUntilBufferSwap--;
          secondaryBuffer.writeNative(sample);
        }
      } else {
        for (StereoSample sample : workingBuffer) {
          int secondaryStrength2 =
              secondaryBuffer.advance(
                  () -> {
                    wrappedRef[0] = secondaryBuffer.clearAndMoveOn() || wrappedRef[0];
                    sizeLeftUntilBufferSwap--;
                  });

          int secondaryStrength1 = 65536 - secondaryStrength2;
          secondaryBuffer.writeResampled(sample, secondaryStrength1, secondaryStrength2);
        }
      }
      wrapped = wrapped || wrappedRef[0];

      if (sizeLeftUntilBufferSwap < 0) {
        copySecondaryToPrimary();
      }
    }

    if (wrapped) {
      hasWrapped();
    }
  }
}
