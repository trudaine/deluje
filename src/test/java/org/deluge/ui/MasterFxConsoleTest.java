package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JSlider;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MasterFxConsoleTest {

  private BridgeContract bridge;
  private ProjectModel projectModel;
  private SwingDelugeApp app;
  private SwingMasterFxDialog dialog;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();
    projectModel = new ProjectModel();

    // Mock SwingDelugeApp with headless flag
    org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);
    org.deluge.midi.MidiService midiService = new org.deluge.midi.MidiService(bridge, router);
    app = new SwingDelugeApp(bridge, midiService, true);

    dialog = new SwingMasterFxDialog(null, projectModel, bridge, app);
  }

  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    if (app != null) {
      app.dispose();
    }
    if (bridge != null) {
      bridge.shutdown();
    }
  }

  @Test
  void testReverbRoomSizeSync() {
    // 1. Find the Room Size slider in the dialog
    JSlider roomSizeSlider = findSlider(dialog.getContentPane(), "Room Size:");
    assertNotNull(roomSizeSlider, "Should find Reverb Room Size slider");

    // 2. Simulate user sliding to 75%
    roomSizeSlider.setValue(75);

    // 3. Assert high-level model field is updated
    assertEquals(0.75f, projectModel.getReverbRoomSize());

    // 4. Assert low-level bridge parameter is updated live
    assertEquals(0.75f, bridge.getGlobalFloat(BridgeContract.G_REVERB_ROOM), 0.01f);
  }

  @Test
  void testDelayFeedbackSync() {
    // 1. Find the Delay Feedback slider in the dialog
    JSlider feedbackSlider = findSlider(dialog.getContentPane(), "Delay Feedback:");
    assertNotNull(feedbackSlider, "Should find Delay Feedback slider");

    // 2. Simulate user sliding to 60%
    feedbackSlider.setValue(60);

    // 3. Assert high-level model field is updated
    assertEquals(0.60f, projectModel.getMasterDelay());

    // 4. Assert low-level bridge parameter is updated live
    assertEquals(0.60f, bridge.getGlobalFloat(BridgeContract.G_DELAY_FB), 0.01f);
  }

  @Test
  void testMasterSaturationToggle() {
    // 1. Find the Saturation checkbox in the dialog
    JCheckBox satCheck = findCheckBox(dialog.getContentPane(), "Saturation:");
    assertNotNull(satCheck, "Should find Saturation checkbox");

    // 2. Simulate user toggling it active
    satCheck.setSelected(true);
    // Manually trigger action listeners as setSelected doesn't fire them programmatically
    for (java.awt.event.ActionListener al : satCheck.getActionListeners()) {
      al.actionPerformed(new java.awt.event.ActionEvent(satCheck, 0, ""));
    }

    // 3. Assert low-level bridge Master Saturation is updated live
    assertEquals(1.0f, bridge.getGlobalFloat(BridgeContract.G_MASTER_SATURATION), 0.01f);
    assertTrue(org.deluge.project.PreferencesManager.isMasterSaturationEnabled());
  }

  // ── Component Search Helpers ──
  private JSlider findSlider(java.awt.Container container, String labelText) {
    java.awt.Component[] comps = container.getComponents();
    for (int i = 0; i < comps.length; i++) {
      if (comps[i] instanceof JLabel label && labelText.equals(label.getText())) {
        if (i + 1 < comps.length && comps[i + 1] instanceof java.awt.Container sliderPanel) {
          return findJSlider(sliderPanel);
        }
      }
      if (comps[i] instanceof java.awt.Container sub) {
        JSlider s = findSlider(sub, labelText);
        if (s != null) return s;
      }
    }
    return null;
  }

  private JSlider findJSlider(java.awt.Container container) {
    if (container instanceof JSlider slider) return slider;
    for (java.awt.Component comp : container.getComponents()) {
      if (comp instanceof JSlider slider) return slider;
      if (comp instanceof java.awt.Container sub) {
        JSlider s = findJSlider(sub);
        if (s != null) return s;
      }
    }
    return null;
  }

  private JCheckBox findCheckBox(java.awt.Container container, String labelText) {
    java.awt.Component[] comps = container.getComponents();
    for (int i = 0; i < comps.length; i++) {
      if (comps[i] instanceof JLabel label && labelText.equals(label.getText())) {
        if (i + 1 < comps.length && comps[i + 1] instanceof JCheckBox cb) {
          return cb;
        }
      }
      if (comps[i] instanceof java.awt.Container sub) {
        JCheckBox cb = findCheckBox(sub, labelText);
        if (cb != null) return cb;
      }
    }
    return null;
  }
}
