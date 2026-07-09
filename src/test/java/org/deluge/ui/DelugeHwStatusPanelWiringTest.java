package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for wiring the real-hardware connection status widget (DelugeHwStatusPanel:
 * LED + "DELUGE ON"/"DELUGE OFF") into the main window. It was a fully built, working class --
 * connection detection, click-to-reconnect, right-click card push/pull -- that was simply never
 * constructed anywhere, so it never appeared. Also verifies its click now opens the SD Explorer's
 * Hardware tab.
 */
public class DelugeHwStatusPanelWiringTest {

  private DelugeHwStatusPanel findStatusPanel(Container root) {
    for (Component c : root.getComponents()) {
      if (c instanceof DelugeHwStatusPanel p) return p;
      if (c instanceof Container cont) {
        DelugeHwStatusPanel found = findStatusPanel(cont);
        if (found != null) return found;
      }
    }
    return null;
  }

  @Test
  public void testStatusPanelIsAddedToMainWindow() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    MidiService midiService = new MidiService(bridge, new MidiInputRouter(bridge));
    SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, true);
    try {
      DelugeHwStatusPanel panel = findStatusPanel(app);
      assertNotNull(panel, "DelugeHwStatusPanel must be constructed and added to the main window");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  public void testClickingStatusPanelOpensHardwareExplorer() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    MidiService midiService = new MidiService(bridge, new MidiInputRouter(bridge));
    SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, true);
    try {
      assertFalse(app.getLeftFloat().isVisible(), "SD Explorer should start hidden");

      DelugeHwStatusPanel panel = findStatusPanel(app);
      assertNotNull(panel);
      java.awt.event.MouseEvent click =
          new java.awt.event.MouseEvent(
              panel,
              java.awt.event.MouseEvent.MOUSE_CLICKED,
              System.currentTimeMillis(),
              0,
              5,
              5,
              1,
              false,
              java.awt.event.MouseEvent.BUTTON1);
      for (var listener : panel.getMouseListeners()) {
        listener.mouseClicked(click);
      }

      assertTrue(
          app.getLeftFloat().isVisible(),
          "Clicking the connection status widget must open the SD Explorer");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }
}
