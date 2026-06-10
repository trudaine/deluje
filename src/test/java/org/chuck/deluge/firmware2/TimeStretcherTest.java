package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Faithful-port checks for the self-contained pieces of {@link TimeStretcher} (Phase B of the
 * sample-engine plan). hopEnd/readFromBuffer are deferred (they need the reader/guide/cache), so
 * these re-derive the C formulas directly — there is no firmware/ oracle (its TimeStretcher is the
 * 112-line simplification).
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
        minBW =
            Functions.interpolateTableSigned(position, 26, TimeStretcher.minHopSizeFine, 4) >> 16;
        maxBW =
            Functions.interpolateTableSigned(position, 26, TimeStretcher.maxHopSizeFine, 4) >> 16;
        cfProp =
            Functions.interpolateTableSigned(
                    position, 26, TimeStretcher.crossfadeProportionalFine, 4)
                << 8;
        cfAbs =
            Functions.interpolateTableSigned(position, 26, TimeStretcher.crossfadeAbsoluteFine, 4)
                >> 16;
        rand = Functions.interpolateTableSigned(position, 26, TimeStretcher.randomFine, 4);
      } else {
        int sl = speedLog;
        if (sl > (896 << 20)) sl = (896 << 20);
        else if (sl < (768 << 20)) sl = (768 << 20);
        int position = sl - (768 << 20);
        minBW =
            Functions.interpolateTableSigned(position, 27, TimeStretcher.minHopSizeCoarse, 2) >> 16;
        maxBW =
            Functions.interpolateTableSigned(position, 27, TimeStretcher.maxHopSizeCoarse, 2) >> 16;
        cfProp =
            Functions.interpolateTableSigned(
                    position, 27, TimeStretcher.crossfadeProportionalCoarse, 2)
                << 8;
        cfAbs =
            Functions.interpolateTableSigned(position, 27, TimeStretcher.crossfadeAbsoluteCoarse, 2)
                >> 16;
        rand = Functions.interpolateTableSigned(position, 27, TimeStretcher.randomCoarse, 2);
      }
      minBW +=
          Functions.multiply_32x32_rshift32(
                  minBW, Functions.multiply_32x32_rshift32(noise, rand << 8))
              << 2;

      int[] got = TimeStretcher.computeHopParameters(timeStretchRatio, noise);
      assertEquals(
          minBW, got[TimeStretcher.HP_MIN_BEAM_WIDTH], "minBeamWidth ratio=" + timeStretchRatio);
      assertEquals(maxBW, got[TimeStretcher.HP_MAX_BEAM_WIDTH], "maxBeamWidth");
      assertEquals(cfProp, got[TimeStretcher.HP_CROSSFADE_PROPORTIONAL], "crossfadeProportional");
      assertEquals(cfAbs, got[TimeStretcher.HP_CROSSFADE_ABSOLUTE], "crossfadeAbsolute");
      assertEquals(rand, got[TimeStretcher.HP_RANDOM_ELEMENT], "randomElement");
    }
  }

  private static Sample stretchSample(int numChannels, int frames, int[] data) {
    Sample s = new Sample();
    s.numChannels = numChannels;
    s.byteDepth = 3;
    s.sampleRate = 44100;
    s.audioDataStartPosBytes = 44;
    s.audioDataLengthBytes = (long) frames * s.byteDepth * numChannels;
    s.lengthInSamples = frames;
    s.data = data;
    return s;
  }

  /**
   * Constant input → no better crossfade alignment exists, so bestOffset 0 (and no sub-sample
   * shift).
   */
  @Test
  void searchForCrossfadeOffsetConstant() {
    int nc = 2;
    int frames = 20000;
    int[] data = new int[frames * nc];
    java.util.Arrays.fill(data, 0x09990000);
    Sample s = stretchSample(nc, frames, data);
    int bps = s.byteDepth * nc;
    int oldHead = 44 + 8000 * bps;
    int newHead = 44 + 9000 * bps;
    int[] res =
        TimeStretcher.searchForCrossfadeOffset(s, oldHead, newHead, 200, 16777216, 1, 1000, 0);
    assertEquals(0, res[0], "constant bestOffset");
    assertEquals(0, res[1], "constant additionalOscPos");
  }

  /** Deterministic + bestOffset stays within the searched byte range. */
  @Test
  void searchForCrossfadeOffsetDeterministicAndBounded() {
    int nc = 1;
    int frames = 30000;
    int[] data = new int[frames];
    Random r = new Random(404);
    for (int i = 0; i < frames; i++) data[i] = r.nextInt();
    Sample s = stretchSample(nc, frames, data);
    int bps = s.byteDepth * nc;
    int oldHead = 44 + 10000 * bps;
    int newHead = 44 + 15000 * bps;
    int phase = 16777216;
    int samplesTilHopEnd = 2000;

    int[] a =
        TimeStretcher.searchForCrossfadeOffset(
            s, oldHead, newHead, 300, phase, 1, samplesTilHopEnd, 0);
    int[] b =
        TimeStretcher.searchForCrossfadeOffset(
            s, oldHead, newHead, 300, phase, 1, samplesTilHopEnd, 0);
    org.junit.jupiter.api.Assertions.assertArrayEquals(a, b, "deterministic");

    int maxSearchSize = Math.min((samplesTilHopEnd * 40) >> 8, (s.sampleRate / 45) >> 1);
    org.junit.jupiter.api.Assertions.assertTrue(
        Math.abs(a[0]) <= (maxSearchSize + 1) * bps, "bestOffset within search range: " + a[0]);
  }

  /**
   * Full re-derivation of the search (catches any transcription bug). Forward + reverse, resampled.
   */
  @Test
  void searchForCrossfadeOffsetMatchesReDerivation() {
    int nc = 2;
    int frames = 40000;
    int[] data = new int[frames * nc];
    Random r = new Random(505);
    for (int i = 0; i < data.length; i++) data[i] = r.nextInt();
    Sample s = stretchSample(nc, frames, data);
    int bps = s.byteDepth * nc;

    for (int t = 0; t < 400; t++) {
      int dir = (t % 2 == 0) ? 1 : -1;
      int oldFrame = 8000 + r.nextInt(20000);
      int newFrame = 8000 + r.nextInt(20000);
      int oldHead = 44 + oldFrame * bps;
      int newHead = 44 + newFrame * bps;
      int crossfade = 50 + r.nextInt(300);
      int phase = (1 << 23) + r.nextInt(1 << 24); // resampled (≠ unity) so the sub-sample path runs
      int sth = 500 + r.nextInt(3000);
      int olderOscPos = r.nextInt(16777216);

      int[] got =
          TimeStretcher.searchForCrossfadeOffset(
              s, oldHead, newHead, crossfade, phase, dir, sth, olderOscPos);
      int[] exp = reDeriveSearch(s, oldHead, newHead, crossfade, phase, dir, sth, olderOscPos);
      org.junit.jupiter.api.Assertions.assertArrayEquals(exp, got, "t=" + t + " dir=" + dir);
    }
  }

  // Independent transcription of TimeStretcher.searchForCrossfadeOffset for the re-derivation test.
  private static int[] reDeriveSearch(
      Sample s,
      int oldHeadBytePos,
      int newHeadBytePos,
      int crossfadeLengthSamples,
      int phaseIncrement,
      int playDirection,
      int samplesTilHopEnd,
      int olderOscPos) {
    final int KMA = TimeStretcher.K_NUM_MOVING_AVERAGES;
    final int KMAL = TimeStretcher.K_MOVING_AVERAGE_LENGTH;
    final int UNITY = 16777216;
    int bps = s.byteDepth * s.numChannels;
    int start = s.audioDataStartPosBytes;
    int len = (int) s.audioDataLengthBytes;

    int lengthToAverageEach = (int) (((long) phaseIncrement * KMAL) >> 24);
    lengthToAverageEach = Math.max(1, Math.min(KMAL * 2, lengthToAverageEach));
    int cfSrc = (int) (((long) crossfadeLengthSamples * phaseIncrement) >> 24);

    if (oldHeadBytePos < start) return new int[] {0, 0};
    int[] oldT = new int[KMA];
    if (!s.getAveragesForCrossfade(oldT, oldHeadBytePos, cfSrc, playDirection, lengthToAverageEach))
      return new int[] {0, 0};
    int[] newT = new int[KMA];
    if (!s.getAveragesForCrossfade(newT, newHeadBytePos, cfSrc, playDirection, lengthToAverageEach))
      return new int[] {0, 0};

    int bestDifferenceAbs = TimeStretcher.getTotalDifferenceAbs(oldT, newT);
    int bestOffset = 0;
    int initialTotalChange = TimeStretcher.getTotalChange(oldT, newT);
    int additionalOscPos = 0;
    int samplePos = Integer.divideUnsigned(newHeadBytePos - start, bps);
    int mid = samplePos + (cfSrc >> 1) * playDirection;
    int readSample = mid - ((lengthToAverageEach * KMA) >> 1) * playDirection;
    int firstReadByte = readSample * bps + start;
    int maxSearchSize = (samplesTilHopEnd * 40) >> 8;
    maxSearchSize = (int) (((long) maxSearchSize * phaseIncrement) >> 24);
    maxSearchSize = Math.min(maxSearchSize, (s.sampleRate / 45) >> 1);

    int searchDirection = playDirection;
    int numFullDirectionsSearched = 0;
    int timesSignFlipped = 0;
    boolean stop = false;

    while (true) {
      int step = bps * searchDirection;
      int lastTotalChange = initialTotalChange;
      int[] readByte = new int[KMA + 1];
      readByte[0] = firstReadByte;
      int sdrpd = searchDirection * playDirection;
      if (sdrpd == -1) readByte[0] -= playDirection * bps;
      int[] running = new int[KMA];
      for (int i = 0; i < KMA; i++) {
        running[i] = newT[i];
        readByte[i + 1] = readByte[i] + lengthToAverageEach * bps * playDirection;
      }
      int offsetNow = 0;
      int numLeft = maxSearchSize;
      boolean restartOther = false;

      while (numLeft > 0) {
        boolean oob = false;
        for (int i = 0; i < KMA + 1; i++) {
          int btwe =
              (searchDirection == 1) ? (start + len - readByte[i]) : (readByte[i] - (start - bps));
          if (btwe <= 0) {
            oob = true;
            break;
          }
        }
        if (oob) break;

        int rv0 = readVal(s, readByte[0], bps);
        readByte[0] += step;
        int rvb = rv0 * sdrpd;
        for (int i = 1; i < KMA + 1; i++) {
          int trt = running[i - 1] - rvb;
          int rv = readVal(s, readByte[i], bps);
          readByte[i] += step;
          rvb = rv * sdrpd;
          trt += rvb;
          running[i - 1] = trt;
        }
        int differenceAbs = TimeStretcher.getTotalDifferenceAbs(oldT, running);
        if (offsetNow == 0
            && sdrpd == 1
            && numFullDirectionsSearched == 0
            && differenceAbs > bestDifferenceAbs) {
          restartOther = true;
          break;
        }
        offsetNow += step;
        boolean best = (differenceAbs < bestDifferenceAbs);
        if (best) {
          bestDifferenceAbs = differenceAbs;
          bestOffset = offsetNow;
        }
        int thisTotalChange = TimeStretcher.getTotalChange(oldT, running);
        if ((thisTotalChange >>> 31) != (lastTotalChange >>> 31)) {
          if (phaseIncrement != UNITY && (best || bestOffset == offsetNow - step)) {
            long ta = Math.abs((long) thisTotalChange);
            long la = Math.abs((long) lastTotalChange);
            additionalOscPos = (int) ((la << 24) / (la + ta));
            if (sdrpd == -1) additionalOscPos = UNITY - additionalOscPos;
            if (best != (sdrpd == -1)) bestOffset -= bps * playDirection;
          }
          timesSignFlipped++;
          if (timesSignFlipped >= 4) {
            stop = true;
            break;
          }
        }
        lastTotalChange = thisTotalChange;
        numLeft--;
      }

      if (stop) break;
      if (restartOther) {
        searchDirection = -searchDirection;
        continue;
      }
      numFullDirectionsSearched++;
      if (numFullDirectionsSearched < 2) {
        searchDirection = -searchDirection;
        continue;
      }
      break;
    }

    if (phaseIncrement != UNITY) {
      additionalOscPos += olderOscPos;
      if (additionalOscPos >= UNITY) {
        additionalOscPos -= UNITY;
        bestOffset += bps * playDirection;
      }
    }
    return new int[] {bestOffset, additionalOscPos};
  }

  /** Full re-derivation of hopEnd (placement + search) + invariants. */
  @Test
  void hopEndMatchesReDerivation() {
    int nc = 2;
    int frames = 200000;
    int[] data = new int[frames * nc];
    Random r = new Random(606);
    for (int i = 0; i < data.length; i++) data[i] = r.nextInt();
    Sample s = stretchSample(nc, frames, data);
    int bps = s.byteDepth * nc;
    final int UNITY = 16777216;

    for (int t = 0; t < 300; t++) {
      int dir = (t % 2 == 0) ? 1 : -1;
      int samplePos = 50000 + r.nextInt(100000);
      int oldHead = 44 + samplePos * bps;
      int phase = (1 << 23) + r.nextInt(1 << 24);
      int ratio = (1 << 23) + r.nextInt(1 << 25); // around / below / above unity
      int noise = r.nextInt();
      int olderOscPos = r.nextInt(UNITY);

      long combinedIncrement = ((phase & 0xFFFFFFFFL) * (ratio & 0xFFFFFFFFL)) >>> 24;
      TimeStretcher ts = new TimeStretcher();
      ts.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = true;
      int[] got =
          ts.hopEnd(
              s,
              null,
              TimeStretcher.LoopType.NONE,
              oldHead,
              samplePos,
              phase,
              ratio,
              combinedIncrement,
              dir,
              noise,
              olderOscPos);

      // Re-derive.
      int[] hp = TimeStretcher.computeHopParameters(ratio, noise);
      int minBW = (int) (((hp[0] & 0xFFFFFFFFL) * (phase & 0xFFFFFFFFL)) >>> 24);
      int maxBW = (int) (((hp[1] & 0xFFFFFFFFL) * (phase & 0xFFFFFFFFL)) >>> 24);
      int bestBW = (minBW + maxBW) >> 1;
      int beamBackEdge = samplePos + (int) (((long) bestBW * (ratio - UNITY)) >> 25) * dir;
      int wStart = (dir == 1) ? 0 : frames - 1;
      int wEnd = (dir == 1) ? frames : -1;
      if ((beamBackEdge - wStart) * dir < 0) beamBackEdge = wStart;
      int sth = (int) Long.divideUnsigned((long) bestBW << 24, phase & 0xFFFFFFFFL);
      if (sth < 1) sth = 1;
      int cfl = Functions.multiply_32x32_rshift32_rounded(sth, hp[2]) + hp[3] * 4;
      if (cfl >= (sth >> 1)) cfl = sth >> 1;
      sth -= cfl;
      cfl = Math.min(sth, cfl);
      if (cfl < 1) cfl = 1;
      int cfInc = (int) Integer.toUnsignedLong(UNITY) / cfl;
      boolean newerActive = true;
      int[] expRes;
      if ((beamBackEdge - wEnd) * dir >= 0) {
        newerActive = false;
        expRes = new int[] {0, 0};
      } else {
        int newHead = 44 + beamBackEdge * bps;
        int[] sr =
            TimeStretcher.searchForCrossfadeOffset(
                s, oldHead, newHead, cfl, phase, dir, sth, olderOscPos);
        newHead += sr[0];
        int wStartByte = 44 + ((dir != 1) ? ((int) s.audioDataLengthBytes - bps) : 0);
        if ((newHead - wStartByte) * dir < 0) newHead = wStartByte;
        expRes = new int[] {newHead, sr[1]};
      }

      org.junit.jupiter.api.Assertions.assertArrayEquals(expRes, got, "hopEnd t=" + t);
      assertEquals(
          newerActive, ts.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER], "newerActive t=" + t);
      assertEquals(sth, ts.samplesTilHopEnd, "samplesTilHopEnd t=" + t);
      assertEquals(cfInc, ts.crossfadeIncrement, "crossfadeIncrement t=" + t);
      org.junit.jupiter.api.Assertions.assertTrue(ts.samplesTilHopEnd >= 1, "hop >= 1");
    }
  }

  /** Loop pre-margin: near the loop end of a looping clip, the hop lands in the pre-margin. */
  @Test
  void hopEndLoopPreMargin() {
    int nc = 2;
    int frames = 100000;
    int[] data = new int[frames * nc];
    Random r = new Random(707);
    for (int i = 0; i < data.length; i++) data[i] = r.nextInt();
    Sample s = stretchSample(nc, frames, data);
    int bps = s.byteDepth * nc;
    int unity = 16777216;

    SamplePlaybackGuide g = new SamplePlaybackGuide();
    g.startPlaybackAtByte = 44 + 5000 * bps; // loop start frame 5000 → pre-margin = frames 0..4999
    g.endPlaybackAtByte = 44 + 50000 * bps; // loop end frame 50000

    int samplePos = 49990; // 10 frames until the loop end
    int oldHead = 44 + samplePos * bps;
    int noise = 12345;
    long combinedIncrement = ((unity & 0xFFFFFFFFL) * (unity & 0xFFFFFFFFL)) >>> 24; // = unity

    TimeStretcher ts = new TimeStretcher();
    ts.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = true;
    int[] got =
        ts.hopEnd(
            s,
            g,
            TimeStretcher.LoopType.TIMESTRETCHER_LEVEL_IF_ACTIVE,
            oldHead,
            samplePos,
            unity,
            unity,
            combinedIncrement,
            1,
            noise,
            0);

    org.junit.jupiter.api.Assertions.assertTrue(
        ts.hasLoopedBackIntoPreMargin, "should have looped into pre-margin");

    // Re-derive (ratio=unity ⇒ randomElement 0 ⇒ deterministic).
    int[] hp = TimeStretcher.computeHopParameters(unity, noise);
    int minBW = hp[0];
    int sourceSamplesTilLoop = (50000 - samplePos);
    int outputSamplesTilLoop =
        (int)
            ((((long) sourceSamplesTilLoop << 24) + (combinedIncrement >> 1)) / combinedIncrement);
    org.junit.jupiter.api.Assertions.assertTrue(outputSamplesTilLoop < 100);
    int candidate = g.startPlaybackAtByte - outputSamplesTilLoop * bps; // phase==unity branch
    int cfl = Math.max(outputSamplesTilLoop, 10);
    int sth = Math.max(minBW >> 2, cfl);
    assertEquals(sth, ts.samplesTilHopEnd, "samplesTilHopEnd");
    assertEquals(
        Integer.divideUnsigned(16777215 + cfl, cfl), ts.crossfadeIncrement, "crossfadeIncrement");

    int[] sr = TimeStretcher.searchForCrossfadeOffset(s, oldHead, candidate, cfl, unity, 1, sth, 0);
    int newHead = candidate + sr[0];
    if ((newHead - 44) < 0) newHead = 44;
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        new int[] {newHead, sr[1]}, got, "hop result");
  }

  private static int readVal(Sample s, int readByte, int bps) {
    int frame = (readByte - s.audioDataStartPosBytes) / bps;
    int base = frame * s.numChannels;
    int v = s.data[base] >> 16;
    if (s.numChannels == 2) v += s.data[base + 1] >> 16;
    return v;
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
