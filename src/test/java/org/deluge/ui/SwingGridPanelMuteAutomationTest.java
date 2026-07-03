package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class SwingGridPanelMuteAutomationTest {

  @Test
  public void testRowMuteToggleAction() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    // 1. Setup VM and Bridge Contract
    BridgeContract bridge = new BridgeContract();

    // 2. Setup Project Model with 1 track
    ProjectModel model = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth Track");
    model.addTrack(track);

    // 3. Setup Grid Panel in SONG view
    SwingGridPanel gridPanel = new SongGridPanel(bridge);
    gridPanel.setProjectModel(model);
    gridPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    gridPanel.refresh();

    // 4. Retrieve the Mute Button (Column: columnCount - 2, Row: 0)
    int muteCol = gridPanel.columnCount - 2;
    javax.swing.JButton muteBtn = gridPanel.getPads()[0][muteCol];
    assertNotNull(muteBtn, "Mute button should be initialized");

    // Assert initial state: unmuted
    assertFalse(bridge.getMute(0), "Track must start unmuted");

    // 5. Simulate standard left-click action (intuitive, standard behavior)
    java.awt.event.ActionEvent normalClickEvent =
        new java.awt.event.ActionEvent(
            muteBtn,
            java.awt.event.ActionEvent.ACTION_PERFORMED,
            "",
            System.currentTimeMillis(),
            0);
    for (java.awt.event.ActionListener al : muteBtn.getActionListeners()) {
      al.actionPerformed(normalClickEvent);
    }

    // 6. Assert muting was toggled in the bridge contract & local VM global
    assertTrue(bridge.getMute(0), "Track must be muted in the bridge");
    assertEquals(1L, bridge.getGlobalInt("g_mute_0"), "VM global g_mute_0 must reflect mute state");

    // 7. Verify UI component color matching
    if (muteBtn instanceof DelugePadButton pad) {
      assertEquals(
          new java.awt.Color(0xff, 0xd7, 0x00),
          pad.getBaseColor(),
          "Pad must light up yellow on mute");
    }

    // 8. Simulate second click to unmute
    for (java.awt.event.ActionListener al : muteBtn.getActionListeners()) {
      al.actionPerformed(normalClickEvent);
    }
    assertFalse(bridge.getMute(0), "Track must be unmuted after second click");

    bridge.shutdown();
  }
}
