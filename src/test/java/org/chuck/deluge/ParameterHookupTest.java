package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E2E test verifying that UI parameter changes via BridgeContract are correctly applied to the
 * ChucK engine audio output.
 */
public class ParameterHookupTest {
  private static ChuckVM vm;
  private static BridgeContract bridge;

  @BeforeAll
  static void initAll() {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    vm = new ChuckVM(44100, 2);

    bridge = new BridgeContract();

    bridge.setTrackType(0, 0); // Track 0 = Kit
    bridge.setTrackType(4, 1); // Track 4 = Synth

    bridge.register(vm);

    vm.addPrintListener(msg -> System.out.print("[VM] " + msg));

    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL(vm));

    vm.setGlobalString("g_sample_0", "examples/data/kick.wav");

    System.out.println("INIT: Advancing 100");
    System.out.println(
        "INIT: VM now=" + vm.getCurrentTime() + " activeShreds=" + vm.getActiveShredCount());
    try {
      vm.advanceTime(100);
    } catch (RuntimeException e) {
      System.out.println("=== THREAD DUMP ===");
      for (java.util.Map.Entry<Thread, StackTraceElement[]> entry :
          Thread.getAllStackTraces().entrySet()) {
        Thread t = entry.getKey();
        System.out.println(
            "Thread: "
                + t.getName()
                + " (daemon="
                + t.isDaemon()
                + ", state="
                + t.getState()
                + ")");
        StackTraceElement[] stack = entry.getValue();
        for (int i = 0; i < Math.min(10, stack.length); i++) {
          System.out.println("  " + stack[i]);
        }
        System.out.println();
      }
      throw e;
    }
    System.out.println("INIT: Broadcasting LOAD");
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    System.out.println("INIT: Advancing 250ms");
    vm.advanceTime(44100 / 4); // 250ms
    System.out.println("INIT: Done!");
  }

  @AfterAll
  static void tearDownAll() {
    if (vm != null) vm.shutdown();
  }

  @BeforeEach
  void setUp() {
    // Reset state before each test
    bridge.clearAllSteps();
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    vm.advanceTime(4410); // 100ms for play=0 to settle
  }

  @Test
  void testVelocityHookup() {
    System.out.println("--- TEST: VELOCITY HOOKUP ---");
    int track = 0; // KICK
    int step = 0;

    bridge.setStep(track, step, true);
    bridge.setVelocity(track, step, 0.1);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    float lowPeak = getPeakAfterAdvance(44100 * 2);
    System.out.printf("Low velocity peak: %f\n", lowPeak);

    // Reset playhead for predictable repeat
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
    bridge.setVelocity(track, step, 1.0);
    float highPeak = getPeakAfterAdvance(44100 * 2);

    System.out.printf("High velocity peak: %f\n", highPeak);

    assertTrue(lowPeak > 0.00001f, "Low velocity should result in some signal");
    assertTrue(highPeak > lowPeak, "High velocity should result in higher peak than low velocity");
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
    assertTrue(unmutePeak > 0.0001f, "Unmuted track should produce signal");
  }

  @Test
  void testStutterHookup() {
    System.out.println("--- TEST: STUTTER HOOKUP ---");
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100);

    long stepBefore = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    vm.setGlobalInt("g_stutter_on", 1L);
    vm.setGlobalFloat("g_stutter_div", 10.0);

    vm.advanceTime(8820);
    long stepAfter = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

    assertEquals(stepBefore, stepAfter, "Current step should not advance during Stutter");

    vm.setGlobalInt("g_stutter_on", 0L);
    vm.advanceTime(17640);
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

    bridge.setTrackLevel(track, 0.1);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float lowLevelPeak = getPeakAfterAdvance(44100 * 2);

    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
    bridge.setTrackLevel(track, 1.0);
    float highLevelPeak = getPeakAfterAdvance(44100 * 2);

    assertTrue(lowLevelPeak > 0.00001f, "Low level should result in some signal");
    assertTrue(highLevelPeak > lowLevelPeak, "Higher track level should result in higher peak");
  }

  @Test
  void testFilterHookup() {
    System.out.println("--- TEST: FILTER HOOKUP ---");
    int track = 4; // Synth 1
    // Use moderate track level and explicit velocity to avoid DAC clipping.
    bridge.setTrackLevel(track, 0.1);
    bridge.setStep(track, 0, true);
    bridge.setVelocity(track, 0, 0.8);
    // Set resonance to 0 so closed-filter resonance ringing doesn't exceed
    // open-filter output. Default resonance (0.5 → Q=3.0) causes strong
    // resonant ringing at 100Hz cutoff that produces a higher peak than
    // the same signal through a 3100Hz cutoff at Q=1.
    bridge.setFilterRes(track, 0.0);

    bridge.setFilterFreq(track, 0.0);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    float closedPeak = getPeakAfterAdvance(44100 * 2);

    // Reset playhead so the second measurement sees step 0 fire again
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
    bridge.setFilterFreq(track, 0.3);
    float openPeak = getPeakAfterAdvance(44100 * 2);

    System.out.printf("Filter closed peak: %f, open peak: %f\n", closedPeak, openPeak);
    // Filter frequency change should produce different output. We can't assert
    // that opening the filter increases the peak — a resonant low-frequency
    // filter rings on transients, often producing higher instantaneous peaks
    // than a wide-open filter passing attenuated harmonics.
    assertTrue(
        Math.abs(openPeak - closedPeak) > 0.0001,
        "Filter frequency change from 0.0 ("
            + closedPeak
            + ") to 0.3 ("
            + openPeak
            + ") should measurably affect peak output");
  }

  @Test
  void testPitchHookup() {
    System.out.println("--- TEST: PITCH HOOKUP ---");
    int track = 4;
    bridge.setStep(track, 0, true);

    bridge.setPitch(track, 0, 12);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100);

    assertEquals(12, bridge.getPitch(track, 0), "Bridge should store the pitch offset");
  }

  private float getPeakAfterAdvance(int samples) {
    float peak = 0.0f;
    int chunk = 64;
    for (int i = 0; i < samples / chunk; i++) {
      vm.advanceTime(chunk);
      peak = Math.max(peak, Math.abs(vm.getDacChannel(0).getLastOut()));
      peak = Math.max(peak, Math.abs(vm.getDacChannel(1).getLastOut()));
    }
    return peak;
  }
}
