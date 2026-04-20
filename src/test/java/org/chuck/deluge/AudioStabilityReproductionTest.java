package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.chuck.audio.util.DacChannel;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AudioStabilityReproductionTest {
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

    // Activate the diagnostic system I just built
    DacChannel.DEBUG_AUDIO = true;
  }

  @AfterEach
  void tearDown() {
    DacChannel.DEBUG_AUDIO = false;
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
  void reproduceOscillation() throws Exception {
    File f = findEngineFile();
    assertNotNull(f, "Engine script not found");

    int id = vm.add(f.getAbsolutePath());
    assertTrue(id >= 0);

    // Explicitly reset DAC channels to clear any startup noise
    for (int i = 0; i < 2; i++) {
      vm.getDacChannel(i).reset();
    }

    System.out.println("--- REPRODUCTION TEST: STARTING TRANSPORT ---");
    // Set engine to play
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Wait for gates to settle in virtual time
    for (int i = 0; i < 20; i++) {
      vm.advanceTime(64); // Advance time by one block
    }

    // Advance time by 0.5 seconds.
    // If the bug exists, DacChannel will print "Signal detected" to System.out
    vm.advanceTime(44100 / 2);

    float lastL = vm.getDacChannel(0).getLastOut();
    float lastR = vm.getDacChannel(1).getLastOut();

    System.out.printf("Final DAC Values: L=%f, R=%f\n", lastL, lastR);

    // We expect near-silence if fixed
    assertTrue(Math.abs(lastL) < 0.001f, "DAC L should be silent but was " + lastL);
    assertTrue(Math.abs(lastR) < 0.001f, "DAC R should be silent but was " + lastR);
  }
}
