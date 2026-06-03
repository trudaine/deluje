package org.chuck.deluge.firmware.modulation;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

public class LFO {
  public enum LFOType {
    SINE,
    TRIANGLE,
    SQUARE,
    SAW,
    SAMPLE_AND_HOLD,
    RANDOM_WALK,
    WARBLER
  }

  public int phase = 0;
  public int holdValue = 0;
  public int target = 0;
  public int speed = 0;

  public int render(int numSamples, LFOType waveType, int phaseIncrement) {
    int value = 0;
    switch (waveType) {
      case SAW:
        value = phase;
        break;

      case SQUARE:
        value = (phase >= 0) ? ONE : NEGATIVE_ONE;
        break;

      case SINE:
        value = FirmwareUtils.getSine(phase, 32);
        break;

      case TRIANGLE:
        // Triangle: abs(saw)
        int temp = (phase < 0) ? -phase : phase;
        value = (temp - 1073741824) << 1;
        break;

      case SAMPLE_AND_HOLD:
        // Retrigger on unsigned uint32 phase wrap (firmware: phase + inc*n < phase). Phase and
        // phaseIncrement are conceptually uint32, so mask to avoid Java sign-extension.
        if (phase == 0
            || (phase & 0xFFFFFFFFL) + (phaseIncrement & 0xFFFFFFFFL) * numSamples > 0xFFFFFFFFL) {
          value = FirmwareUtils.getNoise();
          holdValue = value;
        } else {
          value = holdValue;
        }
        break;

      case RANDOM_WALK:
        int range = (int) (4294967295L / 20);
        if (phase == 0) {
          value = (range / 2) - (FirmwareUtils.getNoise() % range);
          holdValue = value;
        } else if ((phase & 0xFFFFFFFFL) + (phaseIncrement & 0xFFFFFFFFL) * numSamples
            > 0xFFFFFFFFL) {
          int step = (holdValue / -16) + (range / 2) - (FirmwareUtils.getNoise() % range);
          holdValue = addSaturate(holdValue, step);
          value = holdValue;
        } else {
          value = holdValue;
        }
        break;

      case WARBLER:
        phaseIncrement *= 2;
        warble(numSamples, phaseIncrement);
        value = holdValue;
        break;
    }

    phase += phaseIncrement * numSamples;
    return value;
  }

  private void warble(int numSamples, int phaseIncrement) {
    if ((long) phase + (long) phaseIncrement * numSamples > 0xFFFFFFFFL) {
      target = FirmwareUtils.getNoise();
      speed = 0;
    }
    int targetSpeed = target - holdValue;
    speed = speed + numSamples * (Q31.multiply_32x32_rshift32(targetSpeed, phaseIncrement >> 8));
    holdValue = addSaturate(holdValue, speed);
  }
}
