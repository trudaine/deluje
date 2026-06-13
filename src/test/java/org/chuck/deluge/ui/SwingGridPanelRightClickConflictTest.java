package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.MouseListener;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class SwingGridPanelRightClickConflictTest {

  @Test
  public void testSongViewCellMouseListenerCount() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    // 1. Setup VM and Bridge Contract
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 2. Setup Project Model with 1 track
    ProjectModel model = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth Track");
    model.addTrack(track);

    // 3. Setup Grid Panel in SONG view
    SwingGridPanel gridPanel = new SwingGridPanel(vm, bridge);
    gridPanel.setProjectModel(model);
    gridPanel.setViewMode(SwingGridPanel.GridViewMode.SONG);
    gridPanel.refresh();

    // 4. Retrieve cell button (Row: 0, Col: 0)
    javax.swing.JButton cellBtn = gridPanel.getPads()[0][0];
    assertNotNull(cellBtn);

    // 5. Assert the number of mouse listeners
    // It should have exactly 1 mouse listener handling clip selection & context menu,
    // and no duplicate conflicting listeners.
    MouseListener[] listeners = cellBtn.getMouseListeners();
    int customListenersCount = 0;
    for (MouseListener l : listeners) {
      String className = l.getClass().getName();
      // Skip standard Swing look-and-feel/tooltips mouse listeners
      if (className.contains("org.chuck.deluge.ui")) {
        customListenersCount++;
      }
    }
    assertEquals(
        1,
        customListenersCount,
        "Song cell button must have exactly 1 custom mouse listener to avoid right-click conflicts");

    vm.shutdown();
  }
}
