package org.deluge.firmware2;

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

    LivePitchShifter b =
        new LivePitchShifter(LiveInputBuffer.InputType.INPUT_L, 17000000); // < 1 semitone up
    assertEquals(1, b.numChannels);
    assertEquals(2048, b.samplesTilHopEnd);
    assertEquals(256, b.nextCrossfadeLength);

    LivePitchShifter c =
        new LivePitchShifter(LiveInputBuffer.InputType.INPUT_R, 30000000); // big up
    assertEquals(256, c.samplesTilHopEnd);
    assertEquals(256, c.nextCrossfadeLength);

    assertEquals(
        LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT,
        a.playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode);
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
        maxHop =
            (Functions.interpolateTableSigned(p, 26, LivePitchShifter.maxHopLengthFine, 4) >> 16)
                * 100;
        rand = Functions.interpolateTableSigned(p, 26, LivePitchShifter.randomFine, 4);
      } else {
        int pl = pitchLog;
        if (pl > (896 << 20)) pl = 896 << 20;
        else if (pl < (768 << 20)) pl = 768 << 20;
        int p = pl - (768 << 20);
        minS = Functions.interpolateTableSigned(p, 27, LivePitchShifter.minSearchCoarse, 2) >> 9;
        maxS = Functions.interpolateTableSigned(p, 27, LivePitchShifter.maxSearchCoarse, 2) >> 9;
        perc =
            Functions.interpolateTableSigned(p, 27, LivePitchShifter.percThresholdCoarse, 2) >> 16;
        nextCf = Functions.interpolateTableSigned(p, 27, LivePitchShifter.crossfadeCoarse, 2) >> 12;
        maxHop =
            (Functions.interpolateTableSigned(p, 27, LivePitchShifter.maxHopLengthCoarse, 2) >> 16)
                * 100;
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

  /**
   * At unity pitch (kMaxSampleValue), the live pitch shifter sets RAW_DIRECT mode and passes input
   * through. Render a block with a known input and verify the output is non-zero (the input reaches
   * the output). Also checks that the hop-search paths are traversed without crashing.
   */
  @Test
  void renderAtUnityPitchProducesOutput() {
    LivePitchShifter ls =
        new LivePitchShifter(LiveInputBuffer.InputType.STEREO, LivePitchShifter.K_MAX_SAMPLE_VALUE);
    LiveInputBuffer lib = new LiveInputBuffer();

    int n = 128;
    int[] input = new int[n * 2];
    Random r = new Random(42);
    for (int i = 0; i < input.length; i++) {
      input[i] = r.nextInt() >> 2;
    }
    lib.giveInput(input, n, 0, LiveInputBuffer.InputType.STEREO);

    int[] out = new int[n * 2];
    ls.render(out, n, LivePitchShifter.K_MAX_SAMPLE_VALUE, 1 << 27, 0, 16, lib, n, input);

    // Output should be non-zero — the unity-pitch path passes input through.
    long energy = 0;
    for (int v : out) {
      energy += Math.abs((long) v);
    }
    assertTrue(energy > 0, "LivePitchShifter render should produce output at unity pitch");
  }

  /** At 1.5x pitch, verify the render loop runs (RAW_REPITCHING mode, hopEnd paths exercised). */
  @Test
  void renderAtPitchUpProducesOutput() {
    int phaseIncrement = LivePitchShifter.K_MAX_SAMPLE_VALUE + (1 << 23); // ~1.5x
    LivePitchShifter ls = new LivePitchShifter(LiveInputBuffer.InputType.INPUT_L, phaseIncrement);
    LiveInputBuffer lib = new LiveInputBuffer();

    int n = 128;
    // giveInput expects interleaved-stereo even for mono types (reads input[inIdx*2]).
    int[] input = new int[n * 2];
    for (int i = 0; i < n; i++) {
      input[i * 2] = (i % 10 == 0) ? (Integer.MAX_VALUE >> 1) : 0; // sparse pulses in L channel
    }
    lib.giveInput(input, n, 0, LiveInputBuffer.InputType.INPUT_L);

    int[] out = new int[n];
    // Need enough input in the buffer for the hop search. Prime with more data.
    int[] more = new int[n * 2];
    for (int i = 0; i < n; i++) {
      more[i * 2] = (i % 7 == 0) ? (Integer.MAX_VALUE >> 2) : 0;
    }
    lib.giveInput(more, n, n, LiveInputBuffer.InputType.INPUT_L);

    // Render a small block — the hop will fire (samplesTilHopEnd starts at 32 for this pitch).
    int[] newInput = new int[32 * 2];
    for (int i = 0; i < 32; i++) {
      newInput[i * 2] = (i % 5 == 0) ? (Integer.MAX_VALUE >> 3) : 0;
    }
    ls.render(out, 32, phaseIncrement, 1 << 27, 0, 16, lib, n * 2 + 32, newInput);

    long energy = 0;
    for (int v : out) {
      energy += Math.abs((long) v);
    }
    assertTrue(energy > 0, "LivePitchShifter render at pitch-up should produce output");
  }
}
