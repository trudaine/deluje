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
  void computeHopParametersMatchesC() {
    Random r = new Random(123);
    for (int n = 0; n < 200_000; n++) {
      int timeStretchRatio = 1 + (r.nextInt(Integer.MAX_VALUE)); // covers <1x, 1x, >1x
      int noise = r.nextInt();

      // Re-derive the C (time_stretcher.cpp:317-373) independently.
      int speedLog = Functions.quickLog(timeStretchRatio);
      int minBW;
      int maxBW;
      int cfProp;
      int cfAbs;
      int rand;
      if (speedLog >= (800 << 20) && speedLog < (864 << 20)) {
        int position = speedLog - (800 << 20);
        minBW = Functions.interpolateTableSigned(position, 26, TimeStretcher.minHopSizeFine, 4) >> 16;
        maxBW = Functions.interpolateTableSigned(position, 26, TimeStretcher.maxHopSizeFine, 4) >> 16;
        cfProp = Functions.interpolateTableSigned(position, 26, TimeStretcher.crossfadeProportionalFine, 4) << 8;
        cfAbs = Functions.interpolateTableSigned(position, 26, TimeStretcher.crossfadeAbsoluteFine, 4) >> 16;
        rand = Functions.interpolateTableSigned(position, 26, TimeStretcher.randomFine, 4);
      } else {
        int sl = speedLog;
        if (sl > (896 << 20)) sl = (896 << 20);
        else if (sl < (768 << 20)) sl = (768 << 20);
        int position = sl - (768 << 20);
        minBW = Functions.interpolateTableSigned(position, 27, TimeStretcher.minHopSizeCoarse, 2) >> 16;
        maxBW = Functions.interpolateTableSigned(position, 27, TimeStretcher.maxHopSizeCoarse, 2) >> 16;
        cfProp = Functions.interpolateTableSigned(position, 27, TimeStretcher.crossfadeProportionalCoarse, 2) << 8;
        cfAbs = Functions.interpolateTableSigned(position, 27, TimeStretcher.crossfadeAbsoluteCoarse, 2) >> 16;
        rand = Functions.interpolateTableSigned(position, 27, TimeStretcher.randomCoarse, 2);
      }
      minBW += Functions.multiply_32x32_rshift32(
              minBW, Functions.multiply_32x32_rshift32(noise, rand << 8)) << 2;

      int[] got = TimeStretcher.computeHopParameters(timeStretchRatio, noise);
      assertEquals(minBW, got[TimeStretcher.HP_MIN_BEAM_WIDTH], "minBeamWidth ratio=" + timeStretchRatio);
      assertEquals(maxBW, got[TimeStretcher.HP_MAX_BEAM_WIDTH], "maxBeamWidth");
      assertEquals(cfProp, got[TimeStretcher.HP_CROSSFADE_PROPORTIONAL], "crossfadeProportional");
      assertEquals(cfAbs, got[TimeStretcher.HP_CROSSFADE_ABSOLUTE], "crossfadeAbsolute");
      assertEquals(rand, got[TimeStretcher.HP_RANDOM_ELEMENT], "randomElement");
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
