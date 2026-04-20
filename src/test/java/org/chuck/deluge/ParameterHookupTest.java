package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ParameterHookupTest {

  private ChuckVM vm;
  private BridgeContract bridge;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    vm = new ChuckVM(44100, 2);

    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("../deluge/src/main/resources");

    bridge = new BridgeContract();
    bridge.setUseJavaEngine(true);
    bridge.register(vm);

    vm.spork((Runnable) new org.chuck.deluge.engine.DelugeEngineDSL());

    // Allow settle time
    vm.advanceTime(44100 / 2);
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  @Test
  void testVelocityHookup() {
    System.out.println("--- TEST: VELOCITY HOOKUP ---");
    int track = 4; // SYNTH
    int step = 0;

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);
    
    // 1. Low Velocity (0.1)
    bridge.setStep(track, step, true);
    bridge.setVelocity(track, step, 0.1);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float lowPeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("Low velocity peak: " + lowPeak);
    assertTrue(lowPeak > 0, "Low velocity should result in some signal");

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 2. High Velocity (1.0)
    bridge.setVelocity(track, step, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float highPeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("High velocity peak: " + highPeak);
    assertTrue(highPeak > lowPeak * 1.5, "High velocity should result in significantly higher peak");
  }

  @Test
  void testFilterHookup() {
    System.out.println("--- TEST: FILTER HOOKUP ---");
    int track = 4; // SYNTH
    int step = 0;

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 1. Open Filter
    bridge.setStep(track, step, true);
    bridge.setFilterFreq(track, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float openPeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("Open filter peak: " + openPeak);
    assertTrue(openPeak > 0, "Open filter should produce signal");

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 2. Closed Filter
    bridge.setFilterFreq(track, 0.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float closedPeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("Closed filter peak: " + closedPeak);
    assertTrue(openPeak > closedPeak, "Open filter should produce more signal than closed filter");
  }

  @Test
  void testMuteHookup() {
    System.out.println("--- TEST: MUTE HOOKUP ---");
    int track = 0; // KICK
    int step = 0;
    
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 1. Muted
    bridge.setStep(track, step, true);
    bridge.setMute(track, true);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float mutePeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("Mute peak: " + mutePeak);
    assertTrue(mutePeak < 0.001, "Muted track should be silent");

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 2. Unmuted
    bridge.setMute(track, false);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float unmutePeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("Unmute peak: " + unmutePeak);
    assertTrue(unmutePeak > 0.1, "Unmuted track should produce signal");
  }

  @Test
  void testTrackLevelHookup() {
    System.out.println("--- TEST: TRACK LEVEL HOOKUP ---");
    int track = 0;
    int step = 0;

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 1. Low Level
    bridge.setStep(track, step, true);
    bridge.setTrackLevel(track, 0.1);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float lowPeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("Low level peak: " + lowPeak);

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(1000);

    // 2. High Level
    bridge.setTrackLevel(track, 1.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float highPeak = getPeakAfterAdvance(44100 * 2);
    System.out.println("High level peak: " + highPeak);
    assertTrue(highPeak > lowPeak * 2, "Higher track level should result in significantly higher peak");
  }

  @Test
  void testStutterHookup() {
    System.out.println("--- TEST: STUTTER HOOKUP ---");
    // Verify stutter global is set
    vm.setGlobalInt(BridgeContract.G_STUTTER_ON, 1L);
    assertEquals(1L, vm.getGlobalInt(BridgeContract.G_STUTTER_ON));
    vm.setGlobalInt(BridgeContract.G_STUTTER_ON, 0L);
    assertEquals(0L, vm.getGlobalInt(BridgeContract.G_STUTTER_ON));
  }

  private float getPeakAfterAdvance(int samples) {
    float peak = 0;
    // Reset DAC peak tracking by advancing a tiny bit
    vm.advanceTime(1);
    
    for (int i = 0; i < samples / 64; i++) {
      vm.advanceTime(64);
      peak = Math.max(peak, Math.abs(vm.getDacChannel(0).getLastOut()));
      peak = Math.max(peak, Math.abs(vm.getDacChannel(1).getLastOut()));
    }
    return peak;
  }
}
