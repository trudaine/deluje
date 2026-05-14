package org.chuck.deluge.firmware.modulation;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.LookupTables;

public class Envelope {
  public static final int SOFT_CULL_INCREMENT = 65536;

  public enum EnvelopeStage {
    ATTACK,
    DECAY,
    SUSTAIN,
    HOLD,
    RELEASE,
    FAST_RELEASE,
    OFF
  }

  public int pos;
  public EnvelopeStage state = EnvelopeStage.OFF;
  public int lastValue;
  public int lastValuePreCurrentStage;
  public int timeEnteredState;
  public boolean ignoredNoteOff;
  public int fastReleaseIncrement = 1024;
  private int smoothedSustain = 0;

  public int render(
      int numSamples, int attack, int decay, int sustain, int release, short[] releaseTable) {
    while (true) {
      switch (state) {
        case ATTACK:
          pos += attack * numSamples;
          if (pos >= 8388608) {
            pos = 0;
            setState(EnvelopeStage.DECAY);
            continue;
          }
          lastValue = 2147483647 - FirmwareUtils.getDecay4(pos, 23);
          lastValue = Math.max(lastValue, 1);
          break;

        case DECAY:
          smoothedSustain =
              addSaturate(smoothedSustain, numSamples * ((sustain - smoothedSustain) >> 9));
          lastValue =
              smoothedSustain
                  + multiply_32x32_rshift32(
                          FirmwareUtils.getDecay8(pos, 23), 2147483647 - smoothedSustain)
                      * 2;

          pos += decay * numSamples;
          if (pos >= 8388608) {
            setState(EnvelopeStage.SUSTAIN);
          }
          break;

        case SUSTAIN:
          smoothedSustain =
              addSaturate(smoothedSustain, numSamples * ((sustain - smoothedSustain) >> 9));
          lastValue = smoothedSustain;
          if (sustain == 0) {
            setState(EnvelopeStage.OFF);
          } else if (ignoredNoteOff) {
            unconditionalRelease(EnvelopeStage.RELEASE, SOFT_CULL_INCREMENT);
          }
          break;

        case RELEASE:
          pos += release * numSamples;
          if (pos >= 8388608) {
            setState(EnvelopeStage.OFF);
            lastValue = 0;
            return -2147483648;
          }
          lastValue =
              multiply_32x32_rshift32(
                      FirmwareUtils.interpolateTable(pos, 23, releaseTable, 8),
                      lastValuePreCurrentStage)
                  << 1;
          break;

        case FAST_RELEASE:
          if (fastReleaseIncrement < 2 * release) {
            release = 2 * release;
            fastReleaseIncrement = release;
          }
          pos += fastReleaseIncrement * numSamples;
          if (pos >= 8388608) {
            setState(EnvelopeStage.OFF);
            return -2147483648;
          }

          lastValue =
              multiply_32x32_rshift32(
                      (FirmwareUtils.getSine(pos + (8388608 >> 1), 24) >> 1) + 1073741824,
                      lastValuePreCurrentStage)
                  << 1;
          break;

        default: // OFF
          return -2147483648;
      }
      break;
    }

    return (lastValue - 1073741824) << 1;
  }

  public int noteOn(boolean directlyToDecay) {
    ignoredNoteOff = false;
    pos = 0;
    if (!directlyToDecay) {
      setState(EnvelopeStage.ATTACK);
      lastValue = 0;
    } else {
      setState(EnvelopeStage.DECAY);
      lastValue = 2147483647;
    }

    return (lastValue - 1073741824) << 1;
  }

  public void setState(EnvelopeStage newState) {
    state = newState;
  }

  public void unconditionalOff() {
    lastValuePreCurrentStage = lastValue;
    setState(EnvelopeStage.OFF);
  }

  public void unconditionalRelease(EnvelopeStage typeOfRelease, int newFastReleaseIncrement) {
    fastReleaseIncrement = newFastReleaseIncrement;

    if (state != typeOfRelease) {
      setState(typeOfRelease);
      pos = 0;
      lastValuePreCurrentStage = lastValue;
    }
  }

  public void resumeAttack(int oldLastValue) {
    if (state == EnvelopeStage.ATTACK) {
      pos =
          FirmwareUtils.interpolateTableInverse(
              2147483647 - oldLastValue, 23, LookupTables.decayTableSmall4, 8);
    }
  }
}
