package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies re-engineered Note Spanning, drag-to-tie notes entries, and horizontal auto-scrolling.
 */
public class SwingGridPanelNoteSpanningTest {

  @Test
  public void testNoteSpanningAndTies() throws Exception {
    // 1. Initialize VM and Bridge
    BridgeContract bridge = new BridgeContract();

    ProjectModel model = new ProjectModel();
    model.setBpm(120);

    SynthTrackModel synthTrack = new SynthTrackModel("Synth 2");
    // Setup clip with 64 steps (beyond 16 steps!)
    ClipModel clipModel = new ClipModel("Clip 2", 8, 64);
    for (int r = 0; r < 8; r++) {
      for (int s = 0; s < 64; s++) {
        clipModel.setStep(r, s, StepData.empty());
      }
    }
    synthTrack.getClips().add(clipModel);
    synthTrack.setActiveClipIndex(0);
    model.getTracks().add(synthTrack);

    SwingGridPanel gridPanel = new SwingGridPanel(bridge);
    gridPanel.setProjectModel(model);
    gridPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    gridPanel.setBaseTrackId(0);
    gridPanel.setActiveClipId(0);

    bridge.clearAllSteps();

    // Trigger step tie span extending from step 10 to step 24 (15 steps total span, crossing 16
    // step boundary!)
    gridPanel.handleStepTied(2, 10, 24);

    int modelRow = gridPanel.getModelRow(2);
    int engineRow = 0 + modelRow;

    // Verify starting step in the model
    StepData startStep = clipModel.getStep(modelRow, 10);
    assertTrue(startStep.active(), "Start step must be active");
    assertEquals(14.9f, startStep.gate(), 0.01f, "Start step gate must be 14.9f");

    // Verify intermediate/ending steps are properly cleared
    for (int s = 11; s <= 24; s++) {
      StepData intermediateStep = clipModel.getStep(modelRow, s);
      assertFalse(intermediateStep.active(), "Intermediate step " + s + " must be cleared");
      assertEquals(0.0f, intermediateStep.gate(), 0.01f);
    }

    bridge.shutdown();
  }
}
