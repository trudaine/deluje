package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the fw2 master-FX units (Delay, Compressor) after the firmware/ parity
 * oracles and their tests were deleted (commit 6fa73408). firmware/ was a non-faithful oracle (see
 * the {@code firmware-nonfaithful-reference-spots} memory), so — like the rest of the fw2 port —
 * these are re-derivation / property + golden-signature tests against fw2's own behavior, locking it
 * in so future edits can't silently change the DSP.
 *
 * <p>Writing these surfaced a real P1 (now fixed): the fw2 master Delay produced no echo because the
 * inner DelayBuffer used Q31 max (2147483647) where the C uses kMaxSampleValue = 1&lt;&lt;24 in the
 * buffer-size / spin-rate math — 128× off, so the secondary→primary swap never fired. The delay-echo
 * test below guards against a regression.
 */
class MasterFxRegressionTest {

  private static final int ONE = 2147483647;

  /** A reproducible pseudo-random Q31 stereo block. */
  private static int[][] noiseBlock(int n, long seed) {
    java.util.Random r = new java.util.Random(seed);
    int[][] b = new int[n][2];
    for (int i = 0; i < n; i++) {
      b[i][0] = (int) (r.nextDouble() * 2.0 * 0.3 * ONE - 0.3 * ONE);
      b[i][1] = (int) (r.nextDouble() * 2.0 * 0.3 * ONE - 0.3 * ONE);
    }
    return b;
  }

  /** FNV-1a over the [l,r] frames — a stable signature of an exact output buffer. */
  private static long signature(int[][] buf, int n) {
    long h = 1469598103934665603L;
    for (int i = 0; i < n; i++) {
      for (int c = 0; c < 2; c++) {
        h = (h ^ (buf[i][c] & 0xFFFFFFFFL)) * 1099511628211L;
      }
    }
    return h;
  }

  // ── Compressor ────────────────────────────────────────────────────────────

  @Test
  void compressorSilenceStaysSilent() {
    Compressor c = new Compressor();
    int[][] buf = new int[128][2]; // all zero
    c.renderVolNeutral(buf, ONE);
    for (int i = 0; i < 128; i++) {
      assertEquals(0, buf[i][0], "silent L @" + i);
      assertEquals(0, buf[i][1], "silent R @" + i);
    }
  }

  @Test
  void compressorIsDeterministic() {
    int[][] a = noiseBlock(128, 42);
    int[][] b = noiseBlock(128, 42);
    new Compressor().renderVolNeutral(a, ONE);
    new Compressor().renderVolNeutral(b, ONE);
    assertEquals(signature(a, 128), signature(b, 128), "compressor must be deterministic");
  }

  @Test
  void compressorGoldenSignature() {
    int[][] buf = noiseBlock(128, 7);
    Compressor c = new Compressor();
    c.renderVolNeutral(buf, ONE);
    assertEquals(
        COMPRESSOR_GOLDEN, signature(buf, 128), "compressor output drifted — re-baseline only if intended");
  }

  // ── Delay ─────────────────────────────────────────────────────────────────

  // A "native" delay rate (>= ~3.1M) maps to an un-clamped buffer whose native_rate_ equals the
  // user rate, so the secondary buffer fills at the input rate and the swap fires quickly (~256
  // blocks). 1<<23 → a 32768-sample (~0.74s) delay.
  private static final int NATIVE_RATE = 1 << 23;

  private static Delay freshDelay() {
    Delay d = new Delay();
    d.syncLevel = 0; // free-running, externally-driven rate (as the master bus uses it)
    return d;
  }

  private static Delay.State delayState() {
    Delay.State s = new Delay.State();
    s.doDelay = true;
    s.userDelayRate = NATIVE_RATE;
    s.delayFeedbackAmount = 1073741824; // 0.5 in Q31
    return s;
  }

  @Test
  void delaySilenceStaysSilent() {
    Delay d = freshDelay();
    Delay.State st = delayState();
    for (int blk = 0; blk < 8; blk++) {
      int[][] buf = new int[128][2]; // silence
      d.setupWorkingState(st, 1 << 20, true);
      d.process(buf, 128, st);
      for (int i = 0; i < 128; i++) {
        assertEquals(0, buf[i][0], "silent L blk" + blk + " @" + i);
        assertEquals(0, buf[i][1], "silent R blk" + blk + " @" + i);
      }
    }
  }

  @Test
  void delayProducesDelayedEcho() {
    // Feed a tone for the first few blocks, then silence; once the buffer swaps (~256 blocks) the
    // delayed signal must read back. Regression guard for the kMaxSampleValue (1<<24) buffer/spin
    // math — with the old Q31-max value the swap never fired and this stayed silent forever.
    Delay d = freshDelay();
    Delay.State st = delayState();
    long echoEnergy = 0;
    for (int blk = 0; blk < 700; blk++) {
      int[][] buf = new int[128][2];
      if (blk < 8) {
        for (int i = 0; i < 128; i++) buf[i][0] = buf[i][1] = (int) (0.4 * ONE);
      }
      d.setupWorkingState(st, 1 << 20, true);
      d.process(buf, 128, st);
      if (blk >= 300) { // well after the dry tone ends and the buffer has swapped
        for (int i = 0; i < 128; i++) echoEnergy += Math.abs((long) buf[i][0]);
      }
    }
    assertTrue(echoEnergy > 0, "an impulse must produce a delayed echo (energy=" + echoEnergy + ")");
  }

  @Test
  void delayIsDeterministic() {
    Delay d1 = freshDelay();
    Delay d2 = freshDelay();
    Delay.State s1 = delayState();
    Delay.State s2 = delayState();
    long h1 = 1469598103934665603L;
    long h2 = 1469598103934665603L;
    for (int blk = 0; blk < 400; blk++) {
      int[][] a = noiseBlock(128, 100 + blk);
      int[][] b = noiseBlock(128, 100 + blk);
      d1.setupWorkingState(s1, 1 << 20, true);
      d1.process(a, 128, s1);
      d2.setupWorkingState(s2, 1 << 20, true);
      d2.process(b, 128, s2);
      h1 ^= signature(a, 128);
      h2 ^= signature(b, 128);
    }
    assertEquals(h1, h2, "delay must be deterministic");
  }

  @Test
  void analogDelayDiffersFromDigital() {
    Delay dig = freshDelay();
    dig.analog = false;
    Delay ana = freshDelay();
    ana.analog = true;
    Delay.State sd = delayState();
    Delay.State sa = delayState();
    long hd = 0;
    long ha = 0;
    for (int blk = 0; blk < 400; blk++) { // long enough for echoes to feed back through saturation
      int[][] a = noiseBlock(128, 200 + blk);
      int[][] b = noiseBlock(128, 200 + blk);
      dig.setupWorkingState(sd, 1 << 20, true);
      dig.process(a, 128, sd);
      ana.setupWorkingState(sa, 1 << 20, true);
      ana.process(b, 128, sa);
      hd ^= signature(a, 128);
      ha ^= signature(b, 128);
    }
    assertNotEquals(hd, ha, "analog (saturated/filtered) delay must differ from digital");
  }

  @Test
  void delayGoldenSignature() {
    Delay d = freshDelay();
    Delay.State st = delayState();
    long h = 1469598103934665603L;
    for (int blk = 0; blk < 400; blk++) {
      int[][] buf = noiseBlock(128, 300 + blk);
      d.setupWorkingState(st, 1 << 20, true);
      d.process(buf, 128, st);
      h ^= signature(buf, 128);
    }
    assertEquals(DELAY_GOLDEN, h, "delay output drifted — re-baseline only if intended");
  }

  // ── Reverb ────────────────────────────────────────────────────────────────

  // The master engine sets the reverb output level as the reverb pan levels before process()
  // (C audio_engine.cpp:836). Without it every model multiplies its wet by a 0 amplitude → silence.
  private static final int REVERB_OUTPUT_VOLUME = 0x20000000; // C:823 with no sidechain

  private static Reverb.Container freshReverb(Reverb.Model m) {
    Reverb.Container c = new Reverb.Container();
    c.setModel(m);
    c.setRoomSize(0.8f);
    c.setDamping(0.4f);
    c.setWidth(1.0f);
    c.setPanLevels(REVERB_OUTPUT_VOLUME, REVERB_OUTPUT_VOLUME);
    return c;
  }

  @Test
  void reverbSilenceStaysSilent() {
    Reverb.Container c = freshReverb(Reverb.Model.FREEVERB);
    for (int blk = 0; blk < 8; blk++) {
      int[] in = new int[128]; // silence
      int[][] out = new int[128][2];
      c.process(in, out);
      for (int i = 0; i < 128; i++) {
        assertEquals(0, out[i][0], "silent L blk" + blk + " @" + i);
        assertEquals(0, out[i][1], "silent R blk" + blk + " @" + i);
      }
    }
  }

  @Test
  void reverbProducesWetTail() {
    // Feed a tone for a few blocks, then silence; the reverb must keep ringing (wet tail). Regression
    // guard for the missing setPanLevels — with pan 0 every model emitted silence.
    Reverb.Container c = freshReverb(Reverb.Model.FREEVERB);
    long tail = 0;
    for (int blk = 0; blk < 30; blk++) {
      int[] in = new int[128];
      if (blk < 8) {
        for (int i = 0; i < 128; i++) in[i] = (int) (Math.sin((blk * 128 + i) * 0.2) * 0.5 * ONE);
      }
      int[][] out = new int[128][2];
      c.process(in, out);
      if (blk >= 12) { // after the dry tone ends — pure reverb tail
        for (int i = 0; i < 128; i++) tail += Math.abs((long) out[i][0]) + Math.abs((long) out[i][1]);
      }
    }
    assertTrue(tail > 0, "reverb must produce a wet tail after the input stops (tail=" + tail + ")");
  }

  @Test
  void reverbIsDeterministic() {
    Reverb.Container a = freshReverb(Reverb.Model.FREEVERB);
    Reverb.Container b = freshReverb(Reverb.Model.FREEVERB);
    long ha = 0;
    long hb = 0;
    for (int blk = 0; blk < 20; blk++) {
      // process() mutates the input array in place (the reverb input HPF), so give each its own copy.
      int[] inA = new int[128];
      int[] inB = new int[128];
      for (int i = 0; i < 128; i++) {
        int s = (int) (Math.sin((blk * 128 + i) * 0.17) * 0.4 * ONE);
        inA[i] = s;
        inB[i] = s;
      }
      int[][] oa = new int[128][2];
      int[][] ob = new int[128][2];
      a.process(inA, oa);
      b.process(inB, ob);
      ha ^= signature(oa, 128);
      hb ^= signature(ob, 128);
    }
    assertEquals(ha, hb, "reverb must be deterministic");
  }

  @Test
  void reverbGoldenSignature() {
    Reverb.Container c = freshReverb(Reverb.Model.FREEVERB);
    long h = 1469598103934665603L;
    for (int blk = 0; blk < 20; blk++) {
      int[][] nb = noiseBlock(128, 500 + blk);
      int[] in = new int[128];
      for (int i = 0; i < 128; i++) in[i] = (nb[i][0] >> 1) + (nb[i][1] >> 1); // mono sum, as the engine feeds it
      int[][] out = new int[128][2];
      c.process(in, out);
      h ^= signature(out, 128);
    }
    assertEquals(REVERB_GOLDEN, h, "reverb output drifted — re-baseline only if intended");
  }

  // Golden signatures captured from the current verified fw2 behavior. Update ONLY for an
  // intentional, C-justified DSP change (and say so in the commit).
  private static final long COMPRESSOR_GOLDEN = 3592526422808312300L;
  private static final long DELAY_GOLDEN = 843932180224107487L;
  private static final long REVERB_GOLDEN = 4308429854311107464L;
}
