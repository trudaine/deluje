package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.EqProcessor;
import org.chuck.deluge.firmware.dsp.fx.ModFXProcessor;
import org.chuck.deluge.firmware.dsp.fx.SrrBitcrushProcessor;
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
  private static final int BLOCKS = 8;

  /** Fill a firmware/ StereoSample[] and a fw2 int[][] with the same deterministic noise. */
  private static void fill(Random r, StereoSample[] a, int[][] b) {
    for (int i = 0; i < N; i++) {
      int l = r.nextInt();
      int rr = r.nextInt();
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

  @Test
  void eqMatchesFirmware() {
    EqProcessor oldEq = new EqProcessor();
    Eq newEq = new Eq();
    Random r = new Random(42);
    int bass = 900000000; // non-zero → doBass
    int treble = -600000000; // non-zero → doTreble
    StereoSample[] a = newBuf();
    int[][] b = new int[N][2];
    for (int blk = 0; blk < BLOCKS; blk++) {
      fill(r, a, b);
      oldEq.process(a, N, bass, treble);
      // freqParam 0 → getExp(...,0), matching the firmware/ EqProcessor's fixed BASS/TREBLE_FREQ.
      newEq.process(b, N, bass, treble, 0, 0);
      assertSame("EQ", blk, a, b);
    }
  }

  @Test
  void srrBitcrushMatchesFirmware() {
    SrrBitcrushProcessor oldSrr = new SrrBitcrushProcessor();
    SrrBitcrush newSrr = new SrrBitcrush();
    Random r = new Random(7);
    int bitcrush = 800000000;
    int srr = 1200000000;
    StereoSample[] a = newBuf();
    int[][] b = new int[N][2];
    for (int blk = 0; blk < BLOCKS; blk++) {
      fill(r, a, b);
      int[] volA = {Integer.MAX_VALUE};
      int[] volB = {Integer.MAX_VALUE};
      oldSrr.process(a, N, bitcrush, srr, volA);
      newSrr.process(b, N, bitcrush, srr, volB);
      assertEquals(volA[0], volB[0], "SRR postFXVolume mismatch block " + blk);
      assertSame("SRR", blk, a, b);
    }
  }

  @Test
  void modFxMatchesFirmware() {
    // Only the SINE-LFO types (CHORUS, PHASER) can be parity-checked against firmware/. FLANGER and
    // DIMENSION use the TRIANGLE LFO, where firmware/'s LFO uses a non-faithful inline approximation
    // ((abs(phase)-2^30)<<1) instead of the C getTriangle (waves.h:53, slope*phase+offset) that fw2
    // ports — so fw2 is the faithful one there and intentionally differs from firmware/. WARBLE +
    // the stereo path are likewise more faithful in fw2 (see ModFx).
    org.chuck.deluge.firmware.dsp.fx.ModFXType[] oldTypes = {
      org.chuck.deluge.firmware.dsp.fx.ModFXType.CHORUS,
      org.chuck.deluge.firmware.dsp.fx.ModFXType.PHASER,
    };
    ModFx.ModFXType[] newTypes = {ModFx.ModFXType.CHORUS, ModFx.ModFXType.PHASER};

    for (int t = 0; t < oldTypes.length; t++) {
      ModFXProcessor oldFx = new ModFXProcessor();
      ModFx newFx = new ModFx();
      Random r = new Random(100 + t);
      int rate = 30000000, depth = 700000000, offset = 200000000, feedback = 500000000;
      StereoSample[] a = newBuf();
      int[][] b = new int[N][2];
      for (int blk = 0; blk < BLOCKS; blk++) {
        fill(r, a, b);
        int[] volA = {Integer.MAX_VALUE};
        int[] volB = {Integer.MAX_VALUE};
        oldFx.processModFX(a, oldTypes[t], rate, depth, volA, offset, feedback);
        newFx.processModFX(b, N, newTypes[t], rate, depth, volB, offset, feedback, false, true);
        assertEquals(volA[0], volB[0], newTypes[t] + " postFXVolume mismatch block " + blk);
        assertSame("modFX " + newTypes[t], blk, a, b);
      }
    }
  }

  /**
   * The compressor CANNOT be parity-checked against firmware/, because firmware/ is the non-faithful
   * side here (like the modFX triangle LFO above). Its {@code getTanHAntialiased} — used per output
   * sample by RMSFeedbackCompressor::render — diverges from the C in TWO ways:
   *
   * <ol>
   *   <li><b>interpolateTableSigned2d scaling.</b> The C (util/functions.h:235) documents "Output of
   *       this function (unlike the regular 1d one) is only +- 1073741824": it blends with 31-bit
   *       strengths and {@code multiply_32x32_rshift32} (half-scale). firmware/ blends with 16-bit
   *       strengths and {@code >> 16}, giving exactly 2× the C value (full-scale, ±2^31). fw2 ports
   *       the C verbatim. This test PROVES it: fw2 stays within the documented ±1073741824 bound,
   *       firmware/ runs to ~2^31.
   *   <li><b>working-value init.</b> C (rms_feedback.cpp:68-69) seeds
   *       {@code lshiftAndSaturateUnknown(buffer[0], 3) + 2147483648u}; firmware/ uses
   *       {@code lshiftAndSaturate(...) + 2147483647} (off by one), which for a saturating sample
   *       wraps 0 → -1, selecting tanH2d row 63 instead of row 0 — a sign flip on sample 0.
   * </ol>
   *
   * fw2's Compressor matches the C on both. So instead of comparing to the buggy firmware/, we assert
   * the C contract that fw2 must honor and firmware/ breaks.
   */
  @Test
  void compressorInterp2dHonorsCContract() {
    Random r = new Random(99);
    long maxFw2 = 0;
    long maxFw = 0;
    for (int n = 0; n < 200_000; n++) {
      int x = r.nextInt();
      int y = r.nextInt();
      int c = Functions.interpolateTableSigned2d(x, y, 32, 32, LookupTables.tanH2d, 7, 6);
      int f =
          org.chuck.deluge.firmware.util.FirmwareUtils.interpolateTableSigned2d(
              x, y, 32, 32, org.chuck.deluge.firmware.util.TanHLookupTable.tanH2d, 7, 6);
      maxFw2 = Math.max(maxFw2, Math.abs((long) c));
      maxFw = Math.max(maxFw, Math.abs((long) f));
    }
    // C: util/functions.h:235 — interpolateTableSigned2d output is only ±1073741824 (half-scale).
    org.junit.jupiter.api.Assertions.assertTrue(
        maxFw2 <= 1073741824L, "fw2 interp2d must honor the C ±2^30 contract; was " + maxFw2);
    // firmware/ violates it (runs to ~2^31) — confirming it is the non-faithful side.
    org.junit.jupiter.api.Assertions.assertTrue(
        maxFw > 1073741824L, "firmware/ interp2d is expected to exceed the C bound; was " + maxFw);
  }

  /**
   * Digital (non-analog) delay: feedback + HPF + delay-buffer resample + write/read, NO tanH. This
   * isolates the core delay DSP from the tanH-antialias path (where firmware/ is non-faithful — see
   * compressor note). syncLevel 0 skips the sync-rate adjustment, so userDelayRate is used directly.
   */
  @Test
  void digitalDelayMatchesFirmware() {
    runDelayParity(false, org.chuck.deluge.firmware.model.SyncLevel.SYNC_LEVEL_NONE, 0);
  }

  /**
   * Analog delay: as digital, plus the 26-tap impulse-response FIR + headroom reduction + 1D
   * {@code getTanHUnknown} saturation (NOT the 2D anti-alias path — that one firmware/ gets wrong).
   */
  @Test
  void analogDelayMatchesFirmware() {
    runDelayParity(true, org.chuck.deluge.firmware.model.SyncLevel.SYNC_LEVEL_NONE, 0);
  }

  /**
   * Synced delay (syncLevel 16TH = 5): exercises setupWorkingState's sync-rate adjustment path
   * (userDelayRate *= timePerInternalTickInverse, clamp, shift). This is the default the engine's
   * FirmwareDelay wrapper uses, so it must be parity-clean before migrating that wrapper to firmware2.
   */
  @Test
  void syncedDelayMatchesFirmware() {
    runDelayParity(false, org.chuck.deluge.firmware.model.SyncLevel.SYNC_LEVEL_16TH, 5);
  }

  private void runDelayParity(boolean analog, org.chuck.deluge.firmware.model.SyncLevel syncOld, int syncNew) {
    org.chuck.deluge.firmware.dsp.delay.Delay oldD = new org.chuck.deluge.firmware.dsp.delay.Delay();
    Delay newD = new Delay();
    oldD.analog = analog;
    oldD.pingPong = false;
    oldD.syncLevel = syncOld;
    newD.analog = analog;
    newD.pingPong = false;
    newD.syncLevel = syncNew;

    int userDelayRate = 1 << 23; // moderate rate → a few-hundred-sample buffer
    int feedback = 0x40000000; // well above the 256 threshold
    String tag = analog ? "analogDelay" : "digitalDelay";

    Random r = new Random(555);
    StereoSample[] a = newBuf();
    int[][] b = new int[N][2];
    for (int blk = 0; blk < BLOCKS; blk++) {
      fill(r, a, b);

      org.chuck.deluge.firmware.dsp.delay.Delay.State sOld =
          new org.chuck.deluge.firmware.dsp.delay.Delay.State();
      sOld.userDelayRate = userDelayRate;
      sOld.delayFeedbackAmount = feedback;
      Delay.State sNew = new Delay.State();
      sNew.userDelayRate = userDelayRate;
      sNew.delayFeedbackAmount = feedback;

      oldD.setupWorkingState(sOld, 1 << 20, true);
      newD.setupWorkingState(sNew, 1 << 20, true);
      assertEquals(sOld.doDelay, sNew.doDelay, "doDelay mismatch block " + blk);
      assertEquals(sOld.userDelayRate, sNew.userDelayRate, "userDelayRate mismatch block " + blk);

      oldD.process(a, sOld);
      newD.process(b, N, sNew);
      assertSame(tag, blk, a, b);
    }
  }

  /**
   * Sidechain envelope (integer). Exercises registerHit (incl. combineHitStrengths — fw2 previously
   * approximated it as max(a,b); C and firmware/ do (maxOne>>1)+(sum>>1)) then the attack/release
   * curve. syncLevel 0 (NONE) so getActualAttackRate/Release return attack/release directly, matching
   * firmware/'s raw-rate render.
   *
   * <p>shapeValue is kept STRICTLY NEGATIVE: the C (sidechain.cpp:181) computes
   * {@code uint32_t positiveShapeValue = (uint32_t)shapeValue + 2147483648} then an unsigned
   * {@code >> 15}. firmware/ does this with a signed int and so is only faithful for shapeValue < 0
   * (for shapeValue >= 0 the +2^31 overflows int negative — a firmware/ bug). fw2 previously had the
   * opposite bug (long without the uint32 wrap, wrong for shapeValue < 0); now fixed to
   * {@code (shapeValue + 0x80000000) >>> 15}, faithful for ALL shapeValues. With a negative shapeValue
   * all three (fw2, firmware/, C) agree, so this is a genuine 3-way parity check of the render path.
   */
  @Test
  void sidechainMatchesFirmware() {
    org.chuck.deluge.firmware.modulation.sidechain.SideChain oldS =
        new org.chuck.deluge.firmware.modulation.sidechain.SideChain();
    Sidechain newS = new Sidechain();
    int attack = 327244;
    int release = 936;
    oldS.attack = attack;
    oldS.release = release;
    oldS.syncLevel = org.chuck.deluge.firmware.model.SyncLevel.SYNC_LEVEL_NONE;
    newS.attack = attack;
    newS.release = release;
    newS.syncLevel = 0;

    int shapeValue = -300000000; // strictly negative — see Javadoc (firmware/ faithful only here)
    Random r = new Random(13);
    for (int blk = 0; blk < 40; blk++) {
      // Occasionally register one or two hits in the same block → combineHitStrengths runs.
      if (blk % 5 == 0) {
        int h1 = r.nextInt(Integer.MAX_VALUE);
        oldS.registerHit(h1);
        newS.registerHit(h1);
        if (blk % 10 == 0) {
          int h2 = r.nextInt(Integer.MAX_VALUE);
          oldS.registerHit(h2);
          newS.registerHit(h2);
        }
      }
      int outOld = oldS.render(N, shapeValue);
      int outNew = newS.render(N, shapeValue);
      assertEquals(outOld, outNew, "Sidechain render mismatch block " + blk);
      assertEquals(oldS.lastValue, newS.lastValue, "Sidechain lastValue mismatch block " + blk);
    }
  }

  /** combineHitStrengths is exact vs the C formula across the strength range (fw2 had max(a,b)). */
  @Test
  void combineHitStrengthsMatchesC() {
    Random r = new Random(8);
    for (int n = 0; n < 100_000; n++) {
      int s1 = r.nextInt(Integer.MAX_VALUE);
      int s2 = r.nextInt(Integer.MAX_VALUE);
      long sum = Math.min((long) s1 + s2, 2147483647L); // C: uint32 sum capped at ONE_Q31
      int expected = (Math.max(s1, s2) >> 1) + (int) (sum >>> 1);
      assertEquals(expected, Sidechain.combineHitStrengths(s1, s2), "combineHitStrengths(" + s1 + "," + s2 + ")");
    }
  }

  /**
   * AbsValueFollower (float). Uses double-precision exp/log on both sides, so compare with a small
   * relative epsilon rather than bit-exact.
   */
  @Test
  void absValueFollowerMatchesFirmware() {
    org.chuck.deluge.firmware.dsp.envelope_follower.AbsValueFollower oldF =
        new org.chuck.deluge.firmware.dsp.envelope_follower.AbsValueFollower();
    AbsValueFollower newF = new AbsValueFollower();
    oldF.setup(5 << 24, 5 << 24);
    newF.setup(5 << 24, 5 << 24);

    Random r = new Random(321);
    StereoSample[] a = newBuf();
    int[][] b = new int[N][2];
    for (int blk = 0; blk < BLOCKS; blk++) {
      fill(r, a, b);
      org.chuck.deluge.firmware.dsp.StereoFloatSample outOld = oldF.calcApproxRMS(a);
      float[] outNew = newF.calcApproxRMS(b);
      org.junit.jupiter.api.Assertions.assertEquals(outOld.l, outNew[0], 1e-3f, "AbsRMS L block " + blk);
      org.junit.jupiter.api.Assertions.assertEquals(outOld.r, outNew[1], 1e-3f, "AbsRMS R block " + blk);
    }
  }

  /**
   * GranularProcessor.toPositive must be the C {@code (a / 2) + 2^30} (fixedpoint.h:37) — signed
   * truncating division, not {@code >> 1}. fw2 previously did {@code (val & 0xFFFFFFFFL) >> 1}, which
   * is wrong for every negative input. Check the C formula across the full int range incl. negatives.
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
   * The DIGITAL reverb (digital.hpp Lexicon-224 topology) used to silently alias the MUTABLE model in
   * fw2. Now that it's its own port, prove it (a) produces a non-silent tail and (b) is distinct from
   * MUTABLE for the same input/params. Self-contained — no firmware/ oracle (firmware/ reverb differs
   * from the C, see Freeverb note).
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
    org.junit.jupiter.api.Assertions.assertTrue(energyDigital > 0, "DIGITAL reverb produced silence");
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
        int progressSmall = oscPos >>> 20;       // C:25
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

  private static StereoSample[] newBuf() {
    StereoSample[] a = new StereoSample[N];
    for (int i = 0; i < N; i++) a[i] = new StereoSample();
    return a;
  }
}
