package org.deluge.firmware2;

import java.util.logging.Logger;

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
  private static final Logger LOGGER = Logger.getLogger(FmCore.class.getName());
  public static boolean debugRender = false;

  private FmCore() {}

  private static class FmScratch {
    int[][] buf = new int[2][0];
    boolean[] hasContents = new boolean[3];
  }

  private static final ThreadLocal<FmScratch> scratchHolder =
      ThreadLocal.withInitial(FmScratch::new);

  // ── Constants (fm_core.h) ──

  /** Maximum render block size (132 to allow 128 output + 4-byte padding). */
  public static final int DX_MAX_N = 132;

  /** Gain below which an operator is considered inaudible. */
  static final int K_GAIN_LEVEL_THRESH = 1120;

  /** Operator flags (fm_core.h:37-45) */
  static final int OUT_BUS_ONE = 1 << 0;

  static final int OUT_BUS_TWO = 1 << 1;
  public static final int OUT_BUS_ADD = 1 << 2;
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
   * Port of {@code Exp2::lookup(level_in - (14 << 24))} ({@code fm_core.cpp:87}, {@code
   * math_lut.h:17-26}). Converts DX7 operator level (Q24) to linear gain (Q24). {@code levelIn = 14
   * << 24} yields full scale ({@code 1 << 24}); {@code levelIn = 0} yields near silence.
   */
  public static int exp2Lookup(int levelIn) {
    return Dx7Tables.exp2Lookup(levelIn - (14 << 24));
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
   * <p>C {@code DxPatch::setEngineMode} (dx7note.h:64, dx7note.cpp:81-83) defaults {@code neon =
   * true} on hardware. When {@code neon} is true, non-feedback operators use the NEON polynomial
   * sine kernel and sample-0 {@code gain1} timing ({@code neon_fm_kernel.s:49-206}).
   *
   * @param output output buffer (samples accumulated)
   * @param n number of samples to render
   * @param params 6 operator params (phase, freq, gain_out, level_in)
   * @param algorithm algorithm index 0-31
   * @param fbBuf feedback buffer (2 entries) — firmware uses 2 ints
   * @param feedbackShift feedback amount (0-16)
   */
  public static void render(
      int[] output, int n, FmOpParams[] params, int algorithm, int[] fbBuf, int feedbackShift) {
    render(output, n, params, algorithm, fbBuf, feedbackShift, true);
  }

  /** Port of FmCore::render with explicit {@code neon} selector (fm_core.cpp:65-118). */
  public static void render(
      int[] output,
      int n,
      FmOpParams[] params,
      int algorithm,
      int[] fbBuf,
      int feedbackShift,
      boolean neon) {
    if (n <= 0) {
      return;
    }
    int[] alg = ALGORITHMS[algorithm];
    int invN = (1 << 30) / n;
    FmScratch scratch = scratchHolder.get();
    if (scratch.buf[0].length < n) {
      scratch.buf[0] = new int[n];
      scratch.buf[1] = new int[n];
    }
    int[][] buf = scratch.buf;
    java.util.Arrays.fill(buf[0], 0, n, 0);
    java.util.Arrays.fill(buf[1], 0, n, 0);
    boolean[] hasContents = scratch.hasContents;
    hasContents[0] = true;
    hasContents[1] = false;
    hasContents[2] = false;

    if (debugRender) {
      LOGGER.fine(
          String.format(
              "[FMCORE DEBUG] render algorithm=%d n=%d feedbackShift=%d neon=%b",
              algorithm, n, feedbackShift, neon));
    }

    for (int op = 0; op < 6; op++) {
      int flags = alg[op];
      boolean add = (flags & OUT_BUS_ADD) != 0;
      FmOpParams param = params[op];
      if (debugRender) {
        LOGGER.fine(
            String.format(
                "  op=%d (Op %d) freq=%d level_in=%d gain_out=%d flags=0x%02X",
                op, 6 - op, param.freq, param.level_in, param.gain_out, flags));
      }
      int inbus = (flags >> 4) & 3;
      int outbus = flags & 3;

      int gain1 = param.gain_out;
      // fm_core.cpp:87 — gain2 = Exp2::lookup(param.level_in - (14 * (1 << 24))). Real Exp2, Q24.
      int gain2 = Dx7Tables.exp2Lookup(param.level_in - (14 << 24));
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
            computeFb(outptr, n, param, gain1, gain2, dgain, fbBuf, feedbackShift, add);
          } else if (neon) {
            computePureNeon(outptr, n, param, gain1, dgain, add);
          } else {
            computePure(outptr, n, param, gain1, gain2, dgain, add);
          }
        } else if (neon) {
          computeNormalNeon(outptr, n, inptr, param, gain1, dgain, add);
        } else {
          computeNormal(outptr, n, inptr, param, gain1, gain2, dgain, add);
        }
        hasContents[outbus] = true;
      } else if (!add) {
        hasContents[outbus] = false;
      }
      // param.phase += param.freq * n; (line 117)
      param.phase += param.freq * n;
    }
  }

  // C neon_fm_kernel.s:206 — 7th-order sine polynomial coefficients (d6[0], d6[1], d7[0], d7[1]).
  private static final float NEON_SIN_C0 = -0.01880853017455781f;
  private static final float NEON_SIN_C1 = 0.25215252666796095f;
  private static final float NEON_SIN_C2 = -1.2333439964934032f;
  private static final float NEON_SIN_C3 = 1.0f;
  private static final float Q24_TO_FLOAT = 1.0f / (1 << 24);
  private static final float Q22_TO_FLOAT = 1.0f / (1 << 22);
  private static final float FLOAT_TO_Q24 = (float) (1 << 24);

  // C neon_fm_kernel.s:80-155 — evaluates one sample of the ARM NEON FM polynomial kernel.
  private static int evalNeonSample(int p, float gainFloat) {
    int signMask = (p & 0x800000) != 0 ? -1 : 0; // vtst.32 q13, q4, #0x800000
    float x = ((p & 0x7fffff) - 0x400000) * Q22_TO_FLOAT; // vand, vsub #0x400000, vcvt.f32.s32 #22
    float x2 = x * x; // vmul.f32
    float poly = NEON_SIN_C1 + x2 * NEON_SIN_C0; // vmla.f32
    poly = NEON_SIN_C2 + x2 * poly; // vmla.f32
    poly = NEON_SIN_C3 + x2 * poly; // vmla.f32
    int y1 = (int) ((poly * gainFloat) * FLOAT_TO_Q24); // vmul.f32, vcvt.s32.f32 #24
    return y1 ^ signMask; // veor
  }

  // C fm_op_kernel.cpp:73-76 + neon_fm_kernel.s:49-206 — NEON pure-sine operator kernel.
  // Gain starts at gain1 on sample 0 (lane k initialized to gain1 + k*dgain, stepped by 4*dgain
  // in float Q24 via vcvt.f32.s32 dgain, #22).
  private static void computePureNeon(
      int[] out, int n, FmOpParams param, int gain1, int dgain, boolean add) {
    int phase = param.phase;
    float g0 = gain1 * Q24_TO_FLOAT;
    float g1 = (gain1 + dgain) * Q24_TO_FLOAT;
    float g2 = (gain1 + 2 * dgain) * Q24_TO_FLOAT;
    float g3 = (gain1 + 3 * dgain) * Q24_TO_FLOAT;
    float dgainStep4 = dgain * Q22_TO_FLOAT;
    for (int i = 0; i < n; i++) {
      int lane = i & 3;
      float g;
      if (lane == 0) {
        g = g0;
        g0 += dgainStep4;
      } else if (lane == 1) {
        g = g1;
        g1 += dgainStep4;
      } else if (lane == 2) {
        g = g2;
        g2 += dgainStep4;
      } else {
        g = g3;
        g3 += dgainStep4;
      }
      int y1 = evalNeonSample(phase, g);
      if (add) out[i] += y1;
      else out[i] = y1;
      phase += param.freq;
    }
  }

  // C fm_op_kernel.cpp:42-45 + neon_fm_kernel.s:49-206 — NEON phase-modulated operator kernel.
  private static void computeNormalNeon(
      int[] out, int n, int[] inbuf, FmOpParams param, int gain1, int dgain, boolean add) {
    int phase = param.phase;
    float g0 = gain1 * Q24_TO_FLOAT;
    float g1 = (gain1 + dgain) * Q24_TO_FLOAT;
    float g2 = (gain1 + 2 * dgain) * Q24_TO_FLOAT;
    float g3 = (gain1 + 3 * dgain) * Q24_TO_FLOAT;
    float dgainStep4 = dgain * Q22_TO_FLOAT;
    for (int i = 0; i < n; i++) {
      int lane = i & 3;
      float g;
      if (lane == 0) {
        g = g0;
        g0 += dgainStep4;
      } else if (lane == 1) {
        g = g1;
        g1 += dgainStep4;
      } else if (lane == 2) {
        g = g2;
        g2 += dgainStep4;
      } else {
        g = g3;
        g3 += dgainStep4;
      }
      int y1 = evalNeonSample(phase + inbuf[i], g);
      if (add) out[i] += y1;
      else out[i] = y1;
      phase += param.freq;
    }
  }

  // ── FmOpKernel::compute_pure (fm_op_kernel.cpp:69-99) ──
  // y = Sin::lookup(phase); y1 = (y*gain)>>24; output += y1 (plain, int wraparound); phase += freq.
  private static void computePure(
      int[] out, int n, FmOpParams param, int gain1, int gain2, int dgain, boolean add) {
    int phase = param.phase;
    int gain = gain1;
    for (int i = 0; i < n; i++) {
      gain += dgain;
      int y = Dx7Tables.sinLookup(phase);
      int y1 = (int) (((long) y * (long) gain) >> 24);
      if (add) out[i] += y1;
      else out[i] = y1;
      phase += param.freq;
    }
  }

  // ── FmOpKernel::compute (fm_op_kernel.cpp:38-67) ──
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
      gain += dgain;
      int y = Dx7Tables.sinLookup(phase + inbuf[i]);
      int y1 = (int) (((long) y * (long) gain) >> 24);
      if (add) out[i] += y1;
      else out[i] = y1;
      phase += param.freq;
    }
  }

  // ── FmOpKernel::compute_fb (fm_op_kernel.cpp:104-133) ──
  // scaled_fb = (y0+y) >> (fb_shift+1); y = Sin::lookup(phase+scaled_fb); y = (y*gain)>>24;
  // output += y; fb_buf = {y0(prev), y(gained)}.
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
    int y0 = fbBuf[0];
    int y = fbBuf[1];
    for (int i = 0; i < n; i++) {
      gain += dgain;
      int scaledFb = (y0 + y) >> (feedbackShift + 1);
      y0 = y;
      y = Dx7Tables.sinLookup(phase + scaledFb);
      y = (int) (((long) y * (long) gain) >> 24);
      if (add) out[i] += y;
      else out[i] = y;
      phase += param.freq;
    }
    fbBuf[0] = y0;
    fbBuf[1] = y;
  }
}
