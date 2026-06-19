package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Faithful-port checks for {@link LiveInputBuffer} (re-derived; no firmware/ oracle). */
class LiveInputBufferTest {

  @Test
  void giveInputAngleAndPercMatchC() {
    int numSamples = 300;
    int[] input = new int[numSamples * 2];
    Random r = new Random(11);
    for (int i = 0; i < input.length; i++) input[i] = r.nextInt() >> 1;

    LiveInputBuffer b = new LiveInputBuffer();
    b.giveInput(input, numSamples, 0, LiveInputBuffer.InputType.STEREO);

    // Re-derive.
    int lastSampleRead = 0;
    int lastAngle = 0;
    int[] lpf = new int[2];
    int[] expRaw = new int[LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE * 2];
    byte[] expPerc = new byte[LiveInputBuffer.K_INPUT_PERC_BUFFER_SIZE];
    int mask = LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1;
    for (int n = 0; n < numSamples; n++) {
      int l = input[n * 2];
      int rr = input[n * 2 + 1];
      int thisSampleRead = (l >> 2) + (rr >> 2);
      expRaw[(n & mask) * 2] = l;
      expRaw[(n & mask) * 2 + 1] = rr;
      int angle = thisSampleRead - lastSampleRead;
      lastSampleRead = thisSampleRead;
      if (angle < 0) angle = -angle;
      for (int p = 0; p < 2; p++) {
        int d = angle - lpf[p];
        lpf[p] += Functions.multiply_32x32_rshift32_rounded(d, 1 << 23);
        angle = lpf[p];
      }
      if ((n & (LiveInputBuffer.K_PERC_BUFFER_REDUCTION_SIZE - 1)) == 0) {
        int diff = angle - lastAngle;
        if (diff < 0) diff = -diff;
        int perc = (angle != 0) ? (int) ((((long) diff * 262144) / angle) >> 1) : 0;
        perc = Functions.getTanHUnknown(perc, 23);
        expPerc[
                (n >>> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE)
                    & (LiveInputBuffer.K_INPUT_PERC_BUFFER_SIZE - 1)] =
            (byte) perc;
      }
      lastAngle = angle;
    }

    assertEquals(numSamples, b.numRawSamplesProcessed);
    assertEquals(lastSampleRead, b.lastSampleRead);
    assertEquals(lastAngle, b.lastAngle);
    org.junit.jupiter.api.Assertions.assertArrayEquals(lpf, b.angleLPFMem);
    org.junit.jupiter.api.Assertions.assertArrayEquals(expPerc, b.percBuffer);
    for (int i = 0; i < numSamples * 2; i++) assertEquals(expRaw[i], b.rawBuffer[i], "raw " + i);
  }

  @Test
  void getAveragesForCrossfadeMatchesC() {
    int numSamples = 2000;
    int[] input = new int[numSamples * 2];
    Random r = new Random(22);
    for (int i = 0; i < input.length; i++) input[i] = r.nextInt();
    LiveInputBuffer b = new LiveInputBuffer();
    b.giveInput(input, numSamples, 0, LiveInputBuffer.InputType.STEREO);

    int lengthEach = 35;
    int nc = 2;
    for (int startPos = 0; startPos < 1500; startPos += 137) {
      int[] got = new int[3];
      b.getAveragesForCrossfade(got, startPos, lengthEach, nc);

      int[] exp = new int[3];
      int cur = startPos;
      for (int i = 0; i < 3; i++) {
        int endPos = (cur + lengthEach) & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
        do {
          exp[i] += b.rawBuffer[cur * nc] >> 16;
          exp[i] += b.rawBuffer[cur * 2 + 1] >> 16;
          cur = (cur + 1) & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
        } while (cur != endPos);
      }
      org.junit.jupiter.api.Assertions.assertArrayEquals(exp, got, "startPos=" + startPos);
    }
  }
}
