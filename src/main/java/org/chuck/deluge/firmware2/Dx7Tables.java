package org.chuck.deluge.firmware2;

/**
 * Faithful port of the firmware's DX7 lookup tables ({@code math_lut.cpp}).
 * The {@code exp2tab} is generated at class init time using the same formula
 * as {@code exp2_init()}: an interleaved delta+value table for fast linear
 * interpolation of 2^x on the range [0, 1).
 *
 * <p>Also contains the DX7 operator gain table mapping level (0-99) to Q31 gain.
 */
public final class Dx7Tables {

  private Dx7Tables() {}

  /** EXP2_LG_N_SAMPLES = 10 (engine.h:30). Table size = 1024, interleaved = 2048 ints. */
  static final int EXP2_LG_N_SAMPLES = 10;
  static final int EXP2_N_SAMPLES = 1 << EXP2_LG_N_SAMPLES;

  /**
   * Interleaved delta+value table for Exp2::lookup.
   * Index [i*2] = delta (slope), [i*2+1] = value at sample i.
   * Generated at init time, matching exp2_init().
   */
  public static final int[] EXP2_TAB = new int[EXP2_N_SAMPLES << 1];

  /** DX7 operator gain table: gain[level] for level 0 (max) to 99 (silent), Q31. */
  public static final int[] gainTable = new int[100];

  static {
    // Port of exp2_init(): build 2^x table for x in [0, 1)
    double inc = Math.pow(2.0, 1.0 / EXP2_N_SAMPLES);
    double y = 1 << 24; // Q24 representation of 1.0
    for (int i = 0; i < EXP2_N_SAMPLES; i++) {
      EXP2_TAB[(i << 1) + 1] = (int) Math.floor(y + 0.5);
      y *= inc;
    }
    // Compute deltas (slopes)
    for (int i = 0; i < EXP2_N_SAMPLES - 1; i++) {
      EXP2_TAB[i << 1] = EXP2_TAB[(i << 1) + 3] - EXP2_TAB[(i << 1) + 1];
    }
    // Last delta: extrapolate to 2^31
    EXP2_TAB[(EXP2_N_SAMPLES << 1) - 2] =
        (1 << 31) - EXP2_TAB[(EXP2_N_SAMPLES << 1) - 1];

    // Build DX7 operator gain table (0 = full, 99 = silent)
    for (int lvl = 0; lvl < 100; lvl++) {
      // level → attenuation via exp2 lookup
      // Input: (lvl / 99.0) * (14 << 24) → attenuation in Q24
      // Then 2^(-atten) → gain
      double atten = (lvl / 99.0) * (14.0 * (1 << 24));
      double g = Math.pow(2.0, -atten / (double) (1 << 24));
      gainTable[lvl] = (int) (g * (double) (1 << 24));
    }
  }

  /**
   * Port of Exp2::lookup.  Q24 input → Q24 output (linear gain).
   * (math_lut.h:17-26)
   */
  public static int exp2Lookup(int x) {
    final int SHIFT = 24 - EXP2_LG_N_SAMPLES;
    int lowbits = x & ((1 << SHIFT) - 1);
    int xInt = (x >> (SHIFT - 1)) & ((EXP2_N_SAMPLES - 1) << 1);
    int dy = EXP2_TAB[xInt];
    int y0 = EXP2_TAB[xInt + 1];
    int y = y0 + (int) (((long) dy * (long) lowbits) >> SHIFT);
    return y >> (6 - (x >> 24));
  }
}
