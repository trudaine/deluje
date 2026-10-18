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
  void crossScreenWrap_handleStepTied_wrapsAcross12StepTripletScreensAndSkipsUndefinedSquares() {
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
      assertEquals(16, panel.getStepCount(), "16 visible grid columns remain in triplet view");
      assertEquals(18, panel.getColumnCount(), "16 pad columns + 2 audition/mute columns");
      assertEquals(12, panel.getScreenStepCount(), "12 triplet steps per screen");

      assertFalse(panel.isSquareDefined(3), "col 3 is undefined in triplet mode");
      assertFalse(panel.isSquareDefined(7), "col 7 is undefined in triplet mode");
      assertEquals(-1, panel.getActiveCol(0, 3), "undefined triplet square maps to -1");
      assertEquals(2, panel.getActiveCol(0, 2), "col 2 maps to triplet step 2");
      assertEquals(3, panel.getActiveCol(0, 4), "col 4 maps to triplet step 3");

      int visualRow = 0;
      int visualModelRow = panel.getModelRow(visualRow);
      // Toggling undefined column 3 must be a no-op
      panel.clipController.handleStepToggled(visualRow, 3);
      assertEquals(
          -1,
          panel.getClipRowIndex(clip, visualModelRow, false),
          "toggling undefined triplet column must not allocate a row");

      SwingGridPanel.isCrossScreenWrapActive = true;
      // Tie from visual col 2 (step 2) across undefined col 3 to visual col 4 (step 3)
      panel.clipController.handleStepTied(visualRow, 2, 4);

      int clipRow = panel.getClipRowIndex(clip, visualModelRow, false);
      assertTrue(clipRow >= 0);
      for (int sCol : new int[] {2, 14, 26}) {
        assertTrue(clip.getStep(clipRow, sCol).active(), "start tie at " + sCol);
        assertEquals(
            1.9f,
            clip.getStep(clipRow, sCol).gate(),
            0.001f,
            "gate across 1 triplet step (col 2 to col 4) at " + sCol);
        assertFalse(clip.getStep(clipRow, sCol + 1).active(), "cleared tail at " + (sCol + 1));
      }
      assertFalse(clip.getStep(clipRow, 18).active(), "2 + 16 must not be tied in 12-step mode");
    } finally {
      SwingGridPanel.isCrossScreenWrapActive = prevWrap;
      bridge.shutdown();
    }
  }

  @Test
  void shiftClickAndFormattedValue_mapModFxReverbDelayAndGuardNegativeTrackIndex() {
    BridgeContract bridge = new BridgeContract();
    try {
      ClipGridPanel panel = new ClipGridPanel(bridge);
      ProjectModel project = new ProjectModel();
      SynthTrackModel synth = new SynthTrackModel("Synth");
      synth.setReverbSend(0.35f);
      synth.setDelaySend(0.60f);
      synth.setModFxRate(0.20f);
      synth.setModFxDepth(0.45f);
      synth.setModFxFeedback(0.55f);
      synth.setModFxOffset(0.70f);
      synth.setDelayFeedbackQ31(ClipEditorController.delayFeedbackQ31FromUnit(0.80));
      synth.setDelayPingPong(true);
      synth.setDelayAnalog(true);
      synth.getClips().add(new ClipModel("Clip", 8, 16));
      project.getTracks().clear();
      project.getTracks().add(synth);

      panel.projectModel = project;
      panel.editedModelTrack = 0;
      panel.activeClipId = 0;

      // Legacy / send labels
      assertEquals("35%", panel.clipController.getParamFormattedValue("REVERB", 0, 7));
      assertEquals("60%", panel.clipController.getParamFormattedValue("DELAY", 0, 6));
      // ModFX column 12 (menus.cpp:1933)
      assertEquals("20%", panel.clipController.getParamFormattedValue("RATE", 0, 12));
      assertEquals("45%", panel.clipController.getParamFormattedValue("DEPTH", 1, 12));
      assertEquals("55%", panel.clipController.getParamFormattedValue("FDBACK", 2, 12));
      assertEquals("70%", panel.clipController.getParamFormattedValue("OFFSET", 3, 12));
      // Reverb column 13 vs Delay column 14 (menus.cpp:1934-1935)
      assertEquals("35%", panel.clipController.getParamFormattedValue("AMOUNT", 4, 13));
      assertEquals("80%", panel.clipController.getParamFormattedValue("AMOUNT", 4, 14));
      assertEquals("80%", panel.clipController.getParamFormattedValue("FEEDBACK", 4, 14));
      assertEquals("ON", panel.clipController.getParamFormattedValue("MONO/ STEREO", 3, 14));
      assertEquals("ANLG", panel.clipController.getParamFormattedValue("DIGI/ ANALOG", 5, 14));
      // Unbacked labels must return "--" instead of silently reading another parameter
      assertEquals("--", panel.clipController.getParamFormattedValue("DIFF", 0, 13));
      assertEquals("--", panel.clipController.getParamFormattedValue("DELAY TIME", 7, 14));
      assertEquals("--", panel.clipController.getParamFormattedValue("RATE", 7, 14));

      // Rotary adjustment mutates the real backing parameter
      panel.clipController.setActiveShiftParam("DEPTH", 1, 12);
      panel.clipController.adjustRotaryParameter(5);
      assertEquals(0.55f, synth.getModFxDepth(), 0.001f);
      assertEquals(0.35f, synth.getReverbSend(), 0.001f, "DEPTH must not touch reverbSend");

      panel.clipController.setActiveShiftParam("AMOUNT", 4, 14);
      panel.clipController.adjustRotaryParameter(2);
      assertEquals(
          0.90f, ClipEditorController.delayFeedbackUnitFromQ31(synth.getDelayFeedbackQ31()), 0.01f);
      assertEquals(0.35f, synth.getReverbSend(), 0.001f, "col 14 AMOUNT must not touch reverbSend");

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
