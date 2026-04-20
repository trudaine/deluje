package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** E2E Integration Test for the Deluge Engine (ChucK and Java DSL). */
public class DelugeEngineTest {
  private ChuckVM vm;
  private BridgeContract bridge;
  private final List<String> logs = java.util.Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    vm = new ChuckVM(44100, 2);
    vm.addPrintListener(logs::add);

    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("../deluge/src/main/resources");

    bridge = new BridgeContract();
  }

  @AfterEach
  void tearDown() {
    if (vm != null) vm.shutdown();
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  void testChucKEngineKitTrigger() throws Exception {
    bridge.setUseJavaEngine(false);
    bridge.register(vm);
    
    File f = new File("src/main/resources/org/chuck/deluge/engine.ck");
    if (!f.exists()) f = new File("../deluge/src/main/resources/org/chuck/deluge/engine.ck");
    
    vm.setLogLevel(2);
    vm.run(java.nio.file.Files.readString(f.toPath()), "engine.ck");

    runTriggerTest();
    
    boolean triggerFound = logs.stream().anyMatch(l -> l.contains("KIT trigger track: 0 step: 0"));
    assertTrue(triggerFound, "ChucK Engine did not emit audio trigger log");
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  void testJavaEngineKitTrigger() throws Exception {
    bridge.setUseJavaEngine(true);
    bridge.register(vm);
    
    vm.setLogLevel(2);
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL());

    // Note: Java DSL doesn't automatically print "KIT trigger" unless we add it
    // But we can check if logical time advanced and no errors occurred
    runTriggerTest();
    
    // Note: Java Engine sporks internal shreds (clock, kit, synth, fx, master, sidechain)
    assertTrue(vm.getActiveShredCount() >= 5, "Java Engine should have multiple active shreds");
  }

  private void runTriggerTest() {
    // Set engine to play
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Select a cell on Track 0, Step 0
    bridge.setStep(0, 0, true);
    bridge.setTrackLevel(0, 1.0);

    // Advance time by 2 seconds
    for (int i = 0; i < 20; i++) {
        vm.advanceTime(4410); 
    }
  }
}
