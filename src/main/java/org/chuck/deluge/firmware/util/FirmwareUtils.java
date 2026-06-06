package org.chuck.deluge.firmware.util;

import static org.chuck.deluge.firmware.util.Q31.*;

public class FirmwareUtils {

  private static int jcong = 12345;

  public static int getMagnitude(int input) {
    if (input == 0) return 0;
    return 31 - Integer.numberOfLeadingZeros(input);
  }

  public static int fastPythag(int x, int y) {
    if (x < 0) x = -x;
    if (y < 0) y = -y;
    if (y > x) {
      int a = y;
      y = x;
      x = a;
    }

    if (x == 0) return 0;

    // Ratio y/x is between 0 and 1.0
    // The firmware uses a table. We'll use the standard alpha-max beta-min algorithm
    // which is a common hardware approximation for hypot(x,y):
    // 0.96 * max + 0.4 * min
    return (int) (0.96 * x + 0.398 * y);
  }

  public static int increaseMagnitudeAndSaturate(int number, int magnitude) {
    // Faithful port: firmware uses `magnitude > 0` for the saturating left shift and an ARITHMETIC
    // right shift for magnitude <= 0. (The prior Java used `>= 0`, which sent magnitude==0 through
    // a
    // `1 << 31` that overflowed and forced saturation — breaking getExp for cutoff/LFO knobs that
    // land at magnitudeIncrease==0 — and used an unsigned `>>>` that mangled negative inputs.)
    if (magnitude > 0) {
      return lshiftAndSaturate(number, magnitude);
    }
    return number >> (-magnitude);
  }

  public static int getExp(int presetValue, int adjustment) {
    int magnitudeIncrease = (adjustment >> 26) + 2;
    // fine adjustment
    int adjustedPresetValue =
        multiply_32x32_rshift32(
            presetValue,
            interpolateTable(adjustment & 67108863, 26, LookupTables.expTableSmall, 8));
    return increaseMagnitudeAndSaturate(adjustedPresetValue, magnitudeIncrease);
  }

  public static int getNoise() {
    jcong = 69069 * jcong + 1234567;
    return jcong;
  }

  public static void resetNoise() {
    jcong = 12345;
  }

  // ── Pan (port of shouldDoPanning) ──
  // The firmware pan is LINEAR: at centre both channels are full (1073741823); panned, the near
  // channel stays full and the far channel drops linearly to 0. panAmount is bipolar (0 = centre,
  // ±1073741824 = hard L/R). The resulting amplitude is "1.0" at 1073741823 (2^30) — apply as
  // multiply_32x32_rshift32(sample, amp) << 2. (The previous Java cos/sin constant-power law made
  // centre 0.707/0.707 — 3 dB quiet — and a mis-encoded centre rendered off to one side.)
  public static int panAmplitudeL(int panAmount) {
    if (panAmount == 0) return 1073741823;
    int panOffset = Math.max(-1073741824, Math.min(1073741824, panAmount));
    return (panAmount <= 0) ? 1073741823 : (1073741824 - panOffset);
  }

  public static int panAmplitudeR(int panAmount) {
    if (panAmount == 0) return 1073741823;
    int panOffset = Math.max(-1073741824, Math.min(1073741824, panAmount));
    return (panAmount >= 0) ? 1073741823 : (1073741824 + panOffset);
  }

  // ── Deluge patched-param final-value curves (port of util/functions.cpp) ──

  /**
   * Combines the stored knob value of a parameter into the "patched value" domain (±2^29 ≈ "1"),
   * with no patch cables — the firmware's {@code combineCablesLinear(nullptr, ...)}. {@code
   * paramRange} is {@code getParamRange(p)} (2^30 for most volume/feedback params).
   */
  public static int combineCablesLinearNoCable(int storedValue, int paramRange) {
    return patchCombineLinearStep(536870912, storedValue, paramRange) - 536870912;
  }

  /**
   * One multiplicative step of the Deluge's linear cable combiner (port of
   * cableToLinearParamWithoutRangeAdjustment): folds a source value, scaled by its strength, into
   * the running combination. Seed {@code runningTotal} with 536870912 ("1"); the first call folds
   * in the stored knob value (strength = paramRange), subsequent calls fold each patch cable
   * (strength = cable amount). Subtract 536870912 from the final running total to get the patched
   * combo.
   */
  public static int patchCombineLinearStep(int runningTotal, int source, int strength) {
    int scaledSource = multiply_32x32_rshift32(source, strength);
    int madePositive = scaledSource + 536870912;
    int preLimits = multiply_32x32_rshift32(runningTotal, madePositive);
    return lshiftAndSaturate(preLimits, 3);
  }

  /**
   * One additive step of the exp cable combiner (port of cableToExpParamWithoutRangeAdjustment).
   * Seed {@code runningTotal} with 0; first call folds the stored knob (strength = paramRange),
   * then one call per cable (strength = cable amount). No final subtraction — pass the result
   * straight to {@link #getFinalParameterValueExp}.
   */
  public static int patchCombineExpStep(int runningTotal, int source, int strength) {
    return runningTotal + multiply_32x32_rshift32(source, strength);
  }

  /** Port of {@code getFinalParameterValueVolume} — parabola curve for volume params. */
  public static int getFinalParameterValueVolume(int paramNeutralValue, int patchedValue) {
    int positivePatchedValue = patchedValue + 536870912;
    positivePatchedValue = (positivePatchedValue >> 16) * (positivePatchedValue >> 15);
    return lshiftAndSaturate(multiply_32x32_rshift32(positivePatchedValue, paramNeutralValue), 5);
  }

  /** Port of {@code getFinalParameterValueLinear} — linear curve for non-volume params. */
  public static int getFinalParameterValueLinear(int paramNeutralValue, int patchedValue) {
    int positivePatchedValue = patchedValue + 536870912;
    return lshiftAndSaturate(multiply_32x32_rshift32(positivePatchedValue, paramNeutralValue), 3);
  }

  /**
   * Port of {@code getFinalParameterValueHybrid} — additive curve for hybrid params (pan, phase
   * width, wave index). Allows max output ±1073741824 (full pan range).
   */
  public static int getFinalParameterValueHybrid(int paramNeutralValue, int patchedValue) {
    int preLimits = (paramNeutralValue >> 2) + (patchedValue >> 1);
    return signedSaturate(preLimits, 32 - 3) << 2;
  }

  /** Port of {@code getFinalParameterValueExp} (= {@link #getExp}). */
  public static int getFinalParameterValueExp(int paramNeutralValue, int patchedValue) {
    return getExp(paramNeutralValue, patchedValue);
  }

  /**
   * Final amplitude of an uncabled volume param (e.g. FM modulator amount), from its raw stored Q31
   * knob value, applying the firmware's no-cable combine then the volume parabola.
   */
  public static int finalVolumeParam(int storedValue, int paramNeutralValue, int paramRange) {
    return getFinalParameterValueVolume(
        paramNeutralValue, combineCablesLinearNoCable(storedValue, paramRange));
  }

  /** As {@link #finalVolumeParam} but for linear (non-volume) params like feedback. */
  public static int finalLinearParam(int storedValue, int paramNeutralValue, int paramRange) {
    return getFinalParameterValueLinear(
        paramNeutralValue, combineCablesLinearNoCable(storedValue, paramRange));
  }

  /**
   * Port of {@code lookupReleaseRate}: maps a patched decay/release value to a per-sample increment
   * via the interpolated {@code releaseRateTable64}. Used (scaled by the param neutral) for the
   * envelope decay and release stages — the firmware's
   * getFinalParameterValueExpWithDumbEnvelopeHack uses this for those stages instead of plain
   * getExp.
   */
  public static int lookupReleaseRate(int input) {
    int magnitude = 24;
    int whichValue = input >> magnitude;
    int howMuchFurther = (input << (31 - magnitude)) & 2147483647;
    whichValue += 32; // Put it in the range 0 to 64
    if (whichValue < 0) {
      return LookupTables.releaseRateTable64[0];
    } else if (whichValue >= 64) {
      return LookupTables.releaseRateTable64[64];
    }
    int value1 = LookupTables.releaseRateTable64[whichValue];
    int value2 = LookupTables.releaseRateTable64[whichValue + 1];
    return (multiply_32x32_rshift32(value2, howMuchFurther)
            + multiply_32x32_rshift32(value1, 2147483647 - howMuchFurther))
        << 1;
  }

  /**
   * Port of {@code getFinalParameterValueExpWithDumbEnvelopeHack} for envelope rate params: attack
   * uses getExp on the negated patched value; decay/release use the release-rate table scaled by
   * the neutral. {@code stage}: 0=attack, 1=decay, 2=release.
   */
  public static int finalEnvRateParam(int paramNeutralValue, int patchedValue, int stage) {
    if (stage == 0) { // attack
      return getExp(paramNeutralValue, -patchedValue);
    }
    return multiply_32x32_rshift32(paramNeutralValue, lookupReleaseRate(patchedValue));
  }

  public static int quickLog(int input) {
    int magnitude = 31 - Integer.numberOfLeadingZeros(input);
    int inputLSBs = increaseMagnitude(input, 26 - magnitude);
    return (magnitude << 25) + (inputLSBs & ~(1 << 26));
  }

  public static int increaseMagnitude(int number, int magnitude) {
    if (magnitude >= 0) {
      return number << magnitude;
    }
    return number >>> (-magnitude);
  }

  public static int getDecay8(int input, int numBitsInInput) {
    return interpolateTable(input, numBitsInInput, LookupTables.decayTableSmall8, 8);
  }

  public static int getDecay4(int input, int numBitsInInput) {
    return interpolateTable(input, numBitsInInput, LookupTables.decayTableSmall4, 8);
  }

  public static int getSine(int phase, int numBitsInInput) {
    return interpolateTableSigned(phase, numBitsInInput, LookupTables.sineWaveSmall, 8);
  }

  public static int interpolateTable(
      int input, int numBitsInInput, short[] table, int numBitsInTableSize) {
    int whichValue = input >>> (numBitsInInput - numBitsInTableSize);
    int value1 = table[whichValue] & 0xFFFF;
    int value2 = table[whichValue + 1] & 0xFFFF;

    int rshiftAmount = numBitsInInput - 15 - numBitsInTableSize;
    int rshifted;
    if (rshiftAmount >= 0) {
      rshifted = input >>> rshiftAmount;
    } else {
      rshifted = input << (-rshiftAmount);
    }

    int strength2 = rshifted & 32767;
    int strength1 = 32768 - strength2;
    return value1 * strength1 + value2 * strength2;
  }

  public static int interpolateTableInverse(
      int tableValueBig, int numBitsInLookupOutput, short[] table, int numBitsInTableSize) {
    int tableValue = tableValueBig >> 15;
    int tableSize = 1 << numBitsInTableSize;

    int tableDirection = ((table[0] & 0xFFFF) < (table[tableSize] & 0xFFFF)) ? 1 : -1;

    if ((tableValue - (table[0] & 0xFFFF)) * tableDirection <= 0) {
      return 0;
    }
    if ((tableValue - (table[tableSize] & 0xFFFF)) * tableDirection >= 0) {
      return (1 << numBitsInLookupOutput) - 1;
    }

    int rangeStart = 0;
    int rangeEnd = tableSize;

    while (rangeStart + 1 < rangeEnd) {
      int examinePos = (rangeStart + rangeEnd) >> 1;
      if ((tableValue - (table[examinePos] & 0xFFFF)) * tableDirection >= 0) {
        rangeStart = examinePos;
      } else {
        rangeEnd = examinePos;
      }
    }

    long output = (long) rangeStart << (numBitsInLookupOutput - numBitsInTableSize);
    long diffY = (long) tableValueBig - ((long) (table[rangeStart] & 0xFFFF) << 15);
    long dx = 1L << (numBitsInLookupOutput - numBitsInTableSize);
    long dy = ((long) (table[rangeStart + 1] & 0xFFFF) - (long) (table[rangeStart] & 0xFFFF)) << 15;
    output += (diffY * dx) / dy;

    return (int) output;
  }

  public static int interpolateTableSigned(
      int input, int numBitsInInput, short[] table, int numBitsInTableSize) {
    long uInput = (long) input & 0xFFFFFFFFL;
    int whichValue = (int) (uInput >>> (32 - numBitsInTableSize));
    int rshiftAmount = 32 - 16 - numBitsInTableSize;
    int rshifted = (int) (uInput >>> rshiftAmount);

    int strength2 = rshifted & 65535;
    int strength1 = 65536 - strength2;
    long sum = (long) table[whichValue] * strength1 + (long) table[whichValue + 1] * strength2;
    return (int) sum;
  }

  public static int instantTan(int input) {
    if (input < 0) {
      input = 0;
    }
    int whichValue = input >>> 25;
    if (whichValue > 63) {
      return LookupTables.tanTable[64] << 1;
    }
    int howMuchFurther = (input << 6) & 2147483647;
    int value1 = LookupTables.tanTable[whichValue];
    int value2 = LookupTables.tanTable[whichValue + 1];
    int sum =
        multiply_32x32_rshift32(value2, howMuchFurther)
            + multiply_32x32_rshift32(value1, 2147483647 - howMuchFurther);
    return sum << 1;
  }

  public static int getTanHAntialiased(int input, int[] lastWorkingValue, int saturationAmount) {
    int workingValue = (int) (lshiftAndSaturateUnknown(input, saturationAmount) + 2147483648L);
    int toReturn =
        interpolateTableSigned2d(
                workingValue, lastWorkingValue[0], 32, 32, TanHLookupTable.tanH2d, 7, 6)
            >> (saturationAmount + 1);
    lastWorkingValue[0] = workingValue;
    return toReturn;
  }

  public static int interpolateTableSigned2d(
      int inputX,
      int inputY,
      int numBitsInInputX,
      int numBitsInInputY,
      short[][] table,
      int numBitsInTableSizeX,
      int numBitsInTableSizeY) {
    int whichValue = inputY >>> (numBitsInInputY - numBitsInTableSizeY);

    // Interpolate horizontal slices (row 'whichValue' and 'whichValue + 1')
    int value1 =
        interpolateTableSigned(inputX, numBitsInInputX, table[whichValue], numBitsInTableSizeX);
    int value2 =
        interpolateTableSigned(inputX, numBitsInInputX, table[whichValue + 1], numBitsInTableSizeX);

    int rshiftAmount = numBitsInInputY - 16 - numBitsInTableSizeY;
    int rshifted;
    if (rshiftAmount >= 0) {
      rshifted = inputY >>> rshiftAmount;
    } else {
      rshifted = inputY << (-rshiftAmount);
    }

    int strength2 = rshifted & 65535;
    int strength1 = 65536 - strength2;

    return (int) (((long) value1 * strength1 + (long) value2 * strength2) >> 16);
  }

  public static int getTanHUnknown(int input, int saturationAmount) {
    int workingValue;
    if (saturationAmount != 0) {
      workingValue = (int) (lshiftAndSaturateUnknown(input, saturationAmount) + 2147483648L);
    } else {
      workingValue = (int) (input + 2147483648L);
    }
    int result = interpolateTableSigned(workingValue, 32, LookupTables.tanHSmall, 8);
    return result >> (saturationAmount + 2);
  }

  public static int lshiftAndSaturateUnknown(int val, int lshift) {
    return signedSaturateOperandUnknown(val, 32 - lshift) << lshift;
  }

  public static int signedSaturateOperandUnknown(int val, int bits) {
    return org.chuck.deluge.firmware.util.Q31.signedSaturate(val, bits);
  }

  public static int lshiftAndSaturate(int val, int lshift) {
    int bits = 32 - lshift;
    int limit = 1 << (bits - 1);
    if (val >= limit) return (limit - 1) << lshift;
    if (val < -limit) return (-limit) << lshift;
    return val << lshift;
  }
}
