package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.JDialog;
import javax.swing.JToggleButton;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

public class SwingTopBarPanelResizeTest {

  @Test
  public void testTabButtonsWidthUnchangedAfterClicks() {
    ProjectModel model = new ProjectModel();
    // Simple top bar listener stub
    SwingTopBarPanel.TopBarListener stubListener =
        new SwingTopBarPanel.TopBarListener() {
          @Override
          public void onLiveRecordToggle(javax.swing.JButton btn) {}

          @Override
          public void onResampleToggle(javax.swing.JButton btn) {}

          @Override
          public void onArrangerCaptureToggle(boolean active) {}

          @Override
          public void onViewModeChanged(String viewMode) {}

          @Override
          public void onAddTrack(String type, boolean isShift) {}

          @Override
          public void onPlayToggle() {}

          @Override
          public void onStop() {}

          @Override
          public void onMasterVolumeChanged(float vol) {}
        };

    SwingTopBarPanel topBar = new SwingTopBarPanel(null, model, new JDialog(), stubListener);

    // Retrieve the clipBtn using reflection or helper fields
    JToggleButton clipBtn = null;
    JToggleButton songBtn = null;
    for (java.awt.Component c : topBar.getComponents()) {
      if (c instanceof JToggleButton tb) {
        if ("CLIP".equals(tb.getText())) {
          clipBtn = tb;
        } else if ("SONG".equals(tb.getText())) {
          songBtn = tb;
        }
      }
    }

    assertNotNull(clipBtn, "CLIP button must exist");
    assertNotNull(songBtn, "SONG button must exist");

    int initialWidth = clipBtn.getPreferredSize().width;
    assertTrue(initialWidth > 0, "Initial width must be positive");

    // Simulate clicking multiple times
    for (int i = 0; i < 10; i++) {
      songBtn.doClick();
      clipBtn.doClick();
    }

    int postClickWidth = clipBtn.getPreferredSize().width;
    assertEquals(
        initialWidth, postClickWidth, "CLIP button preferred width must not expand on click");
  }
}
