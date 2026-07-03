package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Phase 2: pins the pure launch-quantization math ported from Session::investigateSyncedLaunch. */
class LaunchQuantizerTest {

  @Test
  void syncedQuantization_usesPlayingClipLoopLengthByDefault() {
    // Longest starting clip is not shorter than the loop -> no subdivision, use the loop length.
    assertEquals(96, LaunchQuantizer.syncedQuantization(96, 192, true));
    assertEquals(96, LaunchQuantizer.syncedQuantization(96, 96, true));
  }

  @Test
  void syncedQuantization_subdividesWhenLongestDividesLoopEvenly() {
    // 96 divides 192 evenly and is shorter -> quantize to 96 (a subdivision of the 192 loop).
    assertEquals(96, LaunchQuantizer.syncedQuantization(192, 96, true));
    assertEquals(64, LaunchQuantizer.syncedQuantization(192, 64, true)); // 192 = 3*64
  }

  @Test
  void syncedQuantization_fallsBackToLoopWhenNotAnEvenDivisor() {
    // 100 does not divide 192 -> use the full loop length.
    assertEquals(192, LaunchQuantizer.syncedQuantization(192, 100, true));
  }

  @Test
  void syncedQuantization_subdivisionCanBeDisabled() {
    assertEquals(192, LaunchQuantizer.syncedQuantization(192, 96, false));
  }

  @Test
  void magnifyToThreeBeats_doublesUntilAtLeastThreeBeats() {
    // 1 bar = 96 ticks -> 3 beats = (96*3)>>2 = 72. Scale 24 doubles 24->48->96 (>=72).
    assertEquals(96, LaunchQuantizer.magnifyToThreeBeats(24, 96));
    // Already >= three beats: unchanged.
    assertEquals(96, LaunchQuantizer.magnifyToThreeBeats(96, 96));
    // Tiny bar (<2) uses the bar itself as the target.
    assertEquals(4, LaunchQuantizer.magnifyToThreeBeats(4, 1));
  }

  @Test
  void ticksTilLaunch_isNextBoundary_fullWindowWhenOnBoundary() {
    assertEquals(76, LaunchQuantizer.ticksTilLaunch(20, 96));
    assertEquals(96, LaunchQuantizer.ticksTilLaunch(0, 96), "on boundary -> a full window ahead");
    assertEquals(
        96, LaunchQuantizer.ticksTilLaunch(96, 96), "exactly one window in -> next boundary");
    assertEquals(92, LaunchQuantizer.ticksTilLaunch(100, 96), "wraps: 100 % 96 = 4 -> 92");
    assertEquals(0, LaunchQuantizer.ticksTilLaunch(50, 0), "guard: zero quantization");
  }
}
