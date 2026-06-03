package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.chuck.audio.util.DacChannel;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Disabled;

/**
 * Verifies the engine is silent when PLAY=1 but no pattern cells are active. Reproduces the
 * "continuous sound on empty pattern" bug.
 */
@Disabled(
    "Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class SilenceWithNoCellsTest {

  private static ChuckVM vm;
  private static BridgeContract bridge;

  @BeforeAll
  static void setUpAll() {
    System.setProperty("chuck.audio.dummy", "true");
    vm = new ChuckVM(44100, 2);
    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("../deluge/src/main/resources");
    bridge = new BridgeContract();

    // Ensure at least one kit and synth track exist to prevent init hangs in DSL
    bridge.setTrackType(0, 0);
    bridge.setTrackType(4, 1);

    bridge.register(vm);

    // Spork Java DSL Engine
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL(vm));

    // Allow shreds to initialize
    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

    // Let engine initialise (300ms)
    float[][] buf = new float[2][44100 / 4 * 3];
    vm.advanceTime(buf, 0, 44100 / 4 * 3);
  }

  @AfterAll
  static void tearDownAll() {
    DacChannel.DEBUG_AUDIO = false;
    if (vm != null) vm.shutdown();
  }

  @BeforeEach
  void setUp() {
    bridge.clearAllSteps();
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    float[][] buf = new float[2][44100 / 10];
    vm.advanceTime(buf, 0, 44100 / 10);
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  void classicEngine_shouldBeSilentWithNoCells() throws Exception {
    // Enable tracing to stdout
    DacChannel.DEBUG_AUDIO = true;

    // Press PLAY – no cells set
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Run for ~1 second and collect DAC output
    float[][] dacOut = new float[2][44100];
    vm.advanceTime(dacOut, 0, 44100);

    DacChannel.DEBUG_AUDIO = false;

    double rms = rms(dacOut);
    System.out.printf("[TEST] RMS with no cells active: %.8f%n", rms);

    assertEquals(
        0.0, rms, 1e-5, "Engine should produce zero audio when no pattern cells are active.");
  }

  private double rms(float[][] buf) {
    double sum = 0;
    for (float[] ch : buf) for (float s : ch) sum += (double) s * s;
    return Math.sqrt(sum / (buf.length * buf[0].length));
  }
}
