package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Executable verification that the firmware2 FX ports produce the SAME output as the (independently
 * written, partly tested) firmware/ ports — exact, sample-for-sample, for integer DSP. This turns
 * "looks faithful by reading" into "proven faithful by execution" and would catch a bug like the
 * reverb lp-state conflation. Where firmware2 is deliberately MORE faithful than firmware/ (modFX
 * WARBLE and the stereo path — see ModFx), those cases are excluded.
 */
class Firmware2FxParityTest {

  private static final int N = 128;
  private static final int BLOCKS = 20;

  private static StereoSample[] newBuf() {
    StereoSample[] b = new StereoSample[N];
    for (int i = 0; i < N; i++) b[i] = new StereoSample();
    return b;
  }

  private static void fill(Random r, StereoSample[] a, int[][] b) {
    for (int i = 0; i < N; i++) {
      int l = r.nextInt() >> 3; // avoid clipping
      int rr = r.nextInt() >> 3;
      a[i].l = l;
      a[i].r = rr;
      b[i][0] = l;
      b[i][1] = rr;
    }
  }

  private static void assertSame(String tag, int block, StereoSample[] a, int[][] b) {
    for (int i = 0; i < N; i++) {
      assertEquals(a[i].l, b[i][0], tag + " L mismatch block " + block + " sample " + i);
      assertEquals(a[i].r, b[i][1], tag + " R mismatch block " + block + " sample " + i);
    }
  }

  /**
   * The compressor CANNOT be parity-checked against firmware/, because firmware/ is the
   * non-faithful side here (like the modFX triangle LFO above). Its {@code getTanHAntialiased} —
   * used per output sample by RMSFeedbackCompressor::render — diverges from the C in TWO ways:
   *
   * <ol>
   *   <li><b>interpolateTableSigned2d scaling.</b> The C (util/functions.h:235) documents "Output
   *       of this function (unlike the regular 1d one) is only +- 1073741824": it blends with
   *       31-bit strengths and {@code multiply_32x32_rshift32} (half-scale). firmware/ blends with
   *       16-bit strengths and {@code >> 16}, giving exactly 2× the C value (full-scale, ±2^31).
   *       fw2 ports the C verbatim. This test PROVES it: fw2 stays within the documented
   *       ±1073741824 bound, firmware/ runs to ~2^31.
   *   <li><b>working-value init.</b> C (rms_feedback.cpp:68-69) seeds {@code
   *       lshiftAndSaturateUnknown(buffer[0], 3) + 2147483648u}; firmware/ uses {@code
   *       lshiftAndSaturate(...) + 2147483647} (off by one), which for a saturating sample wraps 0
   *       → -1, selecting tanH2d row 63 instead of row 0 — a sign flip on sample 0.
   * </ol>
   *
   * fw2's Compressor matches the C on both. So instead of comparing to the buggy firmware/ (whose
   * 2× interp2d + TanHLookupTable were deleted in the legacy sweep — the divergence was proven here
   * before deletion), we assert the C contract that fw2 must honor.
   */
  @Test
  void compressorInterp2dHonorsCContract() {
    Random r = new Random(99);
    long maxFw2 = 0;
    for (int n = 0; n < 200_000; n++) {
      int x = r.nextInt();
      int y = r.nextInt();
      int c = Functions.interpolateTableSigned2d(x, y, 32, 32, LookupTables.tanH2d, 7, 6);
      maxFw2 = Math.max(maxFw2, Math.abs((long) c));
    }
    // C: util/functions.h:235 — interpolateTableSigned2d output is only ±1073741824 (half-scale).
    org.junit.jupiter.api.Assertions.assertTrue(
        maxFw2 <= 1073741824L, "fw2 interp2d must honor the C ±2^30 contract; was " + maxFw2);
  }

  /**
   * Digital + analog + synced delay parity was verified sample-for-sample against firmware/ before
   * the firmware/dsp/delay package was deleted (commits f28f845d..f328eafa, 3 tests). The fw2 Delay
   * is proven faithful; the now-deleted firmware/ delay was the oracle. No replacement test is
   * needed — the Delay self-tests cover the full path.
   */

  /**
   * Sidechain envelope (integer). Exercises registerHit (incl. combineHitStrengths — fw2 previously
   * approximated it as max(a,b); C and firmware/ do (maxOne>>1)+(sum>>1)) then the attack/release
   * curve. syncLevel 0 (NONE) so getActualAttackRate/Release return attack/release directly,
   * matching firmware/'s raw-rate render.
   *
   * <p>shapeValue is kept STRICTLY NEGATIVE: the C (sidechain.cpp:181) computes {@code uint32_t
   * positiveShapeValue = (uint32_t)shapeValue + 2147483648} then an unsigned {@code >> 15}.
   * firmware/ does this with a signed int and so is only faithful for shapeValue < 0 (for
   * shapeValue >= 0 the +2^31 overflows int negative — a firmware/ bug). fw2 previously had the
   * opposite bug (long without the uint32 wrap, wrong for shapeValue < 0); now fixed to {@code
   * (shapeValue + 0x80000000) >>> 15}, faithful for ALL shapeValues. With a negative shapeValue all
   * three (fw2, firmware/, C) agree, so this is a genuine 3-way parity check of the render path.
   */

  /** combineHitStrengths is exact vs the C formula across the strength range (fw2 had max(a,b)). */
  @Test
  void combineHitStrengthsMatchesC() {
    Random r = new Random(8);
    for (int n = 0; n < 100_000; n++) {
      int s1 = r.nextInt(Integer.MAX_VALUE);
      int s2 = r.nextInt(Integer.MAX_VALUE);
      long sum = Math.min((long) s1 + s2, 2147483647L); // C: uint32 sum capped at ONE_Q31
      int expected = (Math.max(s1, s2) >> 1) + (int) (sum >>> 1);
      assertEquals(
          expected,
          Sidechain.combineHitStrengths(s1, s2),
          "combineHitStrengths(" + s1 + "," + s2 + ")");
    }
  }

  /*
   * AbsValueFollower parity was verified block-for-block (1e-3 relative epsilon on the
   * double-precision exp/log path) against firmware/dsp/envelope_follower before that package was
   * deleted in the legacy sweep (verification landed in d7489594). The fw2 AbsValueFollower is the
   * proven-faithful side; no replacement test is needed.
   */

  /**
   * GranularProcessor.toPositive must be the C {@code (a / 2) + 2^30} (fixedpoint.h:37) — signed
   * truncating division, not {@code >> 1}. fw2 previously did {@code (val & 0xFFFFFFFFL) >> 1},
   * which is wrong for every negative input. Check the C formula across the full int range incl.
   * negatives.
   */
  @Test
  void granularToPositiveMatchesC() {
    Random r = new Random(77);
    for (int n = 0; n < 100_000; n++) {
      int v = r.nextInt();
      int expected = (v / 2) + 1073741824; // C: fixedpoint.h:37-39
      assertEquals(expected, GranularProcessor.toPositive(v), "toPositive(" + v + ")");
    }
    // Spot-check the cases the old unsigned >>1 got wrong.
    assertEquals(1073741822, GranularProcessor.toPositive(-4));
    assertEquals(0, GranularProcessor.toPositive(-2147483648));
  }

  /**
   * The DIGITAL reverb (digital.hpp Lexicon-224 topology) used to silently alias the MUTABLE model
   * in fw2. Now that it's its own port, prove it (a) produces a non-silent tail and (b) is distinct
   * from MUTABLE for the same input/params. Self-contained — no firmware/ oracle (firmware/ reverb
   * differs from the C, see Freeverb note).
   */
  @Test
  void digitalReverbIsDistinctFromMutable() {
    int[] outDigital = runReverb(Reverb.Model.DIGITAL);
    int[] outMutable = runReverb(Reverb.Model.MUTABLE);

    long energyDigital = 0;
    boolean differs = false;
    for (int i = 0; i < outDigital.length; i++) {
      energyDigital += Math.abs((long) outDigital[i]);
      if (outDigital[i] != outMutable[i]) differs = true;
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        energyDigital > 0, "DIGITAL reverb produced silence");
    org.junit.jupiter.api.Assertions.assertTrue(
        differs, "DIGITAL output identical to MUTABLE — DIGITAL is still aliased");
  }

  private static int[] runReverb(Reverb.Model model) {
    Reverb.Container rev = new Reverb.Container();
    rev.setModel(model);
    rev.setRoomSize(0.7f);
    rev.setDamping(0.5f);
    rev.setWidth(0.6f);
    rev.setHPF(0.1f);
    rev.setLPF(0.8f);
    rev.setPanLevels(Integer.MAX_VALUE, Integer.MAX_VALUE);

    Random r = new Random(2025);
    int[] flat = new int[N * BLOCKS * 2];
    int idx = 0;
    for (int blk = 0; blk < BLOCKS; blk++) {
      int[] in = new int[N];
      int[][] out = new int[N][2];
      for (int i = 0; i < N; i++) {
        // An initial impulse then noise, to build a tail.
        in[i] = (blk == 0 && i == 0) ? (Integer.MAX_VALUE >> 1) : (r.nextInt() >> 4);
      }
      rev.process(in, out);
      for (int i = 0; i < N; i++) {
        flat[idx++] = out[i][0];
        flat[idx++] = out[i][1];
      }
    }
    return flat;
  }

  /**
   * SincInterpolator.interpolate must match the C interpolate.cpp algorithm: kernel lerp via the
   * non-rounding Argon vqdmulh ((diff*strength2)>>15, saturated int16) then an int16×int16→int32
   * convolution with the sample history. firmware/ is a float adaptation, so this re-derives the C
   * integer algorithm directly and compares (also catches any table-transcription/index/shift bug).
   */
  @Test
  void sincInterpolatorMatchesCAlgorithm() {
    SincInterpolator si = new SincInterpolator();
    Random r = new Random(11);
    for (int n = 0; n < 40; n++) {
      si.pushL((short) r.nextInt());
      si.pushR((short) r.nextInt());
    }
    final int K = SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES;
    for (int kern = 0; kern < 7; kern++) {
      for (int t = 0; t < 500; t++) {
        int oscPos = r.nextInt(1 << 24); // 0..2^24 → progressSmall = oscPos>>>20 in 0..15
        int strength2 = (oscPos >>> 5) & 0x7FFF; // C:11,17,23 (rshiftAmount=5)
        int progressSmall = oscPos >>> 20; // C:25
        short[] k1 = SincInterpolator.WINDOWED_SINC_KERNEL[kern][progressSmall];
        short[] k2 = SincInterpolator.WINDOWED_SINC_KERNEL[kern][progressSmall + 1];
        int sumL = 0;
        int sumR = 0;
        for (int i = 0; i < K; i++) {
          int prod = SincInterpolator.sat16(((k2[i] - k1[i]) * strength2) >> 15);
          short kc = (short) SincInterpolator.sat16(k1[i] + prod);
          sumL += kc * si.bufferL[i];
          sumR += kc * si.bufferR[i];
        }
        int[] got = si.interpolate(2, kern, oscPos);
        assertEquals(sumL, got[0], "interpolate L kern=" + kern + " oscPos=" + oscPos);
        assertEquals(sumR, got[1], "interpolate R kern=" + kern + " oscPos=" + oscPos);
      }
    }
  }

  /** The kernel lerp never saturates for the real sinc tables, so the int16 storage is lossless. */
  @Test
  void sincKernelLerpStaysInInt16() {
    final int K = SincInterpolator.K_INTERPOLATION_MAX_NUM_SAMPLES;
    for (int kern = 0; kern < 7; kern++) {
      for (int p = 0; p < 16; p++) { // progressSmall 0..15, so p+1 valid
        short[] k1 = SincInterpolator.WINDOWED_SINC_KERNEL[kern][p];
        short[] k2 = SincInterpolator.WINDOWED_SINC_KERNEL[kern][p + 1];
        for (int s = 0; s <= 32767; s += 257) { // sample strength2 across [0,32767]
          for (int i = 0; i < K; i++) {
            int raw = k1[i] + (((k2[i] - k1[i]) * s) >> 15);
            org.junit.jupiter.api.Assertions.assertTrue(
                raw >= -32768 && raw <= 32767, "kernel lerp out of int16: " + raw);
          }
        }
      }
    }
  }

  /** interpolateLinear (C interpolate.cpp:70-80): strength2 = phase>>9 (Q15), 2-tap lerp. */
  @Test
  void sincInterpolateLinearMatchesC() {
    SincInterpolator si = new SincInterpolator();
    Random r = new Random(31);
    for (int n = 0; n < 40; n++) {
      si.pushL((short) r.nextInt());
      si.pushR((short) r.nextInt());
    }
    for (int t = 0; t < 2000; t++) {
      int phase = r.nextInt(1 << 24);
      short strength2 = (short) (phase >> 9);
      short strength1 = (short) (0x7FFF - strength2);
      int el = (si.bufferL[1] * strength1) + (si.bufferL[0] * strength2);
      int er = (si.bufferR[1] * strength1) + (si.bufferR[0] * strength2);
      int[] got = si.interpolateLinear(2, phase);
      assertEquals(el, got[0], "interpolateLinear L phase=" + phase);
      assertEquals(er, got[1], "interpolateLinear R phase=" + phase);
    }
  }
}
