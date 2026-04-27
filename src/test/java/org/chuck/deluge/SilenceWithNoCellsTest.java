package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.TimeUnit;
import org.chuck.audio.util.DacChannel;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.junit.jupiter.api.*;

/**
 * Verifies the engine is silent when PLAY=1 but no pattern cells are active. Reproduces the
 * "continuous sound on empty pattern" bug.
 */
public class SilenceWithNoCellsTest {

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
  }

  @AfterEach
  void tearDown() {
    DacChannel.DEBUG_AUDIO = false;
    if (vm != null) vm.shutdown();
  }

  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS)
  void classicEngine_shouldBeSilentWithNoCells() throws Exception {
    File f = new File("src/main/resources/org/chuck/deluge/engine.ck");
    if (!f.exists()) f = new File("../deluge/src/main/resources/org/chuck/deluge/engine.ck");
    vm.run(java.nio.file.Files.readString(f.toPath()), "engine.ck");

    // Let engine initialise (300ms)
    advanceSamples(44100 / 4 * 3);

    // Diagnostic: print g_synth_bus sources
    Object synthBusObj = vm.getGlobalObject("g_synth_bus");
    System.out.printf(
        "[DIAG] g_synth_bus = %s%n",
        synthBusObj == null ? "null" : synthBusObj.getClass().getSimpleName());
    if (synthBusObj instanceof org.chuck.audio.ChuckUGen synthBus) {
      java.util.List<org.chuck.audio.ChuckUGen> synSrcs = synthBus.getSources();
      System.out.printf("[DIAG] g_synth_bus.getSources().size() = %d%n", synSrcs.size());
      for (int di = 0; di < synSrcs.size(); di++) {
        org.chuck.audio.ChuckUGen s = synSrcs.get(di);
        System.out.printf(
            "[DIAG]   src[%d] = %s%n", di, s == null ? "null" : s.getClass().getSimpleName());
        if (s != null) {
          for (org.chuck.audio.ChuckUGen ss : s.getSources()) {
            System.out.printf(
                "[DIAG]     -> %s%n", ss == null ? "null" : ss.getClass().getSimpleName());
          }
        }
      }
    }

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
        0.0,
        rms,
        1e-6,
        "Engine should produce zero audio when no pattern cells are active. "
            + "Non-zero RMS="
            + rms
            + " — check DEBUG_AUDIO output above for the source.");
  }

  private void advanceSamples(int n) {
    float[][] buf = new float[2][n];
    vm.advanceTime(buf, 0, n);
  }

  private double rms(float[][] buf) {
    double sum = 0;
    for (float[] ch : buf) for (float s : ch) sum += (double) s * s;
    return Math.sqrt(sum / (buf.length * buf[0].length));
  }
}
