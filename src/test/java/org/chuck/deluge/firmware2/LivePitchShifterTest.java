package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Faithful-port checks for {@link LivePitchShifter} (constructor, helpers, hop-parameter head). */
class LivePitchShifterTest {

  @Test
  void constructorMatchesC() {
    LivePitchShifter a = new LivePitchShifter(LiveInputBuffer.InputType.STEREO, 1 << 23); // < unity
    assertEquals(2, a.numChannels);
    assertEquals(32, a.nextCrossfadeLength);
    assertEquals(32, a.samplesTilHopEnd);
    assertEquals(LivePitchShifter.K_MAX_SAMPLE_VALUE, a.crossfadeProgress);

    LivePitchShifter b = new LivePitchShifter(LiveInputBuffer.InputType.INPUT_L, 17000000); // < 1 semitone up
    assertEquals(1, b.numChannels);
    assertEquals(2048, b.samplesTilHopEnd);
    assertEquals(256, b.nextCrossfadeLength);

    LivePitchShifter c = new LivePitchShifter(LiveInputBuffer.InputType.INPUT_R, 30000000); // big up
    assertEquals(256, c.samplesTilHopEnd);
    assertEquals(256, c.nextCrossfadeLength);

    assertEquals(LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT, a.playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode);
    assertEquals(0xFFFFFFFF, a.playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos);
  }

  @Test
  void helpersMatchC() {
    LivePitchShifter ls = new LivePitchShifter(LiveInputBuffer.InputType.INPUT_L, 30000000);
    ls.crossfadeProgress = LivePitchShifter.K_MAX_SAMPLE_VALUE; // not sounding
    assertFalse(ls.olderPlayHeadIsCurrentlySounding());
    assertTrue(ls.mayBeRemovedWithoutClick()); // newer is RAW_DIRECT
    ls.crossfadeProgress = 1000; // mid-crossfade
    assertTrue(ls.olderPlayHeadIsCurrentlySounding());
    assertFalse(ls.mayBeRemovedWithoutClick());
  }

  @Test
  void computeLiveHopParametersMatchesC() {
    Random r = new Random(818);
    for (int t = 0; t < 200_000; t++) {
      int phase = 1 + r.nextInt(Integer.MAX_VALUE);
      int pitchLog = Functions.quickLog(phase);
      int minS;
      int maxS;
      int perc;
      int nextCf;
      int maxHop;
      int rand;
      if (pitchLog >= (800 << 20) && pitchLog < (864 << 20)) {
        int p = pitchLog - (800 << 20);
        minS = Functions.interpolateTableSigned(p, 26, LivePitchShifter.minSearchFine, 4) >> 9;
        maxS = Functions.interpolateTableSigned(p, 26, LivePitchShifter.maxSearchFine, 4) >> 9;
        perc = Functions.interpolateTableSigned(p, 26, LivePitchShifter.percThresholdFine, 4) >> 16;
        nextCf = Functions.interpolateTableSigned(p, 26, LivePitchShifter.crossfadeFine, 4) >> 12;
        maxHop = (Functions.interpolateTableSigned(p, 26, LivePitchShifter.maxHopLengthFine, 4) >> 16) * 100;
        rand = Functions.interpolateTableSigned(p, 26, LivePitchShifter.randomFine, 4);
      } else {
        int pl = pitchLog;
        if (pl > (896 << 20)) pl = 896 << 20;
        else if (pl < (768 << 20)) pl = 768 << 20;
        int p = pl - (768 << 20);
        minS = Functions.interpolateTableSigned(p, 27, LivePitchShifter.minSearchCoarse, 2) >> 9;
        maxS = Functions.interpolateTableSigned(p, 27, LivePitchShifter.maxSearchCoarse, 2) >> 9;
        perc = Functions.interpolateTableSigned(p, 27, LivePitchShifter.percThresholdCoarse, 2) >> 16;
        nextCf = Functions.interpolateTableSigned(p, 27, LivePitchShifter.crossfadeCoarse, 2) >> 12;
        maxHop = (Functions.interpolateTableSigned(p, 27, LivePitchShifter.maxHopLengthCoarse, 2) >> 16) * 100;
        rand = Functions.interpolateTableSigned(p, 27, LivePitchShifter.randomCoarse, 2);
      }
      int[] got = LivePitchShifter.computeLiveHopParameters(phase);
      assertEquals(minS, got[LivePitchShifter.LHP_MIN_SEARCH], "minSearch phase=" + phase);
      assertEquals(maxS, got[LivePitchShifter.LHP_MAX_SEARCH], "maxSearch");
      assertEquals(perc, got[LivePitchShifter.LHP_PERC_THRESHOLD], "percThreshold");
      assertEquals(nextCf, got[LivePitchShifter.LHP_NEXT_CROSSFADE], "nextCrossfade");
      assertEquals(maxHop, got[LivePitchShifter.LHP_MAX_HOP_LENGTH], "maxHopLength");
      assertEquals(rand, got[LivePitchShifter.LHP_RANDOM], "random");
    }
  }
}
