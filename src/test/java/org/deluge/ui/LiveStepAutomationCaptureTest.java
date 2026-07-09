package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.BridgeContract;
import org.deluge.model.AutomationParam;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies 100% C++ hardware parity (`// C sound.cpp`) live parameter lock recording onto sequence
 * steps when [RECORD] is active during real-time playback.
 */
public class LiveStepAutomationCaptureTest {

  @Test
  public void testMacroAdjustmentWritesAutomationStepLockWhenRecording() {
    BridgeContract bridge = new BridgeContract();
    ClipGridPanel grid = new ClipGridPanel(bridge);

    ProjectModel project = new ProjectModel();
    org.deluge.model.SynthTrackModel track = new org.deluge.model.SynthTrackModel("Track 1");
    ClipModel clip = new ClipModel("Clip 1", 16, 16);
    track.addClip(clip);
    project.addTrack(track);

    grid.setProjectModel(project);
    SwingGridPanel.isLiveRecordModeActive = true;

    // Simulate engine playing at step 4
    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 4L);

    // Turn FILTER macro slider (col 3) to 0.75
    grid.setMacroValue(3, 0.75, track);

    assertTrue(
        clip.hasAutomation(AutomationParam.A_LPF_FREQ, 4),
        "Step 4 must receive A_LPF_FREQ automation lock during live recording");
    assertEquals(0.75f, clip.getAutomation(AutomationParam.A_LPF_FREQ, 4), 0.001f);
  }

  @Test
  public void testMacroAdjustmentDoesNotWriteAutomationWhenNotRecording() {
    BridgeContract bridge = new BridgeContract();
    ClipGridPanel grid = new ClipGridPanel(bridge);

    ProjectModel project = new ProjectModel();
    org.deluge.model.SynthTrackModel track = new org.deluge.model.SynthTrackModel("Track 1");
    ClipModel clip = new ClipModel("Clip 1", 16, 16);
    track.addClip(clip);
    project.addTrack(track);

    grid.setProjectModel(project);
    SwingGridPanel.isLiveRecordModeActive = false; // NOT recording

    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 2L);

    grid.setMacroValue(3, 0.5, track);

    assertFalse(
        clip.hasAutomation(AutomationParam.A_LPF_FREQ, 2),
        "Must not write step automation when live record mode is inactive");
  }
}
