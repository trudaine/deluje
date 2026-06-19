package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.shadow.core.ChuckArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for BridgeContract — verifies array dimensions, defaults, and VM registration. */
class BridgeContractTest {

  private BridgeContract bridge;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) bridge.shutdown();
  }

  @Test
  void testDimensions() {
    assertEquals(BridgeContract.TRACKS * BridgeContract.STEPS, BridgeContract.PATTERN_SIZE);
    assertTrue(BridgeContract.TRACKS >= 64);
    assertEquals(192, BridgeContract.STEPS);
    assertEquals(4, BridgeContract.ENV_COUNT);
    assertEquals(4, BridgeContract.ENV_PARAMS);
    assertEquals(4, BridgeContract.LFO_COUNT);
  }

  @Test
  void testDefaultsAreRegistered() {
    // Scalars
    assertEquals(120.0, bridge.getGlobalFloat(BridgeContract.G_BPM), 0.001);
    assertEquals(0.5, bridge.getGlobalFloat(BridgeContract.G_SWING), 0.001);
    assertEquals(0L, bridge.getGlobalInt(BridgeContract.G_PLAY));
    assertEquals(-1L, bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP));
    assertEquals(1.0, bridge.getGlobalFloat(BridgeContract.G_MASTER_VOL), 0.001);

    // FX defaults
    assertEquals(0.375, bridge.getGlobalFloat(BridgeContract.G_DELAY_TIME), 0.001);
    assertEquals(0.4, bridge.getGlobalFloat(BridgeContract.G_DELAY_FB), 0.001);
    assertEquals(0.6, bridge.getGlobalFloat(BridgeContract.G_REVERB_ROOM), 0.001);
  }

  @Test
  void testPatternArrayRegistered() {
    Object obj = bridge.getGlobalObject(BridgeContract.G_PATTERN);
    assertNotNull(obj, "g_pattern not registered");
    assertInstanceOf(ChuckArray.class, obj);
    ChuckArray arr = (ChuckArray) obj;
    // All cells start at 0
    for (int i = 0; i < BridgeContract.PATTERN_SIZE; i++) {
      assertEquals(0L, arr.getInt(i), "pattern[" + i + "] should be 0");
    }
  }

  @Test
  void testVelocityDefaults() {
    ChuckArray vel = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_VELOCITY);
    assertNotNull(vel);
    for (int i = 0; i < BridgeContract.PATTERN_SIZE; i++) {
      assertEquals(0.8, vel.getFloat(i), 0.001, "velocity default should be 0.8");
    }
  }

  @Test
  void testProbabilityDefaults() {
    ChuckArray prob = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_PROBABILITY);
    assertNotNull(prob);
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      assertEquals(1.0, prob.getFloat(t), 0.001, "probability default should be 1.0");
    }
  }

  @Test
  void testSetStepAndSnapshot() {
    bridge.setStep(0, 0, true);
    bridge.setStep(0, 4, true);
    bridge.setStep(3, 15, true);

    assertTrue(bridge.getStep(0, 0));
    assertTrue(bridge.getStep(0, 4));
    assertFalse(bridge.getStep(0, 1));
    assertTrue(bridge.getStep(3, 15));

    bridge.clearPattern();
    assertFalse(bridge.getStep(0, 0));
  }

  @Test
  void testMute() {
    assertFalse(bridge.getMute(0));
    bridge.setMute(0, true);
    assertTrue(bridge.getMute(0));
    bridge.setMute(0, false);
    assertFalse(bridge.getMute(0));
  }

  @Test
  void testVelocityClamp() {
    bridge.setVelocity(0, 0, 1.5); // above 1.0 → clamp
    ChuckArray vel = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_VELOCITY);
    assertEquals(1.0, vel.getFloat(0), 0.001);

    bridge.setVelocity(0, 0, -0.1); // below 0 → clamp
    assertEquals(0.0, vel.getFloat(0), 0.001);
  }

  @Test
  void testReRegisterAfterClear() {
    // Simulate what SequencerApp.loadEngine() does
    bridge.clear();

    // Arrays must still be accessible
    assertNotNull(bridge.getGlobalObject(BridgeContract.G_PATTERN));
    assertNotNull(bridge.getGlobalObject(BridgeContract.G_PROBABILITY));

    // Pattern data survives clear (same Java object)
    bridge.setStep(2, 5, true);
    assertTrue(bridge.getStep(2, 5));
  }

  @Test
  void testFilterDefaults() {
    ChuckArray f = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_FILTER);
    assertNotNull(f);
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      assertEquals(1.0, f.getFloat(t * 2), 0.001, "filter freq default = open");
      assertEquals(0.5, f.getFloat(t * 2 + 1), 0.001, "filter res default");
    }
    ChuckArray fm = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_FILTER_MODE);
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      assertEquals(0L, fm.getInt(t), "filter mode default = LADDER_12");
    }
  }

  @Test
  void testPatternArrayAccessibleFromBothSides() {
    // Java writes; verify via ChuckArray API (engine reads same object)
    bridge.setStep(1, 8, true);
    ChuckArray arr = bridge.patternArray();
    assertEquals(1L, arr.getInt(1 * BridgeContract.STEPS + 8));
  }

  @Test
  void testTrackIdAndClipPlayDirection() {
    bridge.setTrackId(5, 42);
    assertEquals(42, bridge.getTrackId(5));

    bridge.setClipPlayDirection(2, 3, 2); // PING_PONG
    assertEquals(2, bridge.getClipPlayDirection(2, 3));

    ChuckArray dirArr = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_CLIP_PLAY_DIRECTION);
    assertNotNull(dirArr);
    assertEquals(2L, dirArr.getInt(2 * BridgeContract.MAX_CLIPS_PER_TRACK + 3));

    ChuckArray trackIdArr = (ChuckArray) bridge.getGlobalObject(BridgeContract.G_TRACK_ID);
    assertNotNull(trackIdArr);
    assertEquals(42L, trackIdArr.getInt(5));
  }
}
