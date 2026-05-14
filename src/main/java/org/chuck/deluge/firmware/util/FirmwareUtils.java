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
    if (magnitude >= 0) {
      int bits = 31 - magnitude;
      int limit = 1 << bits;
      if (number >= limit) return ONE;
      if (number < -limit) return NEGATIVE_ONE;
      return number << magnitude;
    }
    return number >>> (-magnitude);
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
    jcong = 69069 * jcong + 12345;
    return jcong;
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
    int whichValue = input >>> (numBitsInInput - numBitsInTableSize);
    int rshiftAmount = numBitsInInput - 16 - numBitsInTableSize;
    int rshifted;
    if (rshiftAmount >= 0) rshifted = input >>> rshiftAmount;
    else rshifted = input << (-rshiftAmount);

    int strength2 = rshifted & 65535;
    int strength1 = 65536 - strength2;
    return (table[whichValue] * strength1 + table[whichValue + 1] * strength2);
  }

  public static int instantTan(int input) {
    int whichValue = input >>> 25;
    int howMuchFurther = (input << 6) & 2147483647;
    int value1 = LookupTables.tanTable[whichValue];
    int value2 = LookupTables.tanTable[whichValue + 1];
    return (multiply_32x32_rshift32(value2, howMuchFurther)
            + multiply_32x32_rshift32(value1, 2147483647 - howMuchFurther))
        << 1;
  }

  public static int getTanHAntialiased(int input, int[] lastWorkingValue, int saturationAmount) {
    lastWorkingValue[0] = (int) (lshiftAndSaturateUnknown(input, saturationAmount) + 2147483648L);
    return getTanHUnknown(input, saturationAmount) << 1;
  }

  public static int getTanHUnknown(int input, int saturationAmount) {
    int workingValue;
    if (saturationAmount != 0)
      workingValue = (int) (lshiftAndSaturateUnknown(input, saturationAmount) + 2147483648L);
    else workingValue = (int) (input + 2147483648L);

    return interpolateTableSigned(workingValue, 32, LookupTables.tanHSmall, 8)
        >> (saturationAmount + 2);
  }

  public static int lshiftAndSaturateUnknown(int val, int lshift) {
    return signedSaturateOperandUnknown(val, 32 - lshift) << lshift;
  }

  public static int signedSaturateOperandUnknown(int val, int bits) {
    int limit = 1 << (bits - 1);
    if (val >= limit) return limit - 1;
    if (val < -limit) return -limit;
    return val;
  }

  public static int lshiftAndSaturate(int val, int lshift) {
    int bits = 32 - lshift;
    int limit = 1 << (bits - 1);
    if (val >= limit) return limit - 1;
    if (val < -limit) return -limit;
    return val << lshift;
  }
}
