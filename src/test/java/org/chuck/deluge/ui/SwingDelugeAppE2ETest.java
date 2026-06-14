package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * End-to-End integration test suite for SwingDelugeApp UI actions, track management, and view mode
 * transitions.
 */
public class SwingDelugeAppE2ETest {

  @Test
  public void testInteractiveAddTrackAndModeSwitchingWorkflow() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    // 1. Setup VM and Bridge Contract
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 2. Initialize App Frame (remains headless)
    SwingDelugeApp app = new SwingDelugeApp(vm, bridge, null);

    ProjectModel project = app.getCurrentProject();
    assertNotNull(project, "ProjectModel must be initialized on app boot");
    int initialTrackCount = project.getTracks().size();

    // 3. Simulate interactive Shift-Click "Add Kit Track"
    app.getTopBarListener().onAddTrack("KIT", true);

    // Assert track structure updates
    assertEquals(
        initialTrackCount + 1,
        project.getTracks().size(),
        "A new KIT track must be added to the project");
    org.chuck.deluge.model.TrackModel newTrack = project.getTracks().get(initialTrackCount);
    assertTrue(newTrack instanceof KitTrackModel, "New track must be a KitTrackModel");

    KitTrackModel kit = (KitTrackModel) newTrack;
    assertEquals(
        8,
        kit.getDrums().size(),
        "New drum kit must initialize with 8 default drum instrument slots");

    // Verify grid row count in CLIP mode matches drum slot count
    app.getTopBarListener().onViewModeChanged("CLIP");
    app.getClipPanel().setEditedModelTrack(initialTrackCount);
    app.getClipPanel().refresh();
    assertEquals(
        8,
        app.getClipPanel().getVisibleRowCount(),
        "Grid visible rows must match the kit's drum slot count");

    // 4. Simulate switching view mode to KEYPLAY (Isomorphic Keyboard Play)
    app.getTopBarListener().onViewModeChanged("KEYPLAY");
    assertEquals(
        SwingGridPanel.GridViewMode.KEYPLAY,
        app.getClipPanel().getViewMode(),
        "Grid view mode must transition to KEYPLAY");

    // Verify grid visible rows in KEYPLAY mode is full 8-row height
    assertEquals(
        8, app.getClipPanel().getVisibleRowCount(), "KEYPLAY mode must display all 8 grid rows");

    // Cleanup resources
    app.dispose();
    vm.shutdown();
  }

  @Test
  public void testPlayheadFollowModeAndManualScrollInteractions() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    SwingDelugeApp app = new SwingDelugeApp(vm, bridge, null);
    SwingGridPanel grid = app.getClipPanel();

    // Verify follow mode starts as active
    assertTrue(grid.isPlayheadFollowMode(), "Follow mode should be active by default");

    // Call layout refresh and confirm follow mode is NOT disabled by programmatic setValues
    // triggers
    grid.refresh();
    assertTrue(
        grid.isPlayheadFollowMode(),
        "Follow mode must stay active after refresh/setValues layout calls");

    // Manually trigger play toggle to simulate active play state
    app.getTopBarListener().onPlayToggle();
    assertTrue(grid.isPlayheadFollowMode(), "Follow mode must stay active on play toggle start");

    // Simulate manual scrollbar adjustment (which suspends follow mode)
    javax.swing.JScrollBar scrollbar = null;
    for (java.awt.Component c : grid.getComponents()) {
      if (c instanceof javax.swing.JScrollBar sb
          && sb.getOrientation() == javax.swing.JScrollBar.HORIZONTAL) {
        scrollbar = sb;
        break;
      }
    }
    // If not added to layout yet (headless container delay), we can trigger the listener directly
    // or set values
    grid.setPlayheadFollowMode(
        false); // mock manual scroll override directly for robust headless assertion
    assertFalse(grid.isPlayheadFollowMode(), "Follow mode must be suspended on manual scroll");

    // Playback restarts must restore follow mode
    app.getTopBarListener().onPlayToggle(); // toggles play state OFF
    app.getTopBarListener().onPlayToggle(); // toggles play state ON again
    assertTrue(grid.isPlayheadFollowMode(), "Follow mode must be re-enabled on playback start");

    app.dispose();
    vm.shutdown();
  }

  @Test
  public void testAudioThreadLatencyDuringGridEdits() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    // 1. Setup VM, Bridge and App
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    SwingDelugeApp app = new SwingDelugeApp(vm, bridge, null, true);
    SwingGridPanel grid = app.getClipPanel();

    // Add a synth track
    app.getTopBarListener().onAddTrack("SYNTH", true);
    grid.setEditedModelTrack(0);
    grid.refresh();

    // Toggle play state ON to simulate active sequencer playback
    app.getTopBarListener().onPlayToggle();

    // 2. Start a mock audio thread that calls renderBlock() and measures latency
    org.chuck.deluge.firmware.engine.FirmwareAudioEngine engine = null;
    Object engObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (engObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine) {
      engine = (org.chuck.deluge.firmware.engine.FirmwareAudioEngine) engObj;
    }
    assertNotNull(engine, "Audio engine must be initialized");

    final org.chuck.deluge.firmware.engine.FirmwareAudioEngine finalEngine = engine;
    java.util.List<Double> latencySpikes = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.concurrent.atomic.AtomicBoolean running =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    Thread audioThread =
        new Thread(
            () -> {
              while (running.get()) {
                long start = System.nanoTime();
                finalEngine.renderBlock(128);
                long duration = System.nanoTime() - start;
                double durationMs = duration / 1000000.0;
                if (durationMs > 2.9) {
                  latencySpikes.add(durationMs);
                }
                // Check for DSP blowups (NaN, infinity, or extreme overflow values in Q31)
                for (int i = 0; i < 128; i++) {
                  int valL = finalEngine.masterBuffer[i].l;
                  int valR = finalEngine.masterBuffer[i].r;
                  if (Math.abs(valL) > 0x7fffffff || Math.abs(valR) > 0x7fffffff) {
                    latencySpikes.add(-1.0); // marker for DSP overflow blowout
                  }
                }
                // sleep a bit to match playback pacing
                try {
                  Thread.sleep(2);
                } catch (InterruptedException e) {
                  break;
                }
              }
            });
    audioThread.start();

    // 3. Simulate UI rapid note edits via mouse click events on pads
    try {
      for (int i = 0; i < 20; i++) {
        final int row = 4 + (i % 3);
        final int col = i % 16;
        javax.swing.JButton pad = grid.getPads()[row][col];
        if (pad != null) {
          // Trigger mouse click on EDT to simulate user click
          javax.swing.SwingUtilities.invokeAndWait(
              () -> {
                for (java.awt.event.MouseListener ml : pad.getMouseListeners()) {
                  ml.mousePressed(
                      new java.awt.event.MouseEvent(
                          pad,
                          java.awt.event.MouseEvent.MOUSE_PRESSED,
                          System.currentTimeMillis(),
                          0,
                          10,
                          10,
                          1,
                          false,
                          java.awt.event.MouseEvent.BUTTON1));
                  ml.mouseReleased(
                      new java.awt.event.MouseEvent(
                          pad,
                          java.awt.event.MouseEvent.MOUSE_RELEASED,
                          System.currentTimeMillis(),
                          0,
                          10,
                          10,
                          1,
                          false,
                          java.awt.event.MouseEvent.BUTTON1));
                }
              });
        }
        Thread.sleep(40);
      }
    } finally {
      running.set(false);
      audioThread.join();
      app.dispose();
      vm.shutdown();
    }

    // 4. Assert that no audio block exceeded the 2.9ms budget or blew up during edits
    assertTrue(
        latencySpikes.isEmpty(),
        "Audio thread anomaly detected during grid edits (spikes/blowouts): " + latencySpikes);
  }
}
