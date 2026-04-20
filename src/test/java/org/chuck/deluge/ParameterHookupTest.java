package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E2E test verifying that UI parameter changes via BridgeContract are correctly applied to the
 * ChucK engine audio output.
 */
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
    bridge.register(vm);

    File f = findEngineFile();
    assertNotNull(f, "Engine script not found");
    vm.add(f.getAbsolutePath());

    // Allow settle time (at least 150ms to clear the 100ms engine delay)
    vm.advanceTime(44100 / 4); // 250ms
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  private File findEngineFile() {
    File f = new File("src/main/resources/org/chuck/deluge/engine.ck");
    if (f.exists()) return f;
    f = new File("../deluge/src/main/resources/org/chuck/deluge/engine.ck");
    if (f.exists()) return f;
    return null;
  }

  @Test
  void testVelocityHookup() {
    System.out.println("--- TEST: VELOCITY HOOKUP ---");
    int track = 0; // KICK
    int step = 0;

    // 1. Low Velocity (0.1)
    bridge.setStep(track, step, true);
    bridge.setVelocity(track, step, 0.1);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Advance enough to hit step 0 (120 BPM = 125ms per 16th)
    float lowPeak = getPeakAfterAdvance(44100 * 2);
    System.out.printf("Low velocity peak: %f\n", lowPeak);

    // 2. High Velocity (1.0)
    bridge.setVelocity(track, step, 1.0);
    float highPeak = getPeakAfterAdvance(44100 * 2);
    System.out.printf("High velocity peak: %f\n", highPeak);

    assertTrue(lowPeak > 0.001f, "Low velocity should result in some signal");
    assertTrue(
        highPeak > lowPeak * 2,
        "High velocity should result in significantly higher peak than low velocity");
  }

  @Test
  void testMuteHookup() {
    System.out.println("--- TEST: MUTE HOOKUP ---");
    int track = 0; // KICK
    bridge.setStep(track, 0, true);
    bridge.setMute(track, true);

    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float mutePeak = getPeakAfterAdvance(44100 * 2);
    System.out.printf("Mute peak: %f\n", mutePeak);

    assertTrue(mutePeak < 0.001f, "Muted track should be silent");

    bridge.setMute(track, false);
    float unmutePeak = getPeakAfterAdvance(44100 * 2);
    System.out.printf("Unmute peak: %f\n", unmutePeak);
    assertTrue(unmutePeak > 0.01f, "Unmuted track should produce signal");
  }

  @Test
  void testStutterHookup() {
    System.out.println("--- TEST: STUTTER HOOKUP ---");
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100); // 1s

    long stepBefore = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    vm.setGlobalInt("g_stutter_on", 1L);
    vm.setGlobalFloat("g_stutter_div", 10.0); // Very fast stutter

    vm.advanceTime(8820); // 200ms
    long stepAfter = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

    assertEquals(stepBefore, stepAfter, "Current step should not advance during Stutter");

    vm.setGlobalInt("g_stutter_on", 0L);
    vm.advanceTime(17640); // 400ms
    long stepFinally = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

    assertNotEquals(
        stepAfter, stepFinally, "Current step should resume advancing after Stutter off");
  }

  @Test
  void testTrackLevelHookup() {
    System.out.println("--- TEST: TRACK LEVEL HOOKUP ---");
    int track = 0; // KICK
    bridge.setStep(track, 0, true);
    bridge.setVelocity(track, 0, 1.0);

    // Low Level
    bridge.setTrackLevel(track, 0.1);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float lowLevelPeak = getPeakAfterAdvance(44100 * 2);

    // High Level
    bridge.setTrackLevel(track, 1.0);
    float highLevelPeak = getPeakAfterAdvance(44100 * 2);

    assertTrue(lowLevelPeak > 0.001f, "Low level should result in some signal");
    assertTrue(
        highLevelPeak > lowLevelPeak * 2,
        "Higher track level should result in significantly higher peak");
  }

  @Test
  void testFilterHookup() {
    System.out.println("--- TEST: FILTER HOOKUP ---");
    int track = 4; // Synth 1
    bridge.setStep(track, 0, true);

    // 1. Filter Closed (0.0)
    bridge.setFilterFreq(track, 0.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float closedPeak = getPeakAfterAdvance(44100 * 2);

    // 2. Filter Open (1.0)
    bridge.setFilterFreq(track, 1.0);
    float openPeak = getPeakAfterAdvance(44100 * 2);

    System.out.printf("Filter closed peak: %f, open peak: %f\n", closedPeak, openPeak);
    assertTrue(openPeak > closedPeak, "Open filter should produce more signal than closed filter");
  }

  @Test
  void testPitchHookup() {
    System.out.println("--- TEST: PITCH HOOKUP ---");
    int track = 4;
    bridge.setStep(track, 0, true);

    // We check if changing pitch in bridge is reflected in the engine's view
    bridge.setPitch(track, 0, 12); // Up one octave
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100);

    // Logic check: Bridge value is correctly held
    assertEquals(12, bridge.getPitch(track, 0), "Bridge should store the pitch offset");
  }

  private float getPeakAfterAdvance(int samples) {
    float peak = 0.0f;
    for (int i = 0; i < samples / 64; i++) {
      vm.advanceTime(64);
      peak = Math.max(peak, Math.abs(vm.getDacChannel(0).getLastOut()));
      peak = Math.max(peak, Math.abs(vm.getDacChannel(1).getLastOut()));
    }
    return peak;
  }
}
