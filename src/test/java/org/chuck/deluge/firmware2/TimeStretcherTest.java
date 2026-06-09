package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Faithful-port checks for the self-contained pieces of {@link TimeStretcher} (Phase B of the
 * sample-engine plan). hopEnd/readFromBuffer are deferred (they need the reader/guide/cache), so these
 * re-derive the C formulas directly — there is no firmware/ oracle (its TimeStretcher is the 112-line
 * simplification).
 */
class TimeStretcherTest {

  @Test
  void getSamplePosMatchesC() {
    TimeStretcher ts = new TimeStretcher();
    Random r = new Random(5);
    for (int n = 0; n < 100_000; n++) {
      long pos = ((long) r.nextInt()) << 24 | (r.nextInt() & 0xFFFFFF); // arbitrary 24.x fixed
      ts.samplePosBig = pos;
      assertEquals((int) (pos >> 24), ts.getSamplePos(1), "fwd");
      assertEquals((int) ((pos + 16777215) >> 24), ts.getSamplePos(-1), "rev");
    }
  }

  @Test
  void getTotalDifferenceAbsMatchesC() {
    Random r = new Random(6);
    for (int n = 0; n < 100_000; n++) {
      int[] a = {r.nextInt(), r.nextInt(), r.nextInt()};
      int[] b = {r.nextInt(), r.nextInt(), r.nextInt()};
      int expected = 0;
      for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES; i++) {
        int d = b[i] - a[i];
        if (d < 0) d = -d;
        expected += d;
      }
      assertEquals(expected, TimeStretcher.getTotalDifferenceAbs(a, b));
    }
  }

  @Test
  void getTotalChangeMatchesC() {
    Random r = new Random(7);
    for (int n = 0; n < 100_000; n++) {
      int[] a = {r.nextInt(), r.nextInt(), r.nextInt()};
      int[] b = {r.nextInt(), r.nextInt(), r.nextInt()};
      int expected = 0;
      for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES; i++) expected += b[i];
      for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES; i++) expected -= a[i];
      assertEquals(expected, TimeStretcher.getTotalChange(a, b));
    }
  }
}
