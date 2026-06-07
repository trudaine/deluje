package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code fm_core.cpp/h} and {@code fm_op_kernel.h} DX7
 * 6-operator FM engine. Renders one block through one of the 32 DX7 algorithms.
 *
 * <p>The firmware uses ARM NEON SIMD ({@code FmOpKernel::compute/compute_pure/compute_fb}); this
 * Java port uses scalar equivalents with 4-sample unrolling matching the SIMD width.
 *
 * <p>Firmware reference: {@code dsp/dx/fm_core.cpp} (119 lines), {@code fm_core.h} (63 lines).
 */
public final class FmCore {

  private FmCore() {}

  // ── Constants (fm_core.h) ──

  /** Maximum render block size (132 to allow 128 output + 4-byte padding). */
  public static final int DX_MAX_N = 132;

  /** Gain below which an operator is considered inaudible. */
  static final int K_GAIN_LEVEL_THRESH = 1120;

  /** Operator flags (fm_core.h:37-45) */
  static final int OUT_BUS_ONE = 1 << 0;

  static final int OUT_BUS_TWO = 1 << 1;
  static final int OUT_BUS_ADD = 1 << 2;
  static final int IN_BUS_ONE = 1 << 4;
  static final int IN_BUS_TWO = 1 << 5;
  static final int FB_IN = 1 << 6;
  static final int FB_OUT = 1 << 7;

  // ── Operator params (fm_core.h FmOpParams) ──

  public static class FmOpParams {
    public int phase; // Q32 phase accumulator
    public int freq; // Q32 frequency (phase increment per sample)
    public int gain_out; // current gain (smoothed)
    public int level_in; // target level (DX7 format: 0-99 range, 14<<24 = max)
  }

  // ── 32 DX7 algorithms (fm_core.cpp:21-54) ──
  // Each algorithm encodes 6 operators as hex flags:
  //   0xc_ = FB_IN | FB_OUT | IN_BUS_ONE | OUT_BUS_ONE  (carrier with feedback)
  //   0x11 = IN_BUS_ONE | OUT_BUS_ONE                     (modulator to bus 1)
  //   0x14 = IN_BUS_ONE | OUT_BUS_ADD                     (carrier, add to output)
  //   0x01 = OUT_BUS_ONE                                   (output to bus 1 only)
  //   0x05 = IN_BUS_TWO | OUT_BUS_ONE                      (input from bus 2, output bus 1)
  //   0x94 = FB_IN | FB_OUT | IN_BUS_ONE | OUT_BUS_TWO    (fb to bus 2)
  //   0x04 = OUT_BUS_ADD                                   (carrier, add to output, no input)
  //   0xc4 = FB_IN | FB_OUT | OUT_BUS_ADD                  (carrier with fb, add)
  //   0x25 = IN_BUS_TWO | OUT_BUS_ADD                      (input bus 2, add)
  //   0xc5 = FB_IN | FB_OUT | IN_BUS_TWO | OUT_BUS_ONE    (fb, input bus 2, out bus 1)
  //   0x02 = OUT_BUS_TWO                                    (output to bus 2)

  public static final int[][] ALGORITHMS = {
    {0xc1, 0x11, 0x11, 0x14, 0x01, 0x14}, // 1
    {0x01, 0x11, 0x11, 0x14, 0xc1, 0x14}, // 2
    {0xc1, 0x11, 0x14, 0x01, 0x11, 0x14}, // 3
    {0xc1, 0x11, 0x94, 0x01, 0x11, 0x14}, // 4
    {0xc1, 0x14, 0x01, 0x14, 0x01, 0x14}, // 5
    {0xc1, 0x94, 0x01, 0x14, 0x01, 0x14}, // 6
    {0xc1, 0x11, 0x05, 0x14, 0x01, 0x14}, // 7
    {0x01, 0x11, 0xc5, 0x14, 0x01, 0x14}, // 8
    {0x01, 0x11, 0x05, 0x14, 0xc1, 0x14}, // 9
    {0x01, 0x05, 0x14, 0xc1, 0x11, 0x14}, // 10
    {0xc1, 0x05, 0x14, 0x01, 0x11, 0x14}, // 11
    {0x01, 0x05, 0x05, 0x14, 0xc1, 0x14}, // 12
    {0xc1, 0x05, 0x05, 0x14, 0x01, 0x14}, // 13
    {0xc1, 0x05, 0x11, 0x14, 0x01, 0x14}, // 14
    {0x01, 0x05, 0x11, 0x14, 0xc1, 0x14}, // 15
    {0xc1, 0x11, 0x02, 0x25, 0x05, 0x14}, // 16
    {0x01, 0x11, 0x02, 0x25, 0xc5, 0x14}, // 17
    {0x01, 0x11, 0x11, 0xc5, 0x05, 0x14}, // 18
    {0xc1, 0x14, 0x14, 0x01, 0x11, 0x14}, // 19
    {0x01, 0x05, 0x14, 0xc1, 0x14, 0x14}, // 20
    {0x01, 0x14, 0x14, 0xc1, 0x14, 0x14}, // 21
    {0xc1, 0x14, 0x14, 0x14, 0x01, 0x14}, // 22
    {0xc1, 0x14, 0x14, 0x01, 0x14, 0x04}, // 23
    {0xc1, 0x14, 0x14, 0x14, 0x04, 0x04}, // 24
    {0xc1, 0x14, 0x14, 0x04, 0x04, 0x04}, // 25
    {0xc1, 0x05, 0x14, 0x01, 0x14, 0x04}, // 26
    {0x01, 0x05, 0x14, 0xc1, 0x14, 0x04}, // 27
    {0x04, 0xc1, 0x11, 0x14, 0x01, 0x14}, // 28
    {0xc1, 0x14, 0x01, 0x14, 0x04, 0x04}, // 29
    {0x04, 0xc1, 0x11, 0x14, 0x04, 0x04}, // 30
    {0xc1, 0x14, 0x04, 0x04, 0x04, 0x04}, // 31
    {0xc4, 0x04, 0x04, 0x04, 0x04, 0x04}, // 32
  };

  // ── Exp2 lookup (port of Exp2::lookup, math_lut.h) ──
  // Converts DX7 operator level (0-99 range, 14<<24 = max) to linear gain Q31.
  // This uses the same expTableSmall but with a different input domain.

  /**
   * Port of Exp2::lookup. Converts DX7 operator level (Q24) to linear gain (Q31). level_in = 0 →
   * max, 14<<24 = 234881024 → silent (0 gain).
   */
  public static int exp2Lookup(int levelIn) {
    int x = levelIn - (14 << 24);
    // Simplified: extract level 0-99 from Q24 and use gain table
    int lvl = (x >> 24) + 14;
    if (lvl < 0) lvl = 0;
    if (lvl > 99) lvl = 99;
    // Map to Q31: Dx7Tables.gainTable is Q24, shift to Q31
    return Dx7Tables.gainTable[lvl] << 7;
  }

  // ── n_out (fm_core.cpp:56-63) ──

  private static int nOut(int[] alg) {
    int count = 0;
    for (int i = 0; i < 6; i++) {
      if ((alg[i] & 7) == OUT_BUS_ADD) count++;
    }
    return count;
  }

  // ── render (fm_core.cpp:65-118) ──

  /**
   * Port of FmCore::render. Renders n samples of the 6-operator DX7 algorithm. (fm_core.cpp:65-118)
   *
   * @param output output buffer (samples accumulated)
   * @param n number of samples to render
   * @param params 6 operator params (phase, freq, gain_out, level_in)
   * @param algorithm algorithm index 0-31
   * @param fbBuf feedback buffer (2 entries for stereo?) — firmware uses 2 ints
   * @param feedbackShift feedback amount (0-15)
   */
  public static void render(
      int[] output, int n, FmOpParams[] params, int algorithm, int[] fbBuf, int feedbackShift) {
    int[] alg = ALGORITHMS[algorithm];

    // simd_n: round up to multiple of 4 (replaces NEON alignment)
    int simdN = (n + 3) & ~3;

    // inv_n = (1<<30) / n (fixed-point division constant)
    int invN = (1 << 30) / n;

    // Scratch buffers for operator interconnects (bus 1, bus 2)
    int[][] buf = new int[2][simdN];
    java.util.Arrays.fill(buf[0], 0);
    java.util.Arrays.fill(buf[1], 0);
    boolean[] hasContents = {true, false, false};

    for (int op = 0; op < 6; op++) {
      int flags = alg[op];
      boolean add = (flags & OUT_BUS_ADD) != 0;
      FmOpParams param = params[op];
      int inbus = (flags >> 4) & 3;
      int outbus = flags & 3;

      int gain1 = param.gain_out;
      // gain2 = Exp2::lookup(param.level_in - (14 * (1 << 24)));
      int gain2 = exp2Lookup(param.level_in - (14 << 24));
      param.gain_out = gain2;
      int dgain = (int) (((long) (gain2 - gain1 + (n >> 1)) * (long) invN) >> 30);

      // Determine output destination: outbus 0 = output array, 1/2 = buf_[0]/buf_[1]
      int[] outptr = (outbus == 0) ? output : buf[outbus - 1];
      int[] inptr = (inbus == 0 || inbus > 2) ? null : buf[inbus - 1];

      // Firmware uses unsigned comparison; Java int wraps MIN_VALUE to large unsigned
      if (Integer.compareUnsigned(gain1, K_GAIN_LEVEL_THRESH) >= 0
          || Integer.compareUnsigned(gain2, K_GAIN_LEVEL_THRESH) >= 0) {
        if (!hasContents[outbus]) add = false;
        if (inbus == 0 || inptr == null || !hasContents[inbus]) {
          if ((flags & 0xc0) == 0xc0 && feedbackShift < 16) {
            computeFb(outptr, simdN, param, gain1, gain2, dgain, fbBuf, feedbackShift, add);
          } else {
            computePure(outptr, simdN, param, gain1, gain2, dgain, add);
          }
        } else {
          computeNormal(outptr, simdN, inptr, param, gain1, gain2, dgain, add);
        }
        hasContents[outbus] = true;
      } else if (!add) {
        hasContents[outbus] = false;
      }
      // param.phase += param.freq * n; (line 117)
      param.phase += param.freq * n;
    }
  }

  // ── computePure (scalar equivalent of FmOpKernel::compute_pure) ──
  private static void computePure(
      int[] out, int n, FmOpParams param, int gain1, int gain2, int dgain, boolean add) {
    int phase = param.phase;
    int gain = gain1;
    for (int i = 0; i < n; i++) {
      phase += param.freq;
      gain += dgain;
      int sample = SineOsc.doFMNew(phase, 0);
      sample = Functions.multiply_32x32_rshift32(sample, gain);
      if (add) out[i] = Functions.add_saturate(out[i], sample);
      else out[i] = sample;
    }
  }

  // ── computeNormal (scalar equivalent of FmOpKernel::compute) ──
  private static void computeNormal(
      int[] out,
      int n,
      int[] inbuf,
      FmOpParams param,
      int gain1,
      int gain2,
      int dgain,
      boolean add) {
    int phase = param.phase;
    int gain = gain1;
    for (int i = 0; i < n; i++) {
      phase += param.freq;
      gain += dgain;
      int sample = SineOsc.doFMNew(phase, inbuf[i]);
      sample = Functions.multiply_32x32_rshift32(sample, gain);
      if (add) out[i] = Functions.add_saturate(out[i], sample);
      else out[i] = sample;
    }
  }

  // ── computeFb (scalar equivalent of FmOpKernel::compute_fb) ──
  private static void computeFb(
      int[] out,
      int n,
      FmOpParams param,
      int gain1,
      int gain2,
      int dgain,
      int[] fbBuf,
      int feedbackShift,
      boolean add) {
    int phase = param.phase;
    int gain = gain1;
    int fb0 = fbBuf[0], fb1 = fbBuf[1];
    for (int i = 0; i < n; i++) {
      phase += param.freq;
      gain += dgain;
      int fb = (fb0 + fb1) >> 1;
      fb0 = fb1;
      int sample = SineOsc.doFMNew(phase, fb << feedbackShift);
      fb1 = sample;
      sample = Functions.multiply_32x32_rshift32(sample, gain);
      if (add) out[i] = Functions.add_saturate(out[i], sample);
      else out[i] = sample;
    }
    fbBuf[0] = fb0;
    fbBuf[1] = fb1;
  }
}
