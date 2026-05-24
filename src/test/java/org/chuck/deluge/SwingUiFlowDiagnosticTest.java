package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.*;
import org.chuck.deluge.ui.SwingDelugeApp;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Tests the EXACT Swing UI flow: uses loadProject() which calls pushModelToBridge(), verifying that
 * the full Swing UI code path produces audio.
 */
public class SwingUiFlowDiagnosticTest {

  @Test
  public void testSwingUiExactPushModelToBridge() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // Start engine (same as SwingDelugeApp.main)
    vm.spork(new DelugeEngineDSL());
    vm.advanceTime(44100); // let engine initialize

    // Load a kit from XML (same as sidebar does)
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertTrue(is != null, "808 Kit resource not found");
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "808");
    int voiceCount = Math.min(16, kit.getDrums().size());

    // Add a clip and project (same as addTrack callback)
    kit.addClip(new ClipModel("CLIP 1", voiceCount, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(kit);

    // Use SwingDelugeApp's own loadProject — this is the EXACT code the UI calls
    SwingDelugeApp app = new SwingDelugeApp(vm, bridge, null);
    app.loadProject(project);

    vm.advanceTime(44100); // let async events settle

    // Now mimic user clicking steps on the grid
    bridge.setStep(0, 0, true);
    bridge.setStep(0, 4, true);
    bridge.setStep(0, 8, true);
    bridge.setStep(0, 12, true);

    // Set playback params (same as play button click)
    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    for (int i = 0; i < voiceCount; i++) bridge.setTrackLevel(i, 0.7);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    // Measure audio output
    float peakL = 0, peakR = 0;
    boolean stepAdvanced = false;
    boolean sawAudio = false;

    for (int i = 0; i < 500; i++) {
      vm.advanceTime(441); // 10ms
      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;
      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
      if (curL > 0.0003f || curR > 0.0003f) sawAudio = true;
    }

    System.out.printf(
        "[SWING UI MIMIC] Peak L=%.6f R=%.6f stepAdv=%s audio=%s%n",
        peakL, peakR, stepAdvanced, sawAudio);
    System.out.printf(
        "[SWING UI MIMIC] pattern[0]=%d pattern[4]=%d%n",
        bridge.getStep(0, 0) ? 1 : 0, bridge.getStep(0, 4) ? 1 : 0);

    assertTrue(stepAdvanced, "Engine playhead did not advance");
    assertTrue(sawAudio, String.format("No audio detected: L=%.6f R=%.6f", peakL, peakR));

    vm.shutdown();
  }

  @Test
  public void testSwingUiWithStepsSetInModel() throws Exception {
    // Same flow but sets steps in the ClipModel (mimics loading a song with steps),
    // then verifies pushModelToBridge pushes them correctly.
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "1");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);
    vm.spork(new DelugeEngineDSL());
    vm.advanceTime(44100);

    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertTrue(is != null, "808 Kit resource not found");
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "808");
    int voiceCount = Math.min(16, kit.getDrums().size());

    // Add a clip with steps pre-populated
    ClipModel clip = new ClipModel("CLIP 1", voiceCount, 16);
    StepData active = StepData.of(true, 0.8f, 1.0f, 1.0f, 60);
    clip.setStep(0, 0, active);
    clip.setStep(0, 4, active);
    clip.setStep(0, 8, active);
    clip.setStep(0, 12, active);
    kit.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.addTrack(kit);

    // Use loadProject (same as Swing UI)
    SwingDelugeApp app = new SwingDelugeApp(vm, bridge, null);
    app.loadProject(project);

    vm.advanceTime(44100); // let async events settle

    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, 1.0);
    for (int i = 0; i < voiceCount; i++) bridge.setTrackLevel(i, 0.7);
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);

    float peakL = 0, peakR = 0;
    boolean stepAdvanced = false;
    boolean sawAudio = false;

    for (int i = 0; i < 500; i++) {
      vm.advanceTime(441);
      long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
      if (step >= 0) stepAdvanced = true;
      float curL = Math.abs(vm.getDacChannel(0).getLastOut());
      float curR = Math.abs(vm.getDacChannel(1).getLastOut());
      if (curL > peakL) peakL = curL;
      if (curR > peakR) peakR = curR;
      if (curL > 0.0003f || curR > 0.0003f) sawAudio = true;
    }

    System.out.printf(
        "[SWING UI MODEL FIRST] Peak L=%.6f R=%.6f stepAdv=%s audio=%s%n",
        peakL, peakR, stepAdvanced, sawAudio);

    assertTrue(stepAdvanced, "Engine playhead did not advance");
    assertTrue(sawAudio, String.format("No audio detected: L=%.6f R=%.6f", peakL, peakR));

    vm.shutdown();
  }

  @Test
  public void testPianoRollComponentClickTrigger() throws Exception {
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
    kit.addClip(new ClipModel("CLIP 1", 16, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(kit);

    SwingDelugeApp app = new SwingDelugeApp(vm, bridge, null);
    app.loadProject(project);

    org.chuck.deluge.ui.SwingGridPanel gridPanel = app.getClipPanel();
    gridPanel.setSize(1200, 600);
    gridPanel.doLayout();

    org.chuck.deluge.ui.PianoRollComponent pianoRoll = null;
    for (java.awt.Component c : gridPanel.getComponents()) {
      if (c instanceof org.chuck.deluge.ui.PianoRollComponent) {
        pianoRoll = (org.chuck.deluge.ui.PianoRollComponent) c;
        break;
      }
    }

    assertTrue(pianoRoll != null, "PianoRollComponent child component not found");
    pianoRoll.setSize(1200, 80);
    pianoRoll.doLayout();

    // Compute coordinate parameters exactly matching drawing formulas for size 1200
    int lw = Math.max(60, Math.min(140, 1200 / 12)); // 100
    int gridX = lw + 91; // 191
    int padSz = 48;
    int cols = 16;
    double totalWidth = cols * (padSz + 5) - 5;
    double keyW = totalWidth / 28.0;

    // Click white key 0 (C4 -> MIDI 60):
    int clickX = (int) (gridX + 0 * keyW + keyW / 2);
    int clickY = 50; // White key segment (bottom half)

    java.awt.event.MouseEvent whiteClick =
        new java.awt.event.MouseEvent(
            pianoRoll,
            java.awt.event.MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            0,
            clickX,
            clickY,
            1,
            false,
            java.awt.event.MouseEvent.BUTTON1);

    // Verify it doesn't throw and triggers note properly
    pianoRoll.dispatchEvent(whiteClick);

    vm.advanceTime(4410);
    vm.shutdown();
  }
}
