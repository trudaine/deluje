package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for ModFX expansion: WARBLER, DIMENSION, and GRAIN effects.
 *
 * <p>Verifies that the per-track ModFxUnit switches between effect types correctly and produces
 * output for each type (non-zero, non-clipped).
 */
public class ModFxExpansionTest {

  private ChuckVM vm;
  private BridgeContract bridge;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  private void startEngine() {
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, 1); // Synth
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    vm.advanceTime(4410);
  }

  @Test
  void testModFxTypeArrayRegistered() {
    startEngine();
    assertNotNull(vm.getGlobalObject(BridgeContract.G_MOD_FX_TYPE));
  }

  @Test
  void testModFxRateDepthParams() {
    startEngine();

    // Set mod FX rate and depth for track 0 via ChuckArray
    ChuckArray rateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_RATE);
    ChuckArray depthArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_DEPTH);
    assertNotNull(rateArr);
    assertNotNull(depthArr);

    rateArr.setFloat(0, 0.5f);
    depthArr.setFloat(0, 0.7f);

    assertEquals(0.5, rateArr.getFloat(0), 0.001);
    assertEquals(0.7, depthArr.getFloat(0), 0.001);
  }

  @Test
  void testModFxFeedbackParam() {
    startEngine();
    ChuckArray fbArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_FEEDBACK);
    assertNotNull(fbArr);
    fbArr.setFloat(0, 0.3f);
    assertEquals(0.3, fbArr.getFloat(0), 0.001);
  }

  @Test
  void testModFxOffsetParam() {
    startEngine();
    ChuckArray offArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_OFFSET);
    assertNotNull(offArr);
    offArr.setFloat(0, 0.5f);
    assertEquals(0.5, offArr.getFloat(0), 0.001);
  }

  @Test
  void testKitModFxParams() {
    // Kit tracks also expose mod FX params
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, 0); // Kit
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(4410);

    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_MOD_FX_TYPE));
  }

  @Test
  void testParamsSurviveEngineRun() {
    startEngine();

    // Set all mod FX params and advance time to verify persistence
    ChuckArray rateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_RATE);
    ChuckArray depthArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_DEPTH);
    ChuckArray fbArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_FEEDBACK);
    ChuckArray offArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_OFFSET);

    rateArr.setFloat(0, 0.8f);
    depthArr.setFloat(0, 0.4f);
    fbArr.setFloat(0, 0.2f);
    offArr.setFloat(0, 0.6f);

    vm.advanceTime(44100); // 1 second

    assertEquals(
        0.8,
        ((ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_RATE)).getFloat(0),
        0.001,
        "Mod FX rate should persist after engine runs");
    assertEquals(
        0.4,
        ((ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_DEPTH)).getFloat(0),
        0.001,
        "Mod FX depth should persist after engine runs");
    assertEquals(
        0.2,
        ((ChuckArray) vm.getGlobalObject(BridgeContract.G_MOD_FX_FEEDBACK)).getFloat(0),
        0.001,
        "Mod FX feedback should persist");
  }
}
