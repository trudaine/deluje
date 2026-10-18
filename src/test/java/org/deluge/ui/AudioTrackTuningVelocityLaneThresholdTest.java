package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link SwingAudioTrackPanel}, {@link SwingTuningDialog}, {@link
 * SwingVelocityLanePanel}, and {@link ThresholdRecordDialog}.
 */
class AudioTrackTuningVelocityLaneThresholdTest {

  @Test
  void testAudioTrackPanelPreservesPlayRateOnLoadAndHandlesNullBridge() throws Exception {
    AudioTrackModel track = new AudioTrackModel("VOX");
    track.setPlayRate(0.10f); // below slider min (0.25x)

    SwingUtilities.invokeAndWait(
        () -> {
          SwingAudioTrackPanel panel = new SwingAudioTrackPanel(null);
          panel.setAudioTrack(0, track);
          assertEquals(
              0.10f,
              track.getPlayRate(),
              1e-5f,
              "setAudioTrack must not overwrite model playRate via slider listener");
        });
  }

  @Test
  void testTuningDialogRatioParserAndOutOfRangeClamping() throws Exception {
    assertEquals(1.5, SwingTuningDialog.parseRatioString("3/2"), 1e-6);
    assertEquals(1.25, SwingTuningDialog.parseRatioString(" 5 / 4 "), 1e-6);
    assertEquals(1.0, SwingTuningDialog.parseRatioString("   "), 1e-6);

    assertThrows(NumberFormatException.class, () -> SwingTuningDialog.parseRatioString("1/0"));
    assertThrows(NumberFormatException.class, () -> SwingTuningDialog.parseRatioString("1/2/3"));
    assertThrows(NumberFormatException.class, () -> SwingTuningDialog.parseRatioString("-5/4"));
    assertThrows(NumberFormatException.class, () -> SwingTuningDialog.parseRatioString("Infinity"));
    assertThrows(NumberFormatException.class, () -> SwingTuningDialog.parseRatioString("NaN"));

    ProjectModel project = new ProjectModel();
    project.setOctaveNumMicrotonalNotes(96);
    project.setBaseFrequencyHz(0.0);
    project.getCentAdjustForNotesInTemperament()[0] = 250;

    SwingUtilities.invokeAndWait(
        () -> {
          SwingTuningDialog dlg = new SwingTuningDialog(null, project, () -> {});
          dlg.dispose();
        });
  }

  @Test
  void testVelocityLaneReadsAndWritesGateFilterAndPanModes() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingUtilities.invokeAndWait(
        () -> {
          SwingVelocityLanePanel lane = new SwingVelocityLanePanel(bridge);

          lane.setMode("GATE");
          lane.updateValue(3, 0.75);
          assertEquals(0.75, bridge.getGate(0, 3), 1e-4, "GATE mode must write bridge gate");
          assertEquals(0.75, lane.readValue(3), 1e-4, "GATE mode must read bridge gate");

          lane.setMode("FILTER");
          lane.updateValue(2, 0.40);
          assertEquals(0.40, lane.readValue(2), 1e-4, "FILTER mode must read G_STEP_FILTER");

          lane.setMode("PAN");
          lane.updateValue(1, 0.25);
          assertEquals(0.25, lane.readValue(1), 1e-4, "PAN mode must map [-1, 1] back to [0, 1]");
        });

    bridge.shutdown();
  }

  @Test
  void testThresholdRecordDialogDisablesSlotComboForNonKitTracks() throws Exception {
    ProjectModel project = new ProjectModel();
    project.addTrack(new KitTrackModel("Drums"));
    project.addTrack(new SynthTrackModel("Lead"));
    project.addTrack(new AudioTrackModel("Guitar"));

    SwingUtilities.invokeAndWait(
        () -> {
          ThresholdRecordDialog dlg = new ThresholdRecordDialog(null, project, () -> {});
          try {
            @SuppressWarnings("unchecked")
            JComboBox<String> trackCombo =
                (JComboBox<String>) findComboBoxWithItem(dlg.getContentPane(), "Drums (Kit)");
            @SuppressWarnings("unchecked")
            JComboBox<String> slotCombo =
                (JComboBox<String>) findComboBoxWithItem(dlg.getContentPane(), "Slot 1 (Drum)");
            assertNotNull(trackCombo);
            assertNotNull(slotCombo);

            // Index 0 = Kit -> slotCombo enabled
            trackCombo.setSelectedIndex(0);
            assertTrue(slotCombo.isEnabled(), "Slot combo must be enabled for Kit tracks");

            // Index 1 = Synth -> slotCombo disabled
            trackCombo.setSelectedIndex(1);
            assertFalse(slotCombo.isEnabled(), "Slot combo must be disabled for Synth tracks");

            // Index 2 = Audio -> slotCombo disabled
            trackCombo.setSelectedIndex(2);
            assertFalse(slotCombo.isEnabled(), "Slot combo must be disabled for Audio tracks");
          } finally {
            dlg.dispose();
          }
        });
  }

  private static JComboBox<?> findComboBoxWithItem(Container root, String itemText) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JComboBox<?> combo) {
        for (int i = 0; i < combo.getItemCount(); i++) {
          if (itemText.equals(String.valueOf(combo.getItemAt(i)))) {
            return combo;
          }
        }
      }
      if (comp instanceof Container child) {
        JComboBox<?> found = findComboBoxWithItem(child, itemText);
        if (found != null) return found;
      }
    }
    return null;
  }
}
