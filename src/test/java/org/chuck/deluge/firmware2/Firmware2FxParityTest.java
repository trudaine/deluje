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

  private static StereoSample[] newBuf() {
    StereoSample[] a = new StereoSample[N];
    for (int i = 0; i < N; i++) a[i] = new StereoSample();
    return a;
  }
}
