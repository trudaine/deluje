package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class SwingGridPanelKeyboardModeTest {

  @Test
  public void testScaleAndRootHelpers() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    ProjectModel project = new ProjectModel();
    project.setKey("C");
    project.setScale("Major");
    project.addTrack(new SynthTrackModel("Synth"));

    SwingGridPanel panel = new SwingGridPanel(bridge);
    panel.setProjectModel(project);
    panel.setViewMode(SwingGridPanel.GridViewMode.KEYPLAY);
    panel.refresh();

    // Bottom-left pad is col 0, row 7 (index 7 if 8 voice rows).
    // Mirrors DelugeFirmware isomorphic default: noteFromCoords(0,0) = scrollOffset(50) = D3.
    int expectedNote = 50; // D3 (hardware default isomorphic bottom-left)
    // Simulate press on bottom-left pad
    javax.swing.JButton botLeft = panel.getPads()[7][0];
    assertNotNull(botLeft, "Pads should be populated");

    // Bottom-left is D3 in C Major: in-scale but not the root.
    assertTrue(panel.isNoteInScale(expectedNote), "D3 is in C Major");
    assertFalse(panel.isRootNote(expectedNote), "D3 is not the root of C Major");

    bridge.shutdown();
  }
}
