package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.ActionEvent;
import javax.swing.JButton;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class SongGridPanelSoloTest {

  @Test
  public void testMultiSoloAndMuteRestoration() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    // Create 3 tracks
    TrackModel track0 = new SynthTrackModel("T0");
    track0.setMuted(false);
    project.addTrack(track0);

    TrackModel track1 = new SynthTrackModel("T1");
    track1.setMuted(true); // initially muted
    project.addTrack(track1);

    TrackModel track2 = new SynthTrackModel("T2");
    track2.setMuted(false);
    project.addTrack(track2);

    SongGridPanel panel = new SongGridPanel(bridge);
    panel.setProjectModel(project);

    // Initial state check
    assertFalse(panel.soloedTracks.contains(0));
    assertFalse(panel.soloedTracks.contains(1));
    assertFalse(panel.soloedTracks.contains(2));

    // Simulate clicking solo on Track 0 (normal click, no modifiers)
    JButton soloBtn0 = getSoloButton(panel, 0);
    assertNotNull(soloBtn0);
    
    ActionEvent normalClick = new ActionEvent(soloBtn0, ActionEvent.ACTION_PERFORMED, "");
    soloBtn0.getActionListeners()[0].actionPerformed(normalClick);

    // Assert only Track 0 is soloed
    assertTrue(panel.soloedTracks.contains(0));
    assertFalse(panel.soloedTracks.contains(1));
    assertFalse(panel.soloedTracks.contains(2));

    // Assert engine mutes are updated: track 0 active, others muted
    assertTrue(bridge.getMute(0) == false); // Track 0 active
    assertTrue(bridge.getMute(8) == true);  // Track 1 voice 8 muted
    assertTrue(bridge.getMute(16) == true); // Track 2 voice 16 muted

    // Simulate clicking solo on Track 2 with SHIFT key held down
    JButton soloBtn2 = getSoloButton(panel, 2);
    assertNotNull(soloBtn2);
    
    ActionEvent shiftClick = new ActionEvent(soloBtn2, ActionEvent.ACTION_PERFORMED, "", ActionEvent.SHIFT_MASK);
    soloBtn2.getActionListeners()[0].actionPerformed(shiftClick);

    // Assert both Track 0 and Track 2 are soloed (multi-solo)
    assertTrue(panel.soloedTracks.contains(0));
    assertFalse(panel.soloedTracks.contains(1));
    assertTrue(panel.soloedTracks.contains(2));

    // Assert engine mutes are updated: tracks 0 and 2 active, track 1 muted
    assertTrue(bridge.getMute(0) == false);
    assertTrue(bridge.getMute(8) == true);
    assertTrue(bridge.getMute(16) == false);

    // Simulate clicking solo on Track 2 again (normal click) to toggle off
    soloBtn2.getActionListeners()[0].actionPerformed(normalClick);

    // Simulate clicking solo on Track 0 normally to remove it as well.
    JButton soloBtn0_new = getSoloButton(panel, 0);
    soloBtn0_new.getActionListeners()[0].actionPerformed(normalClick);

    assertTrue(panel.soloedTracks.isEmpty(), "Soloed tracks set should be empty");

    // Assert mutes are restored to original state (Track 0 unmuted, Track 1 muted, Track 2 unmuted)
    assertTrue(bridge.getMute(0) == false); // T0
    assertTrue(bridge.getMute(8) == true);  // T1
    assertTrue(bridge.getMute(16) == false); // T2

    bridge.shutdown();
  }

  @Test
  public void testCopyPasteClipIntegration() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track0 = new SynthTrackModel("T0");
    org.deluge.model.ClipModel clip0 = new org.deluge.model.ClipModel("CLIP_SRC", 8, 16);
    clip0.setStep(0, 0, org.deluge.model.StepData.of(true, 1.0f, 0.9f, 1.0f, 60));
    track0.addClip(clip0);
    project.addTrack(track0);

    SongGridPanel panel = new SongGridPanel(bridge);
    panel.setProjectModel(project);

    assertNull(panel.getCopiedClip());

    panel.setCopiedClip(clip0);
    assertNotNull(panel.getCopiedClip());
    assertEquals("CLIP_SRC", panel.getCopiedClip().getName());

    int clipCol = 1;
    while (track0.getClips().size() <= clipCol) {
      track0.addClip(new org.deluge.model.ClipModel(
          "CLIP " + (track0.getClips().size() + 1), 8, 16));
    }
    org.deluge.model.ClipModel copied = panel.getCopiedClip();
    org.deluge.model.ClipModel copy = copied.deepCopy("CLIP " + (clipCol + 1));
    track0.getClips().set(clipCol, copy);

    assertEquals(2, track0.getClips().size());
    org.deluge.model.ClipModel pastedClip = track0.getClips().get(1);
    assertEquals("CLIP 2", pastedClip.getName());
    assertTrue(pastedClip.getStep(0, 0).active());
    assertEquals(60, pastedClip.getStep(0, 0).pitch());

    bridge.shutdown();
  }

  @Test
  public void testClipLaunching() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track0 = new SynthTrackModel("T0");
    track0.addClip(new org.deluge.model.ClipModel("C1", 8, 16));
    track0.addClip(new org.deluge.model.ClipModel("C2", 8, 16));
    project.addTrack(track0);

    SongGridPanel panel = new SongGridPanel(bridge);
    panel.setProjectModel(project);

    assertEquals(0, track0.getActiveClipIndex());
    assertEquals(-1, bridge.getLaunchQueue(0));

    JButton clipPad1 = panel.pads[0][1];
    assertNotNull(clipPad1);
    java.awt.event.MouseEvent normalClick = new java.awt.event.MouseEvent(
        clipPad1, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 0, 0, 1, false, java.awt.event.MouseEvent.BUTTON1);
    for (java.awt.event.MouseListener ml : clipPad1.getMouseListeners()) {
      ml.mousePressed(normalClick);
    }

    assertEquals(1, bridge.getLaunchQueue(0));
    assertEquals(0, track0.getActiveClipIndex());

    java.awt.event.MouseEvent shiftClick = new java.awt.event.MouseEvent(
        clipPad1, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), java.awt.event.InputEvent.SHIFT_DOWN_MASK, 0, 0, 1, false, java.awt.event.MouseEvent.BUTTON1);
    for (java.awt.event.MouseListener ml : clipPad1.getMouseListeners()) {
      ml.mousePressed(shiftClick);
    }

    assertEquals(1, track0.getActiveClipIndex());
    assertEquals(1, bridge.getCurrentClip(0));

    bridge.shutdown();
  }

  @Test
  public void testRecordingIndicatorsAndSelection() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track0 = new SynthTrackModel("T0");
    track0.addClip(new org.deluge.model.ClipModel("C1", 8, 16));
    project.addTrack(track0);

    TrackModel track1 = new SynthTrackModel("T1");
    track1.addClip(new org.deluge.model.ClipModel("C2", 8, 16));
    project.addTrack(track1);

    SongGridPanel panel = new SongGridPanel(bridge);
    panel.setProjectModel(project);

    assertFalse(SwingGridPanel.isLiveRecordModeActive);
    assertEquals(0, panel.editedModelTrack);

    SwingGridPanel.isLiveRecordModeActive = true;
    panel.refresh();

    JButton clipPadT1 = panel.pads[0][0]; // Visual row 0 is track 1 (since songRowIndex(0) = 1 in SONG mode)
    assertNotNull(clipPadT1);

    java.awt.event.MouseEvent normalClick = new java.awt.event.MouseEvent(
        clipPadT1, java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 0, 0, 1, false, java.awt.event.MouseEvent.BUTTON1);
    for (java.awt.event.MouseListener ml : clipPadT1.getMouseListeners()) {
      ml.mousePressed(normalClick);
    }

    assertEquals(1, panel.editedModelTrack);

    SwingGridPanel.isLiveRecordModeActive = false;
    bridge.shutdown();
  }

  private JButton getSoloButton(SongGridPanel panel, int trackIdx) {
    int col = panel.columnCount - 1; // Solo column is the last column
    for (int r = 0; r < panel.gridMode.rows; r++) {
      if (panel.songRowIndex(r) == trackIdx) {
        return panel.pads[r][col];
      }
    }
    return null;
  }
}
