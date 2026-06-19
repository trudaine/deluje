package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Faithful-port checks for {@link LivePitchShifterPlayHead} (re-derived; no firmware/ oracle). */
class LivePitchShifterPlayHeadTest {

  private static int[] noisyRaw(long seed) {
    int[] raw = new int[LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE * 2];
    Random r = new Random(seed);
    for (int i = 0; i < raw.length; i++) raw[i] = r.nextInt();
    return raw;
  }

  @Test
  void directRenderMatchesC() {
    int[] raw = noisyRaw(1);
    int nc = 2;
    LivePitchShifterPlayHead ph = new LivePitchShifterPlayHead();
    ph.mode = LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT;
    ph.rawBufferReadPos = 1000;
    int amp = 1 << 26;
    int ampInc = 1 << 8;
    int n = 200;
    int[] out = new int[n * nc];
    ph.render(out, n, nc, 16777216, amp, ampInc, raw, 0, 16);

    // Re-derive.
    int rpos = 1000;
    int a = amp;
    int o = 0;
    int mask = LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1;
    for (int i = 0; i < n; i++) {
      a += ampInc;
      int expL = Functions.multiply_32x32_rshift32_rounded(raw[rpos * nc], a) << 4;
      int expR = Functions.multiply_32x32_rshift32_rounded(raw[rpos * 2 + 1], a) << 4;
      assertEquals(expL, out[i * 2], "L i=" + i);
      assertEquals(expR, out[i * 2 + 1], "R i=" + i);
      rpos = (rpos + 1) & mask;
    }
    assertEquals(rpos, ph.rawBufferReadPos);
  }

  @Test
  void behindAndRemainingMatchC() {
    LiveInputBuffer lib = new LiveInputBuffer();
    lib.numRawSamplesProcessed = 5000;
    int mask = LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1;

    LivePitchShifterPlayHead ph = new LivePitchShifterPlayHead();
    ph.mode = LivePitchShifterPlayHead.PlayHeadMode.RAW_REPITCHING;
    ph.rawBufferReadPos = 4000;
    int phase = (1 << 24) + (1 << 22); // > unity

    int expBehind = (5000 - 4000) & mask;
    assertEquals(expBehind, ph.getNumRawSamplesBehindInput(lib, phase));

    long howFarBack = ((long) expBehind << 24) / (phase & 0xFFFFFFFFL);
    long estimate = (howFarBack << 24) / ((phase - 16777216) & 0xFFFFFFFFL);
    int expRemaining = (estimate >= 2147483647L) ? 2147483647 : (int) estimate;
    assertEquals(expRemaining, ph.getEstimatedPlaytimeRemaining(lib, phase));

    // DIRECT: never runs out / never behind.
    ph.mode = LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT;
    assertEquals(2147483647, ph.getEstimatedPlaytimeRemaining(lib, phase));
    assertEquals(0, ph.getNumRawSamplesBehindInput(lib, phase));
  }

  @Test
  void fillInterpolationBufferMatchesC() {
    LiveInputBuffer lib = new LiveInputBuffer();
    int[] in = new int[3000 * 2];
    Random r = new Random(2);
    for (int i = 0; i < in.length; i++) in[i] = r.nextInt();
    lib.giveInput(in, 3000, 0, LiveInputBuffer.InputType.STEREO);

    LivePitchShifterPlayHead ph = new LivePitchShifterPlayHead();
    ph.rawBufferReadPos = 1500;
    int nc = 2;
    ph.fillInterpolationBuffer(lib, nc);

    int mask = LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1;
    for (int i = 1; i <= 16; i++) {
      int pos = (1500 - i) & mask;
      short eL =
          (short)
              (Integer.compareUnsigned(pos, lib.numRawSamplesProcessed) < 0
                  ? lib.rawBuffer[pos * nc] >> 16
                  : 0);
      short eR =
          (short)
              (Integer.compareUnsigned(pos, lib.numRawSamplesProcessed) < 0
                  ? lib.rawBuffer[pos * nc + 1] >> 16
                  : 0);
      assertEquals(eL, ph.interpolator.bufferL[i - 1], "L tap " + i);
      assertEquals(eR, ph.interpolator.bufferR[i - 1], "R tap " + i);
    }
  }

  @Test
  void rawRepitchingDeterministicAndAudible() {
    int[] raw = noisyRaw(3);
    int nc = 1;
    LivePitchShifterPlayHead a = new LivePitchShifterPlayHead();
    a.mode = LivePitchShifterPlayHead.PlayHeadMode.RAW_REPITCHING;
    a.rawBufferReadPos = 500;
    int[] outA = new int[256];
    a.render(outA, 256, nc, (1 << 24) + (1 << 22), 1 << 27, 0, raw, 3, 16);

    LivePitchShifterPlayHead b = new LivePitchShifterPlayHead();
    b.mode = LivePitchShifterPlayHead.PlayHeadMode.RAW_REPITCHING;
    b.rawBufferReadPos = 500;
    int[] outB = new int[256];
    b.render(outB, 256, nc, (1 << 24) + (1 << 22), 1 << 27, 0, raw, 3, 16);

    org.junit.jupiter.api.Assertions.assertArrayEquals(outA, outB, "deterministic");
    long energy = 0;
    for (int x : outA) energy += Math.abs((long) x);
    assertTrue(energy > 0, "repitched output silent");
  }
}
