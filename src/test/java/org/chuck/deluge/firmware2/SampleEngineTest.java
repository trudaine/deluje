package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Faithful-port checks for the firmware2 sample-engine foundation (Phase A/B): SampleHolder duration
 * helpers and SamplePlaybackGuide.setupPlaybackBounds. Pure position math re-derived from the C
 * (sample_holder.cpp, sample_playback_guide.cpp) — no firmware/ oracle.
 */
class SampleEngineTest {

  private static Sample sample(int numChannels, int byteDepth, int startPosBytes, long lengthInSamples) {
    Sample s = new Sample();
    s.numChannels = numChannels;
    s.byteDepth = byteDepth;
    s.audioDataStartPosBytes = startPosBytes;
    s.lengthInSamples = lengthInSamples;
    return s;
  }

  @Test
  void sampleHolderDurationMatchesC() {
    Random r = new Random(9);
    for (int n = 0; n < 50_000; n++) {
      long len = r.nextInt(2_000_000);
      long start = r.nextInt(1_000_000);
      long end = r.nextInt(2_000_000);
      SampleHolder h = new SampleHolder();
      h.audioFile = sample(2, 3, 44, len);
      h.startPos = start;
      h.endPos = end;
      // C: getEndPos(false) = min(endPos, lengthInSamples); getEndPos(true) = endPos.
      assertEquals(Math.min(end, len), h.getEndPos(false));
      assertEquals(end, h.getEndPos(true));
      assertEquals(Math.min(end, len) - start, h.getDurationInSamples(false));
      assertEquals(end - start, h.getDurationInSamples(true));
    }
  }

  @Test
  void setupPlaybackBoundsMatchesC() {
    Random r = new Random(10);
    for (int n = 0; n < 50_000; n++) {
      int numChannels = 1 + r.nextInt(2);
      int byteDepth = 2 + r.nextInt(2); // 2 or 3
      int startPosBytes = r.nextInt(100);
      long len = 1000 + r.nextInt(1_000_000);
      int start = r.nextInt(500_000);
      int end = r.nextInt(500_000);

      Sample s = sample(numChannels, byteDepth, startPosBytes, len);
      SampleHolder h = new SampleHolder();
      h.audioFile = s;
      h.startPos = start;
      h.endPos = end;
      int bytesPerSample = numChannels * byteDepth;

      for (boolean reversed : new boolean[] {false, true}) {
        SamplePlaybackGuide g = new SamplePlaybackGuide();
        g.audioFileHolder = h;
        g.setupPlaybackBounds(reversed);

        int startSample;
        int endSample;
        if (!reversed) {
          startSample = (int) h.startPos;
          endSample = (int) h.getEndPos();
        } else {
          startSample = (int) h.getEndPos() - 1;
          endSample = (int) h.startPos - 1;
        }
        int expStart = startPosBytes + startSample * bytesPerSample;
        int expEnd = startPosBytes + endSample * bytesPerSample;
        assertEquals(reversed ? -1 : 1, g.playDirection);
        assertEquals(expStart, g.startPlaybackAtByte, "start reversed=" + reversed);
        assertEquals(expEnd, g.endPlaybackAtByte, "end reversed=" + reversed);
      }
    }
  }
}
