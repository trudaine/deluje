package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

class StepCrossScreenShiftClickTest {

  @Test
  void crossScreenWrap_uses24StepScreenWidthInToggleAndStepProperties() {
    BridgeContract bridge = new BridgeContract();
    boolean prevWrap = SwingGridPanel.isCrossScreenWrapActive;
    try {
      ClipGridPanel panel = new ClipGridPanel(bridge);
      ProjectModel project = new ProjectModel();
      SynthTrackModel synth = new SynthTrackModel("Synth");
      ClipModel clip = new ClipModel("Clip", 8, 48);
      synth.getClips().add(clip);
      project.getTracks().clear();
      project.getTracks().add(synth);

      panel.projectModel = project;
      panel.editedModelTrack = 0;
      panel.activeClipId = 0;
      panel.setGridMode(org.deluge.project.PreferencesManager.GridMode.GRID_16x24);
      assertEquals(24, panel.getStepCount(), "24-step layout mode");
      SwingGridPanel.isCrossScreenWrapActive = true;

      int visualRow = 0;
      int visualModelRow = panel.getModelRow(visualRow);
      panel.clipController.handleStepToggled(visualRow, 2);

      int clipRow = panel.getClipRowIndex(clip, visualModelRow, false);
      assertTrue(clipRow >= 0, "clip row must be allocated");
      assertTrue(clip.getStep(clipRow, 2).active(), "step 2 on screen 0 must be active");
      assertTrue(clip.getStep(clipRow, 26).active(), "step 26 on screen 1 (2 + 24) must be active");
      assertFalse(
          clip.getStep(clipRow, 18).active(),
          "step 18 (2 + 16) must not be touched in 24-step screen layout");

      StepPropertiesEditor.applyStepProperties(
          panel.clipController, visualRow, 2, 0.5, 0, 0.0, 0.75, 1.0, 0.0);
      assertEquals(0.5f, clip.getStep(clipRow, 2).velocity(), 0.001f);
      assertEquals(0.5f, clip.getStep(clipRow, 26).velocity(), 0.001f);
      assertEquals(0.75f, clip.getStep(clipRow, 2).probability(), 0.001f);
      assertEquals(0.75f, clip.getStep(clipRow, 26).probability(), 0.001f);

      StepPropertiesEditor.saveStepProbability(panel.clipController, visualRow, 2, 0.25);
      assertEquals(0.25f, clip.getStep(clipRow, 2).probability(), 0.001f);
      assertEquals(0.25f, clip.getStep(clipRow, 26).probability(), 0.001f);
    } finally {
      SwingGridPanel.isCrossScreenWrapActive = prevWrap;
      bridge.shutdown();
    }
  }

  @Test
  void crossScreenWrap_handleStepTied_wrapsAcross12StepTripletScreens() {
    BridgeContract bridge = new BridgeContract();
    boolean prevWrap = SwingGridPanel.isCrossScreenWrapActive;
    try {
      ClipGridPanel panel = new ClipGridPanel(bridge);
      ProjectModel project = new ProjectModel();
      SynthTrackModel synth = new SynthTrackModel("Synth");
      ClipModel clip = new ClipModel("Triplet", 8, 36);
      clip.setTripletMode(true);
      synth.getClips().add(clip);
      project.getTracks().clear();
      project.getTracks().add(synth);

      panel.projectModel = project;
      panel.editedModelTrack = 0;
      panel.activeClipId = 0;
      panel.refresh();
      assertEquals(12, panel.getStepCount(), "12 triplet steps per screen after refresh");
      SwingGridPanel.isCrossScreenWrapActive = true;

      int visualRow = 0;
      int visualModelRow = panel.getModelRow(visualRow);
      panel.clipController.handleStepTied(visualRow, 1, 3);

      int clipRow = panel.getClipRowIndex(clip, visualModelRow, false);
      assertTrue(clipRow >= 0);
      for (int sCol : new int[] {1, 13, 25}) {
        assertTrue(clip.getStep(clipRow, sCol).active(), "start tie at " + sCol);
        assertEquals(2.9f, clip.getStep(clipRow, sCol).gate(), 0.001f, "gate at " + sCol);
        assertFalse(clip.getStep(clipRow, sCol + 1).active(), "cleared tail at " + (sCol + 1));
        assertFalse(clip.getStep(clipRow, sCol + 2).active(), "cleared tail at " + (sCol + 2));
      }
      assertFalse(clip.getStep(clipRow, 17).active(), "1 + 16 must not be tied in 12-step mode");
    } finally {
      SwingGridPanel.isCrossScreenWrapActive = prevWrap;
      bridge.shutdown();
    }
  }

  @Test
  void shiftClickAndFormattedValue_formatReverbAndDelayAndGuardNegativeTrackIndex() {
    BridgeContract bridge = new BridgeContract();
    try {
      ClipGridPanel panel = new ClipGridPanel(bridge);
      ProjectModel project = new ProjectModel();
      SynthTrackModel synth = new SynthTrackModel("Synth");
      synth.setReverbSend(0.35f);
      synth.setDelaySend(0.60f);
      synth.getClips().add(new ClipModel("Clip", 8, 16));
      project.getTracks().clear();
      project.getTracks().add(synth);

      panel.projectModel = project;
      panel.editedModelTrack = 0;
      panel.activeClipId = 0;

      assertEquals("35%", panel.clipController.getParamFormattedValue("REVERB", 0, 7));
      assertEquals("60%", panel.clipController.getParamFormattedValue("DELAY", 0, 6));

      panel.editedModelTrack = -1;
      assertEquals("--", panel.clipController.getParamFormattedValue("CUTOFF", 0, 8));
      assertDoesNotThrow(() -> panel.clipController.handleShiftHover(0, 8));
      assertDoesNotThrow(() -> panel.clipController.adjustRotaryParameter(1));
      assertDoesNotThrow(() -> panel.clipController.handleStepToggled(0, 0));
      assertDoesNotThrow(() -> panel.clipController.handleStepTied(0, 0, 2));
      assertDoesNotThrow(
          () ->
              ShiftClickController.handleShiftClick(
                  panel.clipController, 0, 8, new Point(0, 0), panel));
    } finally {
      bridge.shutdown();
    }
  }
}
