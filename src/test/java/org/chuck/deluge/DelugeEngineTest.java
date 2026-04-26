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
    System.setProperty("deluge.tracks", "8");
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
    org.chuck.deluge.engine.DelugeEngine engine =
        new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);

    // Set engine to play
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Give engine time to start and initialize tracks (staggered by 5-10ms)
    vm.advanceTime(44100); // 1 second in virtual time

    // Select a cell on Track 0, Step 0
    bridge.setStep(0, 0, true);
    vm.setGlobalString("g_sample_0", "examples/data/kick.wav");
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

    // Advance time by 10 seconds to allow the engine to process
    vm.advanceTime(44100 * 10);

    // Give virtual threads a brief moment to finish logging to the list
    Thread.sleep(100);

    // Verify trigger in logs
    boolean triggerFound = logs.stream().anyMatch(l -> l.contains("KIT trigger track: 0 step: 0"));
    assertTrue(triggerFound, "Engine did not emit audio trigger log for cell selection");
  }

  @Test
  void testTiedNotes() throws Exception {
    vm.setLogLevel(2);

    org.chuck.deluge.engine.DelugeEngine engine =
        new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);

    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.advanceTime(44100); // 1 second

    // Set a note with gate 2.5
    bridge.setTrackType(4, 1); // Synth track
    bridge.setStep(4, 0, true);
    bridge.setGate(4, 0, 2.5);
    bridge.setPitch(4, 0, 0);

    vm.advanceTime(44100 * 5); // Advance 5 seconds
    Thread.sleep(100);

    // Verify logs
    boolean startFound =
        logs.stream().anyMatch(l -> l.contains("SYNTH trigger track: 4 step: 0 gate: 2.5"));
    boolean endFound = logs.stream().anyMatch(l -> l.contains("SYNTH note end track: 4"));

    assertTrue(startFound, "Engine did not start tied note");
    assertTrue(endFound, "Engine did not end tied note");
  }

  @Test
  void testHidInput() throws Exception {
    vm.setLogLevel(2);

    org.chuck.hid.Hid hid = new org.chuck.hid.Hid();
    hid.openKeyboard(0, vm);

    // Simulate key press via VM
    org.chuck.hid.HidMsg msg = new org.chuck.hid.HidMsg();
    msg.deviceType = "keyboard";
    msg.type = org.chuck.hid.HidMsg.BUTTON_DOWN;
    msg.which = 65; // 'A'

    vm.dispatchHidMsg(msg);

    // Verify that the Hid object received it
    org.chuck.hid.HidMsg out = new org.chuck.hid.HidMsg();
    boolean received = hid.recv(out);

    assertTrue(received, "Hid did not receive dispatched message");
    assertEquals(65, out.which);
  }
}
