package org.deluge.firmware.util;

/**
 * Utility class for Q31 fixed-point arithmetic, matching the behavior of the Deluge firmware. Q31
 * represents numbers with 1 sign bit and 31 fractional bits. The range is [-1.0, 1.0), where 1.0 is
 * approximated by Integer.MAX_VALUE.
 */
public class Q31 {
  public static final int ONE = 2147483647;
  public static final int NEGATIVE_ONE = -2147483648;

  /**
   * Multiplies two Q31 numbers and returns the result as a Q31 number (shifted by 31). Matches
   * q31_mult in the firmware.
   */
  public static int mult(int a, int b) {
    return (int) (((long) a * b) >> 31);
  }

  /**
   * Multiplies two 32-bit integers as if they were Q32, returning the high 32 bits. Matches
   * multiply_32x32_rshift32 in the firmware.
   */
  public static int multiply_32x32_rshift32(int a, int b) {
    return (int) (((long) a * b) >> 32);
  }

  /**
   * Multiplies two 32-bit integers and rounds the result. Matches multiply_32x32_rshift32_rounded
   * in the firmware.
   */
  public static int multiply_32x32_rshift32_rounded(int a, int b) {
    return (int) (((long) a * b + 0x80000000L) >> 32);
  }

  /**
   * Multiply-accumulate: sum + (a * b) >> 32. Matches multiply_accumulate_32x32_rshift32 in the
   * firmware.
   */
  public static int multiply_accumulate_32x32_rshift32(int sum, int a, int b) {
    return sum + (int) (((long) a * b) >> 32);
  }

  /**
   * Multiply-accumulate rounded: sum + (a * b + round) >> 32. Matches
   * multiply_accumulate_32x32_rshift32_rounded in the firmware.
   */
  public static int multiply_accumulate_32x32_rshift32_rounded(int sum, int a, int b) {
    return sum + (int) (((long) a * b + 0x80000000L) >> 32);
  }

  public static int fromFloat(float val) {
    if (val >= 1.0f) return ONE;
    if (val <= -1.0f) return NEGATIVE_ONE;
    return (int) (val * 2147483648.0);
  }

  public static float toFloat(int val) {
    return (float) (val / 2147483648.0);
  }

  public static int subSaturate(int a, int b) {
    long res = (long) a - b;
    if (res > ONE) return ONE;
    if (res < NEGATIVE_ONE) return NEGATIVE_ONE;
    return (int) res;
  }

  public static int signedSaturate(int val, int sat) {
    int limit = (1 << (sat - 1)) - 1;
    if (val > limit) return limit;
    if (val < -limit - 1) return -limit - 1;
    return val;
  }

  public static int lshiftAndSaturate(int val, int lshift) {
    return signedSaturate(val, 32 - lshift) << lshift;
  }

  public static int addSaturate(int a, int b) {
    long res = (long) a + b;
    if (res > ONE) return ONE;
    if (res < NEGATIVE_ONE) return NEGATIVE_ONE;
    return (int) res;
  }
}
