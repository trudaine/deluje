package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for BridgeContract — verifies array dimensions, defaults, and VM registration. */
class BridgeContractTest {

  private ChuckVM vm;
  private BridgeContract bridge;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
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
    assertEquals(120.0, vm.getGlobalFloat(BridgeContract.G_BPM), 0.001);
    assertEquals(0.5, vm.getGlobalFloat(BridgeContract.G_SWING), 0.001);
    assertEquals(0L, vm.getGlobalInt(BridgeContract.G_PLAY));
    assertEquals(-1L, vm.getGlobalInt(BridgeContract.G_CURRENT_STEP));
    assertEquals(1.0, vm.getGlobalFloat(BridgeContract.G_MASTER_VOL), 0.001);

    // FX defaults
    assertEquals(0.375, vm.getGlobalFloat(BridgeContract.G_DELAY_TIME), 0.001);
    assertEquals(0.4, vm.getGlobalFloat(BridgeContract.G_DELAY_FB), 0.001);
    assertEquals(0.6, vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM), 0.001);
  }

  @Test
  void testPatternArrayRegistered() {
    Object obj = vm.getGlobalObject(BridgeContract.G_PATTERN);
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
    ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
    assertNotNull(vel);
    for (int i = 0; i < BridgeContract.PATTERN_SIZE; i++) {
      assertEquals(0.8, vel.getFloat(i), 0.001, "velocity default should be 0.8");
    }
  }

  @Test
  void testProbabilityDefaults() {
    ChuckArray prob = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PROBABILITY);
    assertNotNull(prob);
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      assertEquals(1.0, prob.getFloat(t), 0.001, "probability default should be 1.0");
    }
  }

  @Test
  void testEnvArraySize() {
    ChuckArray envArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_ENV);
    assertNotNull(envArr);
    // 4 envelopes × 4 params = 16 slots
    for (int e = 0; e < BridgeContract.ENV_COUNT; e++) {
      int base = e * BridgeContract.ENV_PARAMS;
      assertEquals(0.01, envArr.getFloat(base + 0), 0.001, "env" + e + " attack");
      assertEquals(0.1, envArr.getFloat(base + 1), 0.001, "env" + e + " decay");
      assertEquals(0.7, envArr.getFloat(base + 2), 0.001, "env" + e + " sustain");
      assertEquals(0.2, envArr.getFloat(base + 3), 0.001, "env" + e + " release");
    }
  }

  @Test
  void testLfoArraySize() {
    ChuckArray rateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
    ChuckArray typeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    assertNotNull(rateArr);
    assertNotNull(typeArr);
    for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
      assertEquals(1.0, rateArr.getFloat(l), 0.001, "lfo" + l + " default rate");
      assertEquals(0L, typeArr.getInt(l), "lfo" + l + " default type = SINE");
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
    ChuckArray vel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
    assertEquals(1.0, vel.getFloat(0), 0.001);

    bridge.setVelocity(0, 0, -0.1); // below 0 → clamp
    assertEquals(0.0, vel.getFloat(0), 0.001);
  }

  @Test
  void testReRegisterAfterClear() {
    // Simulate what SequencerApp.loadEngine() does
    vm.clear();
    bridge.register(vm);

    // Arrays must still be accessible
    assertNotNull(vm.getGlobalObject(BridgeContract.G_PATTERN));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_PROBABILITY));

    // Pattern data survives clear (same Java object)
    bridge.setStep(2, 5, true);
    assertTrue(bridge.getStep(2, 5));
  }

  @Test
  void testFilterDefaults() {
    ChuckArray f = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER);
    assertNotNull(f);
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      assertEquals(1.0, f.getFloat(t * 2), 0.001, "filter freq default = open");
      assertEquals(0.5, f.getFloat(t * 2 + 1), 0.001, "filter res default");
    }
    ChuckArray fm = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_MODE);
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
}
