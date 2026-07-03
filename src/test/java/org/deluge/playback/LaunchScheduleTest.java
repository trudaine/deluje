package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 3: scheduling — assembling the quantization math with the live transport tick to set the
 * launch event (Session::scheduleLaunchTiming / armAllClipsToStop).
 */
class LaunchScheduleTest {

  @Test
  void scheduleQuantizedLaunch_firesAtNextLoopBoundary() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.lastSwungTickActioned = 1000;
    // Playing clip loops every 96 ticks, currently 20 ticks in -> 76 ticks to the boundary.
    ph.scheduleQuantizedLaunch(96, 96, 20, 1, false);

    assertTrue(ph.hasLaunchEvent());
    assertEquals(1076, ph.getLaunchEventAtSwungTickCount(), "1000 + (96 - 20)");
    assertEquals(76, ph.getSwungTicksTilLaunch());
    assertEquals(1, ph.getNumRepeatsTilLaunch());
  }

  @Test
  void scheduleQuantizedLaunch_onBoundarySchedulesFullWindowAhead() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.lastSwungTickActioned = 500;
    ph.scheduleQuantizedLaunch(96, 96, 0, 1, false); // exactly on the boundary
    assertEquals(596, ph.getLaunchEventAtSwungTickCount(), "500 + full 96 window");
  }

  @Test
  void scheduleLaunchTiming_keepsTheLaterEvent() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.lastSwungTickActioned = 0;
    ph.scheduleLaunchTiming(400, 1); // a long clip's launch
    ph.scheduleLaunchTiming(200, 1); // a shorter clip tries to pull it earlier -> ignored
    assertEquals(
        400, ph.getLaunchEventAtSwungTickCount(), "earlier launch does not override later");

    ph.scheduleLaunchTiming(600, 2); // a later one does extend it
    assertEquals(600, ph.getLaunchEventAtSwungTickCount());
    assertEquals(2, ph.getNumRepeatsTilLaunch());
  }

  @Test
  void scheduleQuantizedLaunch_nothingPlayingIsNoOp() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.scheduleQuantizedLaunch(0, 0, 0, 1, false);
    assertFalse(ph.hasLaunchEvent());
  }

  @Test
  void scheduleQuantizedLaunch_subdividesToShorterStartingClip() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.lastSwungTickActioned = 0;
    // Sync clip loops every 192, but a 96-tick clip is starting and divides it evenly -> quantize
    // to 96. Position 100 -> within the 96 window that's 4, so 92 ticks to the next boundary.
    ph.scheduleQuantizedLaunch(192, 96, 100, 1, true);
    assertEquals(92, ph.getLaunchEventAtSwungTickCount(), "quantized to 96: 96 - (100 % 96)");
  }
}
