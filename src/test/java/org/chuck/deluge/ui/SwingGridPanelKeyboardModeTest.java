package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class SwingGridPanelKeyboardModeTest {

  @Test
  public void testScaleAndRootHelpers() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    ProjectModel project = new ProjectModel();
    project.setKey("C");
    project.setScale("Major");

    SwingGridPanel panel = new SwingGridPanel(vm, bridge);
    panel.setProjectModel(project);

    // C3 is note 48
    assertTrue(panel.isNoteInScale(48), "C3 should be in C Major scale");
    assertTrue(panel.isRootNote(48), "C3 should be the root note of C Major");

    // D3 is note 50
    assertTrue(panel.isNoteInScale(50), "D3 should be in C Major scale");
    assertFalse(panel.isRootNote(50), "D3 should not be the root note of C Major");

    // D#3 is note 51
    assertFalse(panel.isNoteInScale(51), "D#3 should not be in C Major scale");

    vm.shutdown();
  }

  @Test
  public void testKeyboardPlayModeMapping() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    ProjectModel project = new ProjectModel();
    project.setKey("C");
    project.setScale("Major");
    project.addTrack(new SynthTrackModel("Synth"));

    SwingGridPanel panel = new SwingGridPanel(vm, bridge);
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

    vm.shutdown();
  }
}
