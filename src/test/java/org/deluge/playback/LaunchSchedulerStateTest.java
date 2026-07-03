package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ArmState;
import org.deluge.model.ClipModel;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of the session launch scheduler: the arm state on clips and the launch-event fields on
 * {@link PlaybackHandler} (no scheduling logic yet — that's Phase 3).
 */
class LaunchSchedulerStateTest {

  @Test
  void clipArmStateDefaultsOffAndRoundTrips() {
    ClipModel clip = new ClipModel("A", 8, 16);
    assertEquals(ArmState.OFF, clip.getArmState(), "clips start un-armed");

    clip.setArmState(ArmState.ON_TO_SOLO);
    assertEquals(ArmState.ON_TO_SOLO, clip.getArmState());

    clip.setArmState(null);
    assertEquals(ArmState.OFF, clip.getArmState(), "null coalesces to OFF");
  }

  @Test
  void noLaunchEventByDefault() {
    PlaybackHandler ph = new PlaybackHandler();
    assertFalse(ph.hasLaunchEvent());
    assertEquals(0, ph.getLaunchEventAtSwungTickCount());
    assertEquals(-1, ph.getSwungTicksTilLaunch(), "no event -> -1");
  }

  @Test
  void scheduleAndClearLaunchEvent() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.lastSwungTickActioned = 100;
    ph.setLaunchEvent(148, 2); // fires at tick 148

    assertTrue(ph.hasLaunchEvent());
    assertEquals(148, ph.getLaunchEventAtSwungTickCount());
    assertEquals(2, ph.getNumRepeatsTilLaunch());
    assertEquals(48, ph.getSwungTicksTilLaunch(), "148 - 100");

    ph.clearLaunchEvent();
    assertFalse(ph.hasLaunchEvent());
    assertEquals(-1, ph.getSwungTicksTilLaunch());
  }

  @Test
  void ticksTilLaunchNeverNegative() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.lastSwungTickActioned = 200;
    ph.setLaunchEvent(150, 1); // event already in the past
    assertEquals(0, ph.getSwungTicksTilLaunch(), "clamped to 0, not negative");
  }

  @Test
  void stopClearsScheduledLaunch() {
    PlaybackHandler ph = new PlaybackHandler();
    ph.setLaunchEvent(500, 1);
    ph.stop();
    assertFalse(ph.hasLaunchEvent(), "stopping the transport cancels any pending launch");
  }
}
