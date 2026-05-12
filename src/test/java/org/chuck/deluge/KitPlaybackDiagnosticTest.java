package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.*;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Diagnostic test that mimics the exact UI flow for kit playback.
 * Load a kit like the sidebar does, add a clip, push to bridge, set steps, then play.
 */
public class KitPlaybackDiagnosticTest {

  @Test
  public void testKitPlaybackMimicsUiFlow() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "2");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 1. Start engine
    vm.spork(new DelugeEngineDSL());
    vm.advanceTime(44100);

    // 2. Load a kit from classpath (same as sidebar would)
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    if (is == null) {
      is = getClass().getClassLoader().getResourceAsStream("KITS/000 TR-808.XML");
    }
    assertTrue(is != null, "808 Kit resource not found");
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "808");
    int voiceCount = Math.min(16, kit.getDrums().size());

    // 3. Mimic sidebar KIT loading path:
    //    sidebar sets g_sample_0..N, setSamplePath, setMute
    for (int i = 0; i < voiceCount; i++) {
      String path = ((SoundDrum) kit.getDrums().get(i)).getSamplePath();
      vm.setGlobalString("g_sample_" + i, path != null ? path : "");
      bridge.setSamplePath(i, path != null ? path : "");
      bridge.setMute(i, false);
    }

    // 4. Mimic addTrack callback: create clip, add to project, pushModelToBridge
    kit.addClip(new ClipModel("CLIP 1", voiceCount, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(kit);

    // --- pushModelToBridge() equivalent ---
    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackType(i, -1);
      bridge.setTrackLength(i, 16);
    }

    // Set track types for kit voices
    for (int v = 0; v < voiceCount; v++) {
      bridge.setTrackType(v, 0);
    }

    // Set sample paths (reversed like pushModelToBridge does)
    for (int v = 0; v < voiceCount; v++) {
      int engineRow = v;
      String path = v < kit.getDrums().size()
        ? ((SoundDrum) kit.getDrums().get(kit.getDrums().size() - 1 - v)).getSamplePath() : "";
      vm.setGlobalString("g_sample_" + engineRow, path);
      bridge.setSamplePath(engineRow, path);
    }

    // Push clip pattern (empty clip = no steps)
    int activeClipIdx = kit.getActiveClipIndex();
    ClipModel clip = kit.getClips().get(activeClipIdx);
    for (int r = 0; r < clip.getRowCount(); r++) {
      for (int s = 0; s < clip.getStepCount(); s++) {
        StepData step = clip.getStep(r, s);
        if (step != null && step.active() && r < voiceCount) {
          bridge.setStep(r, s, true);
        }
      }
    }

    // Broadcast G_LOAD_TRIGGER (inside pushModelToBridge)
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100 * 2);

    // 5. Now mimic swing UI: user clicks cells on grid
    // Click steps 0, 4, 8, 12 on row 0
    bridge.setStep(0, 0, true);
    bridge.setStep(0, 4, true);
    bridge.setStep(0, 8, true);
    bridge.setStep(0, 12, true);

    // 6. Set G_PLAY = 1 (mimics play button)
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    for (int i = 0; i < voiceCount; i++) bridge.setTrackLevel(i, 0.7);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // 7. Measure audio output
    float peakL = 0;
    float peakR = 0;
    boolean stepAdvanced = false;

    for (int i = 0; i < 500; i++) {
      vm.advanceTime(441); // 10ms

      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;

      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
    }

    System.out.printf("[KIT UI MIMIC] Peak L=%.6f R=%.6f stepAdv=%s%n", peakL, peakR, stepAdvanced);

    // Check pattern values
    System.out.printf("[KIT UI MIMIC] pattern[0*16+0]=%d  pattern[0*16+4]=%d  pattern[0*16+8]=%d  pattern[0*16+12]=%d%n",
      bridge.getStep(0, 0) ? 1 : 0,
      bridge.getStep(0, 4) ? 1 : 0,
      bridge.getStep(0, 8) ? 1 : 0,
      bridge.getStep(0, 12) ? 1 : 0);

    assertTrue(stepAdvanced, "Engine playhead did not advance");
    assertTrue(peakL > 0.0005f || peakR > 0.0005f,
      String.format("Audio too weak: L=%.6f R=%.6f", peakL, peakR));

    vm.shutdown();
  }

  @Test
  public void testSameTrackReTriggerViaPreview() throws Exception {
    // Regression: clicking cell (0,0) then cell (0,4) in the same row must both produce sound.
    // The kit_preview_shred dedup guard (r == currentPreviewTrack && !stopped) was blocking
    // re-triggers on the same track, causing silence on the second click.
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "0");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    vm.spork(new DelugeEngineDSL());
    vm.advanceTime(44100);

    // Load kit
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertTrue(is != null, "808 Kit resource not found");
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "808");
    int voiceCount = Math.min(16, kit.getDrums().size());

    for (int i = 0; i < voiceCount; i++) {
      String path = ((SoundDrum) kit.getDrums().get(i)).getSamplePath() != null
        ? ((SoundDrum) kit.getDrums().get(i)).getSamplePath() : "";
      vm.setGlobalString("g_sample_" + i, path);
      bridge.setSamplePath(i, path);
      bridge.setMute(i, false);
    }

    kit.addClip(new ClipModel("CLIP 1", voiceCount, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(kit);

    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackType(i, -1);
      bridge.setTrackLength(i, 16);
    }
    for (int v = 0; v < voiceCount; v++) bridge.setTrackType(v, 0);
    for (int v = 0; v < voiceCount; v++) {
      int engineRow = v;
      String path = v < kit.getDrums().size()
        ? ((SoundDrum) kit.getDrums().get(kit.getDrums().size() - 1 - v)).getSamplePath() : "";
      vm.setGlobalString("g_sample_" + engineRow, path);
      bridge.setSamplePath(engineRow, path);
    }
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100 * 2);

    // Enable play so DAC is active and we can measure audio
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    for (int i = 0; i < voiceCount; i++) bridge.setTrackLevel(i, 0.7);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // First preview: click cell at row 0, step 0
    vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, 0L);
    vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
    vm.advanceTime(4410); // 100ms — enough for envelope attack + sample start

    float peak1 = 0;
    for (int i = 0; i < 20; i++) {
      vm.advanceTime(441); // 10ms
      float l = Math.abs(vm.getDacChannel(0).getLastOut());
      float r = Math.abs(vm.getDacChannel(1).getLastOut());
      if (l > peak1) peak1 = l;
      if (r > peak1) peak1 = r;
    }
    System.out.printf("[PREVIEW REGRESSION] 1st click: peak=%.6f%n", peak1);
    assertTrue(peak1 > 0.001f, "First preview click produced no audio");

    // Second preview: same track (row 0), click cell at step 4
    vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, 0L);
    vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
    vm.advanceTime(4410); // let previous reverb drain, then measure new trigger

    float peak2 = 0;
    for (int i = 0; i < 20; i++) {
      vm.advanceTime(441);
      float l = Math.abs(vm.getDacChannel(0).getLastOut());
      float r = Math.abs(vm.getDacChannel(1).getLastOut());
      if (l > peak2) peak2 = l;
      if (r > peak2) peak2 = r;
    }
    System.out.printf("[PREVIEW REGRESSION] 2nd click: peak=%.6f (1st=%.6f)%n", peak2, peak1);
    assertTrue(peak2 > 0.001f,
      "Second preview click on same track produced no audio — dedup regression");

    vm.shutdown();
  }

  @Test
  public void testKitPlaybackWithSidebarDoubleBroadcast() throws Exception {
    // Same as above but with an EXTRA G_LOAD_TRIGGER broadcast (mimicking sidebar line 214)
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);
    vm.spork(new DelugeEngineDSL());
    vm.advanceTime(44100);

    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    if (is == null) {
      is = getClass().getClassLoader().getResourceAsStream("KITS/000 TR-808.XML");
    }
    assertTrue(is != null, "808 Kit resource not found");
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "808");
    int voiceCount = Math.min(16, kit.getDrums().size());

    for (int i = 0; i < voiceCount; i++) {
      String path = ((SoundDrum) kit.getDrums().get(i)).getSamplePath();
      vm.setGlobalString("g_sample_" + i, path != null ? path : "");
      bridge.setSamplePath(i, path != null ? path : "");
      bridge.setMute(i, false);
    }

    kit.addClip(new ClipModel("CLIP 1", voiceCount, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(kit);

    bridge.clearPattern();
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      bridge.setMute(i, false);
      bridge.setTrackType(i, -1);
      bridge.setTrackLength(i, 16);
    }
    for (int v = 0; v < voiceCount; v++) {
      bridge.setTrackType(v, 0);
    }
    for (int v = 0; v < voiceCount; v++) {
      int engineRow = v;
      String path = v < kit.getDrums().size()
        ? ((SoundDrum) kit.getDrums().get(kit.getDrums().size() - 1 - v)).getSamplePath() : "";
      vm.setGlobalString("g_sample_" + engineRow, path);
      bridge.setSamplePath(engineRow, path);
    }

    // FIRST broadcast (from pushModelToBridge)
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100);

    // SECOND broadcast (from sidebar line 214)
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100 * 2);

    // Click cells
    bridge.setStep(0, 0, true);
    bridge.setStep(0, 4, true);
    bridge.setStep(0, 8, true);
    bridge.setStep(0, 12, true);

    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    for (int i = 0; i < voiceCount; i++) bridge.setTrackLevel(i, 0.7);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    float peakL = 0, peakR = 0;
    boolean stepAdvanced = false;

    for (int i = 0; i < 500; i++) {
      vm.advanceTime(441);
      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;
      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
    }

    System.out.printf("[KIT DOUBLE BCAST] Peak L=%.6f R=%.6f stepAdv=%s%n", peakL, peakR, stepAdvanced);
    assertTrue(stepAdvanced, "Engine playhead did not advance");
    assertTrue(peakL > 0.0005f || peakR > 0.0005f,
      String.format("Audio too weak: L=%.6f R=%.6f", peakL, peakR));

    vm.shutdown();
  }
}
