package org.chuck.deluge.firmware2;

/**
 * Faithful port of the firmware's DX7 lookup tables ({@code math_lut.cpp}). The {@code exp2tab} is
 * generated at class init time using the same formula as {@code exp2_init()}: an interleaved
 * delta+value table for fast linear interpolation of 2^x on the range [0, 1).
 *
 * <p>Also contains the DX7 operator gain table mapping level (0-99) to Q31 gain.
 */
public final class Dx7Tables {

  private Dx7Tables() {}

  /** EXP2_LG_N_SAMPLES = 10 (engine.h:30). Table size = 1024, interleaved = 2048 ints. */
  static final int EXP2_LG_N_SAMPLES = 10;

  static final int EXP2_N_SAMPLES = 1 << EXP2_LG_N_SAMPLES;

  /**
   * Interleaved delta+value table for Exp2::lookup. Index [i*2] = delta (slope), [i*2+1] = value at
   * sample i. Generated at init time, matching exp2_init().
   */
  public static final int[] EXP2_TAB = new int[EXP2_N_SAMPLES << 1];

  /** DX7 operator gain table: gain[level] for level 0 (max) to 99 (silent), Q31. */
  public static final int[] gainTable = new int[100];

  // ── sin / tanh / freq tables (engine.h:34-51; SIN_DELTA defined) ──
  static final int SIN_LG_N_SAMPLES = 10;
  static final int SIN_N_SAMPLES = 1 << SIN_LG_N_SAMPLES;
  public static final int[] SIN_TAB =
      new int[SIN_N_SAMPLES << 1]; // SIN_DELTA: interleaved delta+value

  static final int TANH_LG_N_SAMPLES = 10;
  static final int TANH_N_SAMPLES = 1 << TANH_LG_N_SAMPLES;
  public static final int[] TANH_TAB = new int[TANH_N_SAMPLES << 1];

  static final int FREQ_LG_N_SAMPLES = 10;
  static final int FREQ_N_SAMPLES = 1 << FREQ_LG_N_SAMPLES;
  public static final int[] FREQ_LUT = new int[FREQ_N_SAMPLES + 1];

  static final int SAMPLE_SHIFT = 24 - FREQ_LG_N_SAMPLES; // math_lut.cpp:94
  static final int MAX_LOGFREQ_INT = 20; // math_lut.cpp:95

  static {
    // Port of exp2_init(): build 2^x table for x in [0, 1). C: double y = 1 << 30;
    // (math_lut.cpp:28)
    // exp2Lookup then does `y >> (6 - (x>>24))`, bringing the 2^30-scale table to Q24 output.
    double inc = Math.pow(2.0, 1.0 / EXP2_N_SAMPLES);
    double y = 1 << 30;
    for (int i = 0; i < EXP2_N_SAMPLES; i++) {
      EXP2_TAB[(i << 1) + 1] = (int) Math.floor(y + 0.5);
      y *= inc;
    }
    // Compute deltas (slopes)
    for (int i = 0; i < EXP2_N_SAMPLES - 1; i++) {
      EXP2_TAB[i << 1] = EXP2_TAB[(i << 1) + 3] - EXP2_TAB[(i << 1) + 1];
    }
    // Last delta: extrapolate to 2^31
    EXP2_TAB[(EXP2_N_SAMPLES << 1) - 2] = (1 << 31) - EXP2_TAB[(EXP2_N_SAMPLES << 1) - 1];

    // Build DX7 operator gain table (0 = full, 99 = silent)
    for (int lvl = 0; lvl < 100; lvl++) {
      // level → attenuation via exp2 lookup
      // Input: (lvl / 99.0) * (14 << 24) → attenuation in Q24
      // Then 2^(-atten) → gain
      double atten = (lvl / 99.0) * (14.0 * (1 << 24));
      double g = Math.pow(2.0, -atten / (double) (1 << 24));
      gainTable[lvl] = (int) (g * (double) (1 << 24));
    }

    // Port of sin_init() (math_lut.cpp:64-92), SIN_DELTA format.
    {
      final int R = 1 << 29;
      double dphase = 2 * Math.PI / SIN_N_SAMPLES;
      int c = (int) Math.floor(Math.cos(dphase) * (1 << 30) + 0.5);
      int s = (int) Math.floor(Math.sin(dphase) * (1 << 30) + 0.5);
      int u = 1 << 30;
      int v = 0;
      for (int i = 0; i < SIN_N_SAMPLES / 2; i++) {
        SIN_TAB[(i << 1) + 1] = (v + 32) >> 6;
        SIN_TAB[((i + SIN_N_SAMPLES / 2) << 1) + 1] = -((v + 32) >> 6);
        int t = (int) (((long) u * s + (long) v * c + R) >> 30);
        u = (int) (((long) u * c - (long) v * s + R) >> 30);
        v = t;
      }
      for (int i = 0; i < SIN_N_SAMPLES - 1; i++) {
        SIN_TAB[i << 1] = SIN_TAB[(i << 1) + 3] - SIN_TAB[(i << 1) + 1];
      }
      SIN_TAB[(SIN_N_SAMPLES << 1) - 2] = -SIN_TAB[(SIN_N_SAMPLES << 1) - 1];
    }

    // Port of tanh_init() (math_lut.cpp:42-63); dtanh(y) = 1 - y*y; 4th-order Runge-Kutta.
    {
      double step = 4.0 / TANH_N_SAMPLES;
      double yy = 0;
      for (int i = 0; i < TANH_N_SAMPLES; i++) {
        TANH_TAB[(i << 1) + 1] = (int) ((1 << 24) * yy + 0.5);
        double k1 = 1 - yy * yy;
        double k2 = 1 - (yy + 0.5 * step * k1) * (yy + 0.5 * step * k1);
        double k3 = 1 - (yy + 0.5 * step * k2) * (yy + 0.5 * step * k2);
        double k4 = 1 - (yy + step * k3) * (yy + step * k3);
        double dy = (step / 6) * (k1 + k4 + 2 * (k2 + k3));
        yy += dy;
      }
      for (int i = 0; i < TANH_N_SAMPLES - 1; i++) {
        TANH_TAB[i << 1] = TANH_TAB[(i << 1) + 3] - TANH_TAB[(i << 1) + 1];
      }
      int lasty = (int) ((1 << 24) * yy + 0.5);
      TANH_TAB[(TANH_N_SAMPLES << 1) - 2] = lasty - TANH_TAB[(TANH_N_SAMPLES << 1) - 1];
    }

    // Port of freq_lut_init() (math_lut.cpp:96-103), sample_rate = 44100.
    {
      double yf = (double) (1L << (24 + MAX_LOGFREQ_INT)) / 44100.0;
      double inc2 = Math.pow(2, 1.0 / FREQ_N_SAMPLES);
      for (int i = 0; i < FREQ_N_SAMPLES + 1; i++) {
        FREQ_LUT[i] = (int) Math.floor(yf + 0.5);
        yf *= inc2;
      }
    }
  }

  /** Port of Exp2::lookup. Q24 input → Q24 output (linear gain). (math_lut.h:17-26) */
  public static int exp2Lookup(int x) {
    final int SHIFT = 24 - EXP2_LG_N_SAMPLES;
    int lowbits = x & ((1 << SHIFT) - 1);
    int xInt = (x >> (SHIFT - 1)) & ((EXP2_N_SAMPLES - 1) << 1);
    int dy = EXP2_TAB[xInt];
    int y0 = EXP2_TAB[xInt + 1];
    int y = y0 + (int) (((long) dy * (long) lowbits) >> SHIFT);
    return y >> (6 - (x >> 24));
  }

  /** Port of Sin::lookup (math_lut.h:55-72, SIN_DELTA). */
  public static int sinLookup(int phase) {
    final int SHIFT = 24 - SIN_LG_N_SAMPLES;
    int lowbits = phase & ((1 << SHIFT) - 1);
    int phaseInt = (phase >> (SHIFT - 1)) & ((SIN_N_SAMPLES - 1) << 1);
    int dy = SIN_TAB[phaseInt];
    int y0 = SIN_TAB[phaseInt + 1];
    return y0 + (int) (((long) dy * (long) lowbits) >> SHIFT);
  }

  /** Port of Tanh::lookup (math_lut.h:31-50). Q24 in, Q24 out. */
  public static int tanhLookup(int x) {
    int signum = x >> 31;
    x ^= signum;
    if (x >= (4 << 24)) {
      if (x >= (17 << 23)) {
        return signum ^ (1 << 24);
      }
      int sx = (int) (((long) -48408812 * (long) x) >> 24);
      return signum ^ ((1 << 24) - 2 * exp2Lookup(sx));
    } else {
      final int SHIFT = 26 - TANH_LG_N_SAMPLES;
      int lowbits = x & ((1 << SHIFT) - 1);
      int xInt = (x >> (SHIFT - 1)) & ((TANH_N_SAMPLES - 1) << 1);
      int dy = TANH_TAB[xInt];
      int y0 = TANH_TAB[xInt + 1];
      int y = y0 + (int) (((long) dy * (long) lowbits) >> SHIFT);
      return y ^ signum;
    }
  }

  /** Port of Freqlut::lookup (math_lut.cpp:107-116). logfreq → phase increment. */
  public static int freqLookup(int logfreq) {
    int ix = (logfreq & 0xffffff) >> SAMPLE_SHIFT;
    int y0 = FREQ_LUT[ix];
    int y1 = FREQ_LUT[ix + 1];
    int lowbits = logfreq & ((1 << SAMPLE_SHIFT) - 1);
    int y = y0 + (int) (((long) (y1 - y0) * (long) lowbits) >> SAMPLE_SHIFT);
    int hibits = logfreq >> 24;
    return y >> (MAX_LOGFREQ_INT - hibits);
  }
}
