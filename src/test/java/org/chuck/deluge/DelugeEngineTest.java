package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** E2E Integration Test for the Deluge Engine. */
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
    bridge.register(vm);
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
  void testEngineKitTriggerOnCellSelection() throws Exception {
    vm.setLogLevel(2);
    
    // Spork Java DSL Engine
    org.chuck.deluge.engine.DelugeEngine engine = new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);

    // Set engine to play
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Select a cell on Track 0, Step 0
    bridge.setStep(0, 0, true);

    // Advance time by 2 seconds to allow the engine to process
    vm.advanceTime(44100 * 2);

    // Verify trigger in logs (wait for spork to execute)
    boolean triggerFound = logs.stream().anyMatch(l -> l.contains("KIT trigger track: 0 step: 0"));
    assertTrue(triggerFound, "Engine did not emit audio trigger log for cell selection");
  }
}
