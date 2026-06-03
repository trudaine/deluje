package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Robust End-to-End Test for Deluge Audio Output. Focuses on peak-meter analysis to prove actual
 * signal generation.
 */
@Disabled(
    "Legacy DelugeEngineDSL engine is unsupported; rebuild on the firmware pure engine. See docs/java-port-review-non-dx7-2026-06-03.md.")
public class DelugeAudioE2ETest {

  @Test
  public void test808KitProducesSound() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 1. Start Engine
    vm.spork(new DelugeEngineDSL());

    vm.advanceTime(44100);

    // 2. Load 808 Kit
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    if (is == null) {
      is = getClass().getClassLoader().getResourceAsStream("KITS/000 TR-808.XML");
    }
    assertTrue(is != null, "808 Kit resource not found");

    KitTrackModel kit = DelugeXmlParser.parseKit(is, "808");

    for (int i = 0; i < Math.min(8, kit.getDrums().size()); i++) {
      String path = ((SoundDrum) kit.getDrums().get(i)).getSamplePath();
      if (path != null && !path.isEmpty()) {
        vm.setGlobalString("g_sample_" + i, path);
        bridge.setMute(i, false);
      }
    }
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

    vm.advanceTime(44100);

    // 3. Toggle Steps (Track 0, Step 0 - Kick)
    bridge.setStep(0, 0, true);
    bridge.setStep(0, 4, true);
    bridge.setStep(0, 8, true);
    bridge.setStep(0, 12, true);

    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    for (int i = 0; i < 8; i++) bridge.setTrackLevel(i, 0.9);

    // 4. Start Playback
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // 5. High-Resolution Signal Analysis
    float peakL = 0;
    float peakR = 0;
    boolean stepAdvanced = false;

    // Advance 5 seconds, checking EVERY block for peaks
    for (int i = 0; i < 500; i++) { // 500 * 10ms = 5s
      vm.advanceTime(441); // 10ms

      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;

      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());

      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
    }

    System.out.printf(
        "DIAGNOSTIC: Peak L: %.6f, Peak R: %.6f, Step Advanced: %s\n", peakL, peakR, stepAdvanced);

    vm.shutdown();

    assertTrue(stepAdvanced, "Engine playhead did not advance.");
    assertTrue(
        peakL > 0.01f || peakR > 0.01f,
        String.format(
            "Audio signal too weak. Peaks: L=%.6f, R=%.6f. Expecting > 0.01", peakL, peakR));
  }
}
