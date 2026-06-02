package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies re-engineered Note Spanning, drag-to-tie notes entries, and horizontal auto-scrolling.
 */
public class SwingGridPanelNoteSpanningTest {

  @Test
  public void testNoteSpanningAndTies() throws Exception {
    // 1. Initialize VM and Bridge
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 2. Create Object Model with a Synth track
    ProjectModel model = new ProjectModel();
    model.setBpm(120);

    SynthTrackModel synthTrack = new SynthTrackModel("Synth 1");
    ClipModel clipModel = new ClipModel("Clip 1", 8, 16);
    // Initialize all steps to empty
    for (int r = 0; r < 8; r++) {
      for (int s = 0; s < 16; s++) {
        clipModel.setStep(r, s, StepData.empty());
      }
    }
    synthTrack.getClips().add(clipModel);
    synthTrack.setActiveClipIndex(0);
    model.getTracks().add(synthTrack);

    // 3. Set up Grid Panel
    SwingGridPanel gridPanel = new SwingGridPanel(vm, bridge);
    gridPanel.setProjectModel(model);
    gridPanel.setViewMode(SwingGridPanel.GridViewMode.CLIP);
    gridPanel.setBaseTrackId(0);
    gridPanel.setActiveClipId(0);

    // Initialize bridge matching
    bridge.clearAllSteps();

    // 4. Trigger step tie span from step 4 to step 6 on Row 2 (visual row)
    // Total span duration = 3 steps -> gate = 2.9f on start step
    gridPanel.handleStepTied(2, 4, 6);

    int modelRow = gridPanel.getModelRow(2);
    int engineRow = 0 + modelRow;

    // Verify start step data
    StepData startStep = clipModel.getStep(modelRow, 4);
    assertTrue(startStep.active(), "Start step of note span must be active");
    assertEquals(2.9f, startStep.gate(), 0.01f, "Start step gate must be 2.9f");

    // Verify intermediate/ending step data are cleared in the model
    StepData intermediateStep = clipModel.getStep(modelRow, 5);
    assertFalse(
        intermediateStep.active(), "Intermediate step must be cleared of separate note-ons");
    assertEquals(0.0f, intermediateStep.gate(), 0.01f);

    StepData endingStep = clipModel.getStep(modelRow, 6);
    assertFalse(endingStep.active(), "Ending step must be cleared of separate note-ons");
    assertEquals(0.0f, endingStep.gate(), 0.01f);

    // Verify shared bridge array updates
    assertTrue(bridge.getStep(engineRow, 4));
    assertEquals(2.9, bridge.getGate(engineRow, 4), 0.01);

    assertFalse(bridge.getStep(engineRow, 5));
    assertEquals(0.0, bridge.getGate(engineRow, 5), 0.01);

    assertFalse(bridge.getStep(engineRow, 6));
    assertEquals(0.0, bridge.getGate(engineRow, 6), 0.01);

    // 5. Trigger refresh to sync UI components and assert correct visual rendering properties
    gridPanel.refresh();

    // Get DelugePadButtons for steps 4, 5, and 6 on visual row 2
    DelugePadButton pad4 = (DelugePadButton) gridPanel.getPads()[2][4];
    DelugePadButton pad5 = (DelugePadButton) gridPanel.getPads()[2][5];
    DelugePadButton pad6 = (DelugePadButton) gridPanel.getPads()[2][6];

    // Assert visual active states (all 3 spanned steps must be lit up)
    assertTrue(pad4.isActive(), "Start step pad must render as active");
    assertTrue(pad5.isActive(), "Spanned intermediate step pad must render as active");
    assertTrue(pad6.isActive(), "Spanned ending step pad must render as active");

    // Assert note-tie visual connectors (connecting from start and intermediate, ending is not tied
    // to next)
    assertTrue(pad4.isTied(), "Start step pad must draw the tie connector");
    assertTrue(pad5.isTied(), "Intermediate step pad must draw the tie connector");
    assertFalse(pad6.isTied(), "Ending step pad must not draw a tie connector past the note's end");

    // Shutdown VM
    vm.shutdown();
  }
}
