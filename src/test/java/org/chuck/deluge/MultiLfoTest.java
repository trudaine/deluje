package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the 4-LFO engine with all 7 waveform types (SINE, SAW, SQUARE, TRIANGLE,
 * S_AND_H, RANDOM_WALK, WARBLER).
 *
 * <p>Verifies LFO array dimensions, waveform type selection, and independent rate/depth/target
 * controls.
 */
@org.junit.jupiter.api.Tag("slow")
@Disabled(
    "Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class MultiLfoTest {

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
  void testFourLfosExist() {
    startEngine();
    ChuckArray lfoArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
    assertNotNull(lfoArr);
    assertEquals(BridgeContract.LFO_COUNT, lfoArr.size());

    // LFO waveform type array
    ChuckArray lfoType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    assertNotNull(lfoType);
    assertEquals(BridgeContract.LFO_COUNT, lfoType.size());
  }

  @Test
  void testAllWaveformTypes() {
    startEngine();
    // Write waveform types directly to the VM global array
    // Type ordinals: 0=SINE, 1=SAW, 2=SQUARE, 3=TRIANGLE, 4=S_AND_H, 5=RANDOM_WALK, 6=WARBLER
    ChuckArray lfoType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    int[] allTypes = {0, 1, 2, 3, 4, 5, 6};
    for (int i = 0; i < allTypes.length && i < BridgeContract.LFO_COUNT; i++) {
      lfoType.setInt(i, allTypes[i]);
    }

    // Re-read and verify
    ChuckArray lfoTypeReread = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    for (int i = 0; i < allTypes.length && i < BridgeContract.LFO_COUNT; i++) {
      assertEquals(allTypes[i], (int) lfoTypeReread.getInt(i), "lfo " + i + " waveform type");
    }
  }

  @Test
  void testLfoRatesIndependent() {
    startEngine();
    ChuckArray lfoRateArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_RATE);
    double[] rates = {0.5, 2.0, 5.0, 10.0};
    for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
      lfoRateArr.setFloat(i, (float) rates[i]);
    }

    for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
      assertEquals(rates[i], lfoRateArr.getFloat(i), 0.001, "lfo " + i + " rate");
    }
  }

  @Test
  void testLfoDepthsIndependent() {
    startEngine();
    ChuckArray lfoDepthArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_DEPTH);
    double[] depths = {0.0, 0.3, 0.7, 1.0};
    for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
      lfoDepthArr.setFloat(i, (float) depths[i]);
    }

    for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
      assertEquals(depths[i], lfoDepthArr.getFloat(i), 0.001, "lfo " + i + " depth");
    }
  }

  @Test
  void testLfoTargets() {
    startEngine();
    ChuckArray lfoTargetArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TARGET);
    int[] targets = {0, 1, 2, 3}; // PITCH, VOLUME, PAN, LPF
    for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
      lfoTargetArr.setInt(i, targets[i]);
    }

    for (int i = 0; i < BridgeContract.LFO_COUNT; i++) {
      assertEquals(targets[i], (int) lfoTargetArr.getInt(i), "lfo " + i + " target");
    }
  }

  @Test
  void testKitLfoCount() {
    // Kit track should also have 4 LFOs per sound
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    bridge.setTrackType(0, 0); // Kit
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(4410);

    ChuckArray lfoType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_LFO_TYPE);
    assertEquals(BridgeContract.LFO_COUNT, lfoType.size());

    // Write types directly
    lfoType.setInt(2, 4); // S_AND_H
    lfoType.setInt(3, 6); // WARBLER

    assertEquals(4, (int) lfoType.getInt(2), "kit lfo2 wave type S_AND_H");
    assertEquals(6, (int) lfoType.getInt(3), "kit lfo3 wave type WARBLER");
  }
}
