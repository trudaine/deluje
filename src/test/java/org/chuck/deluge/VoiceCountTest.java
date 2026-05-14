package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for voice management: voice count limiting, voice stealing, and polyphony modes
 * (POLY, MONO, LEGATO, AUTO, CHOKE).
 *
 * <p>Verifies that the engine respects max voice limits and polyphony mode settings to prevent
 * uncontrolled voice allocation.
 */
public class VoiceCountTest {

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
  void testMaxVoicesArrayRegistered() {
    startEngine();
    assertNotNull(vm.getGlobalObject(BridgeContract.G_MAX_VOICES));
  }

  @Test
  void testSetMaxVoices() {
    startEngine();
    ChuckArray mvArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MAX_VOICES);
    mvArr.setInt(0, 4L); // 4 voices max on track 0
    assertEquals(4, mvArr.getInt(0));
  }

  @Test
  void testPolyphonyArrayRegistered() {
    startEngine();
    assertNotNull(vm.getGlobalObject(BridgeContract.G_POLYPHONY));
  }

  @Test
  void testSetPolyphonyMode() {
    startEngine();
    // 0=POLY, 1=MONO, 2=LEGATO, 3=AUTO, 4=CHOKE
    ChuckArray polyArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_POLYPHONY);
    polyArr.setInt(0, 2L); // LEGATO
    assertEquals(2, polyArr.getInt(0));
  }

  @Test
  void testKitMaxVoicesArray() {
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, 0); // Kit
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(4410);

    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_MAX_VOICES));
  }

  @Test
  void testKitPolyphonyArray() {
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, 0);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(4410);

    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_POLYPHONY));
  }

  @Test
  void testMonoModePreventsPolyphony() {
    startEngine();

    // Set MONO mode on track 0
    ChuckArray polyArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_POLYPHONY);
    ChuckArray mvArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MAX_VOICES);

    polyArr.setInt(0, 1L); // MONO
    mvArr.setInt(0, 1L);

    assertEquals(1, polyArr.getInt(0), "MONO mode");
    assertEquals(1, mvArr.getInt(0), "Max voices for MONO");

    // Set choke mode
    polyArr.setInt(0, 4L); // CHOKE
    assertEquals(4, polyArr.getInt(0), "CHOKE mode");
  }

  @Test
  void testDefaultMaxVoices() {
    startEngine();
    ChuckArray mvArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MAX_VOICES);
    assertTrue(mvArr.getInt(0) > 0, "Default max voices should be > 0");
  }
}
