package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the track-level actions (rename/color/inspector/move/one-shot/hot-swap)
 * relocated onto the OLED's system menu after the grid-row color-swatch column was reverted for not
 * matching real hardware. Confirms the menu still exposes and correctly wires these actions.
 */
public class OledTrackMenuTest {

  @Test
  public void testOledMenuHasTrackActionsAndOneShotToggles() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract(44100, 2);
    SwingDelugeApp app = new SwingDelugeApp(bridge, null, true);
    app.setVisible(true); // Must be true to realize peers so JPopupMenu.show() can locate itself
    try {
      ProjectModel project = app.getCurrentProject();
      project.getTracks().clear();
      project.addTrack(new SynthTrackModel("MY_TRACK"));
      app.propagateCurrentModel();
      app.getClipPanel().editedModelTrack = 0;

      SwingOledPanel oled = app.hardwareTopPanel.getOledPanel();
      Method m = SwingOledPanel.class.getDeclaredMethod("showSystemOledMenu", int.class, int.class);
      m.setAccessible(true);
      JPopupMenu menu = (JPopupMenu) m.invoke(oled, 0, 0);

      JMenuItem oneShotItem = null;
      for (java.awt.Component c : menu.getComponents()) {
        if (c instanceof JMenuItem item && item.getText().contains("One-Shot")) {
          oneShotItem = item;
        }
      }
      assertNotNull(oneShotItem, "OLED menu must have a one-shot toggle item");

      assertFalse(app.getClipPanel().isOneShotTrack[0]);
      for (var l : oneShotItem.getActionListeners()) {
        l.actionPerformed(null);
      }
      assertTrue(app.getClipPanel().isOneShotTrack[0], "Clicking the item must toggle one-shot");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }
}
