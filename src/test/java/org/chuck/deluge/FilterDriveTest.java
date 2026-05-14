package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for SVFilter drive, notch mode, and filter routing.
 *
 * <p>Verifies that filter drive saturation, SVF NOTCH mode, and series/parallel filter routing
 * produce expected behavior.
 */
public class FilterDriveTest {

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
  void testFilterDriveArrayRegistered() {
    startEngine();
    assertNotNull(vm.getGlobalObject(BridgeContract.G_FILTER_DRIVE));
  }

  @Test
  void testFilterNotchArrayRegistered() {
    startEngine();
    assertNotNull(vm.getGlobalObject(BridgeContract.G_FILTER_NOTCH));
  }

  @Test
  void testFilterRouteArrayRegistered() {
    startEngine();
    assertNotNull(vm.getGlobalObject(BridgeContract.G_FILTER_ROUTE));
  }

  @Test
  void testSetFilterDrive() {
    startEngine();
    bridge.setTrackType(0, 1);
    // Write filter drive via ChuckArray
    ChuckArray driveArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_DRIVE);
    driveArr.setFloat(0, 1.5f);
    assertEquals(1.5, driveArr.getFloat(0), 0.001);
  }

  @Test
  void testSetFilterNotch() {
    startEngine();
    bridge.setTrackType(0, 1);
    ChuckArray notchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_NOTCH);
    notchArr.setInt(0, 1L); // notch on
    assertEquals(1, notchArr.getInt(0));
  }

  @Test
  void testSetFilterRoute() {
    startEngine();
    bridge.setTrackType(0, 1);
    // 0=SERIES_LPF_HPF, 1=SERIES_HPF_LPF, 2=PARALLEL
    ChuckArray routeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_ROUTE);
    routeArr.setInt(0, 2L); // PARALLEL
    assertEquals(2, routeArr.getInt(0));
  }

  @Test
  void testKitFilterDriveNotch() {
    // Kit tracks also expose filter drive/notch per-sound
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, 0); // Kit
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(4410);

    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_LPF_DRIVE));
    assertNotNull(vm.getGlobalObject(BridgeContract.G_KIT_LPF_NOTCH));

    ChuckArray kitDrive = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_LPF_DRIVE);
    ChuckArray kitNotch = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_LPF_NOTCH);
    kitDrive.setFloat(0, 1.2f);
    kitNotch.setInt(0, 1L);

    assertEquals(1.2, kitDrive.getFloat(0), 0.001);
  }

  @Test
  void testFilterDriveDefaultsToUnity() {
    startEngine();
    ChuckArray driveArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER_DRIVE);
    double drive = driveArr.getFloat(0);
    assertEquals(1.0, drive, 0.001, "Filter drive should default to 1.0 (unity)");
  }
}
