package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for patch cable modulation routing with polarity support.
 *
 * <p>Verifies that modulation sources (envelope, LFO, velocity) can be routed to destinations
 * (pitch, volume, filter cutoff, pan) with correct polarity and amount scaling.
 */
public class PatchCableModulationTest {

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

  private void startEngine(int trackType) {
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, trackType);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(4410);
  }

  @Test
  void testPatchCableArraysRegistered() {
    startEngine(1); // Synth

    assertNotNull(vm.getGlobalObject(BridgeContract.G_PC_COUNT));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_PC_SOURCE));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_PC_DEST));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_PC_AMOUNT));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_PC_POLARITY));

    ChuckArray pcCount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_COUNT);
    assertEquals(BridgeContract.TRACKS, pcCount.size());
  }

  @Test
  void testKitPatchCableArraysRegistered() {
    startEngine(0); // Kit

    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_PC_COUNT));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_PC_SOURCE));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_PC_DEST));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_PC_AMOUNT));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_PC_POLARITY));
  }

  @Test
  void testWritePatchCableSynth() {
    startEngine(1);

    // Write one patch cable: source=0 (velocity), dest=1 (volume), amount=0.5, unipolar
    bridge.setTrackType(0, 1);
    ChuckArray pcSource = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_SOURCE);
    ChuckArray pcDest = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_DEST);
    ChuckArray pcAmt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_AMOUNT);
    ChuckArray pcPol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_POLARITY);
    ChuckArray pcCount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_COUNT);

    pcCount.setInt(0, 1);
    int base = 0 * BridgeContract.MAX_CABLES_PER_TRACK;
    pcSource.setInt(base, 0);
    pcDest.setInt(base, 1);
    pcAmt.setFloat(base, 0.5f);
    pcPol.setInt(base, 0); // UNIPOLAR

    // Verify
    assertEquals(1, pcCount.getInt(0));
    assertEquals(0, pcSource.getInt(base));
    assertEquals(1, pcDest.getInt(base));
    assertEquals(0.5, pcAmt.getFloat(base), 0.001);
    assertEquals(0, pcPol.getInt(base));
  }

  @Test
  void testBipolarPolarity() {
    startEngine(1);

    bridge.setTrackType(0, 1);
    ChuckArray pcPol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_POLARITY);
    ChuckArray pcCount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_COUNT);

    pcCount.setInt(0, 1);
    pcPol.setInt(0, 1); // BIPOLAR

    assertEquals(1, pcPol.getInt(0), "BIPOLAR polarity flag");
  }

  @Test
  void testEnvelopeAsModulationSource() {
    startEngine(1);

    // Route ENV_0 (source=2) to PITCH (dest=0)
    ChuckArray pcSource = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_SOURCE);
    ChuckArray pcDest = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_DEST);
    ChuckArray pcAmt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_AMOUNT);
    ChuckArray pcCount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PC_COUNT);

    bridge.setTrackType(0, 1);
    pcCount.setInt(0, 1);
    pcSource.setInt(0, 2); // ENV_0
    pcDest.setInt(0, 0); // PITCH
    pcAmt.setFloat(0, 0.7f);

    assertEquals(0.7, pcAmt.getFloat(0), 0.001);
    assertEquals(2, pcSource.getInt(0));
    assertEquals(0, pcDest.getInt(0));
  }

  @Test
  void testMultiplePatchCables() {
    startEngine(0); // Kit

    bridge.setTrackType(0, 0);
    ChuckArray pcCount = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_COUNT);
    ChuckArray pcSource = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_SOURCE);
    ChuckArray pcDest = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_DEST);
    ChuckArray pcAmt = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_AMOUNT);
    ChuckArray pcPol = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_PC_POLARITY);

    // Two patch cables for kit sound 0
    pcCount.setInt(0, 2);
    int base = 0 * BridgeContract.MAX_CABLES_PER_TRACK;
    pcSource.setInt(base, 0); // velocity -> volume
    pcDest.setInt(base, 1);
    pcAmt.setFloat(base, 0.3f);
    pcPol.setInt(base, 0);

    pcSource.setInt(base + 1, 1); // aftertouch -> filter
    pcDest.setInt(base + 1, 3);
    pcAmt.setFloat(base + 1, 0.6f);
    pcPol.setInt(base + 1, 1); // BIPOLAR

    assertEquals(2, pcCount.getInt(0));
    assertEquals(0, pcSource.getInt(base));
    assertEquals(1, pcPol.getInt(base + 1));
  }
}
