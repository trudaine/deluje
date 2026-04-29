package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** E2E Integration Test for the new unified DelugeEngineDSL. */
public class DelugeEngineDSLTest {
  private ChuckVM vm;
  private BridgeContract bridge;
  private final List<String> logs = java.util.Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("deluge.tracks", "8");
    vm = new ChuckVM(44100, 2);
    vm.addPrintListener(logs::add);

    // Setup search paths for samples
    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("../deluge/src/main/resources");

    bridge = new BridgeContract();
    bridge.register(vm);
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  @Test
  void testEngineDSLStepAdvancement() throws Exception {
    // Spork the new unified Java DSL engine
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());

    // Initialize track types to unblock shreds
    bridge.setTrackType(0, 0); // Kit
    bridge.setTrackType(4, 1); // Synth
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

    // Set engine to play
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // Initial step should be -1 or 0
    long step0 = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    
    // Advance time by one 16th note at 120 BPM
    // 120 BPM = 2 beats/sec. 1 beat = 0.5s. 16th note = 0.125s.
    vm.advanceTime( (int)(44100 * 0.13) ); 

    long step1 = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    assertTrue(step1 >= 0, "Engine did not advance from initial state");
    
    vm.advanceTime( (int)(44100 * 0.2) );
    long step2 = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    assertTrue(step2 > step1, "Engine step did not continue to advance. Step1: " + step1 + " Step2: " + step2);
  }

  @Test
  void testKitVoiceInitialization() throws Exception {
    // Set track 0 as Kit
    bridge.setTrackType(0, 0);
    vm.setGlobalString("g_sample_0", "examples/data/kick.wav");

    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    
    // Trigger load event to break the init loop in DelugeEngineDSL
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    
    vm.advanceTime(100); // Give it a moment to init voices

    // Verify playhead starts moving when play is set
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100);
    
    long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    assertTrue(step >= 0, "Engine failed to start after kit initialization");
  }

  @Test
  void testStutterMode() throws Exception {
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // Enable stutter
    vm.setGlobalInt(BridgeContract.G_STUTTER_ON, 1L);
    vm.setGlobalFloat(BridgeContract.G_STUTTER_DIV, 4.0);

    long startStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    vm.advanceTime(44100);
    
    // In stutter mode, the step doesn't advance in clock_shred, it just broadcasts ticks
    // (Based on the logic: if stutter_on != 0, it doesn't increment step++)
    long endStep = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    assertEquals(startStep, endStep, "Step should not advance while stutter is active");
  }
}
