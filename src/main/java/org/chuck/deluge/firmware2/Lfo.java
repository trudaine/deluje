package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code lfo.h} LFO class.
 *
 * <p>Waveforms: SAW (phase as-is), SQUARE (phase sign), SINE (table lookup), TRIANGLE,
 * SAMPLE_AND_HOLD (per-cycle random), RANDOM_WALK (biased random steps), WARBLER (smoothed random
 * via second-order filter).
 */
public class Lfo {

  public enum LfoType {
    SAW,
    SQUARE,
    SINE,
    TRIANGLE,
    SAMPLE_AND_HOLD,
    RANDOM_WALK,
    WARBLER
  }

  public enum SyncType {
    EVEN,
    TRIPLET,
    DOTTED
  }

  public enum SyncLevel {
    NONE,
    WHOLE,
    L_2ND,
    L_4TH,
    L_8TH,
    L_16TH,
    L_32ND,
    L_64TH,
    L_128TH,
    L_256TH
  }

  public static class LfoConfig {
    public LfoType waveType = LfoType.TRIANGLE;
    public SyncType syncType = SyncType.EVEN;
    public SyncLevel syncLevel = SyncLevel.NONE;

    public LfoConfig() {}

    public LfoConfig(LfoType waveType) {
      this.waveType = waveType;
    }
  }

  /** 32-bit unsigned phase accumulator. Advances by phaseIncrement each sample. */
  public int phase;

  public int holdValue;
  public int target;
  public int speed;

  public static int getLfoInitialPhaseForNegativeExtreme(LfoType waveType) {
    return switch (waveType) {
      case SAW -> 0x80000000;
      case SINE -> 0xC0000000;
      default -> 0;
    };
  }

  public static int getLfoInitialPhaseForZero(LfoType waveType) {
    return switch (waveType) {
      case TRIANGLE -> 1073741824;
      default -> 0;
    };
  }

  public void setLocalInitialPhase(LfoConfig config) {
    phase = getLfoInitialPhaseForNegativeExtreme(config.waveType);
    holdValue = 0;
    speed = 0;
    target = 0;
  }

  public void setGlobalInitialPhase(LfoConfig config) {
    if (config.waveType == LfoType.SINE || config.waveType == LfoType.TRIANGLE) {
      phase = getLfoInitialPhaseForZero(config.waveType);
    } else {
      phase = getLfoInitialPhaseForNegativeExtreme(config.waveType);
    }
    holdValue = 0;
    speed = 0;
    target = 0;
  }

  public int render(int numSamples, LfoConfig config, int phaseIncrement) {
    return render(numSamples, config.waveType, phaseIncrement);
  }

  // ── render (lfo.h:47-110) ──

  /**
   * Render one block of LFO. Port of lfo.h:47-110.
   *
   * @param numSamples samples in this block
   * @param waveType waveform shape
   * @param phaseIncrement per-sample phase advance (32-bit unsigned)
   * @return LFO output value (Q31)
   */
  public int render(int numSamples, LfoType waveType, int phaseIncrement) {
    int value;
    switch (waveType) {
      case SAW:
        // value = static_cast<int32_t>(phase);  // (line 51)
        value = phase;
        break;

      case SQUARE:
        // value = getSquare(phase);  // (line 55)
        value = Functions.getSquare(phase);
        break;

      case SINE:
        // value = getSine(phase);  // (line 59)
        value = Functions.getSine(phase, 32);
        break;

      case TRIANGLE:
        // value = getTriangle(phase);  // (line 63)
        value = Functions.getTriangle(phase);
        break;

      case SAMPLE_AND_HOLD:
        // if ((phase == 0) || (phase + phaseIncrement * numSamples < phase))  // (lines 67-68)
        // unsigned wrap check: phase + inc*n wraps past 2^32
        if (phase == 0
            || Integer.compareUnsigned(
                    (int) ((phase & 0xFFFFFFFFL) + (phaseIncrement & 0xFFFFFFFFL) * numSamples),
                    phase)
                < 0) {
          // value = CONG; holdValue = value;  // (lines 68-69)
          value = Functions.getNoise();
          holdValue = value;
        } else {
          value = holdValue; // (line 72)
        }
        break;

      case RANDOM_WALK:
        {
          // uint32_t range = 4294967295u / 20;  // (line 77)
          int range = 214748365; // 4294967295 / 20 ≈ 214748364
          if (phase == 0) {
            // value = (range / 2) - CONG % range;  // (line 79)
            int noise = Functions.getNoise() & 0x7FFFFFFF; // positive noise
            value = (range / 2) - (noise % range);
            holdValue = value;
          }
          // else if (phase + phaseIncrement * numSamples < phase)  // (line 82)
          else if (Integer.compareUnsigned(
                  (int) ((phase & 0xFFFFFFFFL) + (phaseIncrement & 0xFFFFFFFFL) * numSamples),
                  phase)
              < 0) {
            // holdValue = add_saturate((holdValue / -16) + (range / 2) - CONG % range, holdValue);
            // (line 93)
            int noise = Functions.getNoise() & 0x7FFFFFFF;
            holdValue =
                Functions.add_saturate(
                    (holdValue / -16) + (range / 2) - (noise % range), holdValue);
            value = holdValue;
          } else {
            value = holdValue; // (line 97)
          }
          break;
        }

      case WARBLER:
        {
          // phaseIncrement *= 2; warble(numSamples, phaseIncrement); value = holdValue;
          // (lines 102-105)
          int pi2 = phaseIncrement * 2;
          warble(numSamples, pi2);
          value = holdValue;
          break;
        }

      default:
        value = 0;
        break;
    }

    // phase += phaseIncrement * numSamples;  // (line 108)
    phase =
        (int) (((phase & 0xFFFFFFFFL) + (phaseIncrement & 0xFFFFFFFFL) * numSamples) & 0xFFFFFFFFL);
    return value;
  }

  // ── warble (lfo.h:111-121) ──

  /**
   * Second-order filtered random walk. Target is set per cycle; speed ramps toward the target
   * difference, and holdValue tracks via saturated accumulation. (lfo.h:111-121)
   */
  private void warble(int numSamples, int phaseIncrement) {
    // if (phase + phaseIncrement * numSamples < phase)  // (line 112)
    if (Integer.compareUnsigned(
            (int) ((phase & 0xFFFFFFFFL) + (phaseIncrement & 0xFFFFFFFFL) * numSamples), phase)
        < 0) {
      target = Functions.getNoise(); // CONG  // (line 113)
      speed = 0; // (line 114)
    }
    // int targetSpeed = target - holdValue;  // (line 118)
    int targetSpeed = target - holdValue;
    // speed = speed + numSamples * (multiply_32x32_rshift32(targetSpeed, phaseIncrement >> 8));
    // (line 119)
    speed =
        speed + numSamples * Functions.multiply_32x32_rshift32(targetSpeed, phaseIncrement >> 8);
    // holdValue = add_saturate(holdValue, speed);  // (line 120)
    holdValue = Functions.add_saturate(holdValue, speed);
  }
}
