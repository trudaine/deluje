package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import javax.swing.JButton;
import org.deluge.BridgeContract;
import org.deluge.engine.SequencerClock;
import org.deluge.model.ArrangerClip;
import org.deluge.model.AutomationParam;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for SongGridPanel bottom-up/scrolled track targeting, ArrangerGridPanel
 * 24-column rendering, AutomationGridPanel horizontal scroll / undo / Clear All, and SequencerClock
 * BPM guard / bar-step resolution.
 */
class GridAutomationAndClockParityTest {

  @Test
  void testSongGridPanelBottomUpAndScrolledRowTargetsCorrectTrack() {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track0 = new SynthTrackModel("T0");
    track0.addClip(new ClipModel("T0_C0", 8, 16));
    track0.addClip(new ClipModel("T0_C1", 8, 16));
    project.addTrack(track0);

    TrackModel track1 = new SynthTrackModel("T1");
    track1.addClip(new ClipModel("T1_C0", 8, 16));
    track1.addClip(new ClipModel("T1_C1", 8, 16));
    project.addTrack(track1);

    TrackModel track2 = new SynthTrackModel("T2");
    track2.addClip(new ClipModel("T2_C0", 8, 16));
    track2.addClip(new ClipModel("T2_C1", 8, 16));
    project.addTrack(track2);

    SongGridPanel panel = new SongGridPanel(bridge);
    panel.setProjectModel(project);

    // In Song view (bottom-up ordering), visual row 0 corresponds to track 2 (n - 1).
    assertEquals(2, panel.songRowIndex(0));

    // Shift-clicking column 1 on visual row 0 must instant-launch clip 1 on track 2, not track 0.
    JButton row0Col1 = panel.pads[0][1];
    assertNotNull(row0Col1);
    dispatchMousePress(row0Col1, InputEvent.SHIFT_DOWN_MASK);

    assertEquals(1, track2.getActiveClipIndex(), "Track 2 active clip should become 1");
    assertEquals(0, track0.getActiveClipIndex(), "Track 0 active clip must remain untouched");
    assertEquals(1, bridge.getCurrentClip(2));

    // Clicking an empty slot (column 2) on visual row 0 must create a clip on track 2, not track 0.
    JButton row0Col2 = panel.pads[0][2];
    assertNotNull(row0Col2);
    dispatchMousePress(row0Col2, 0);

    assertEquals(3, track2.getClips().size(), "New clip must be created on track 2");
    assertEquals(2, track0.getClips().size(), "Track 0 clip count must remain untouched");

    // Add tracks T3..T8 (total 9 tracks > 8 grid rows) so scrollOffset=1 is not clamped to 0.
    for (int i = 3; i <= 8; i++) {
      TrackModel extra = new SynthTrackModel("T" + i);
      extra.addClip(new ClipModel("T" + i + "_C0", 8, 16));
      extra.addClip(new ClipModel("T" + i + "_C1", 8, 16));
      project.addTrack(extra);
    }

    // With 9 tracks and scrollOffset=1, visual row 0 maps to track 7 (9 - 1 - 1 = 7).
    panel.setScrollOffset(1);
    panel.forceRebuild();
    assertEquals(7, panel.songRowIndex(0));

    JButton scrolledRow0Col1 = panel.pads[0][1];
    assertNotNull(scrolledRow0Col1);
    dispatchMousePress(scrolledRow0Col1, InputEvent.SHIFT_DOWN_MASK);

    assertEquals(
        1, project.getTracks().get(7).getActiveClipIndex(), "Scrolled row 0 must target track 7");
    assertEquals(0, track0.getActiveClipIndex(), "Track 0 active clip must remain untouched");

    bridge.shutdown();
  }

  @Test
  void testArrangerGridPanel24ColumnModeRendersColumns16To23() {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    PreferencesManager.GridMode savedMode = PreferencesManager.getGridMode();
    try {
      ProjectModel project = new ProjectModel();
      project.setXZoomArrangementView(96);
      TrackModel track0 = new SynthTrackModel("LEAD");
      ClipModel clip0 = new ClipModel("INTRO", 8, 16);
      track0.addClip(clip0);
      project.addTrack(track0);

      // Place clip at bar index 20 (column 20 at 96 ticks per column).
      project.addArrangerClip(new ArrangerClip(0, clip0, 20 * 96, 96));

      ArrangerGridPanel panel = new ArrangerGridPanel(bridge);
      panel.setViewMode(SwingGridPanel.GridViewMode.ARRANGEMENT);
      panel.setGridMode(PreferencesManager.GridMode.GRID_16x24);
      panel.setProjectModel(project);
      panel.forceRebuild();

      assertEquals(24, panel.getStepCount());
      JButton col20Pad = panel.pads[0][20];
      assertNotNull(col20Pad, "Column 20 pad must exist in GRID_16x24 mode");
      assertTrue(
          col20Pad.getText() != null && col20Pad.getText().contains("INTRO"),
          "Column 20 pad must render clip name in 24-column mode");
      assertTrue(
          col20Pad.getText().contains("Bar 21"),
          "Column 20 pad must render Bar 21 label in 24-column mode");
      assertTrue(
          col20Pad.getToolTipText() != null && col20Pad.getToolTipText().contains("Bar 21"),
          "Column 20 pad must include Bar 21 tooltip in 24-column mode");
    } finally {
      PreferencesManager.setGridMode(savedMode);
      bridge.shutdown();
    }
  }

  @Test
  void testAutomationScrollOffsetXSingleUndoAndUninitializedSteps() {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track0 = new SynthTrackModel("SYNTH");
    ClipModel clip = new ClipModel("AUTO_CLIP", 8, 32);
    track0.addClip(clip);
    project.addTrack(track0);

    AutomationGridPanel panel = new AutomationGridPanel(bridge);
    panel.setProjectModel(project);
    panel.setEditedModelTrack(0);
    panel.setViewMode(SwingGridPanel.GridViewMode.AUTOMATION);
    panel.setScrollOffsetX(16);
    panel.forceRebuild();

    String param0 = AutomationParam.SYTH_PARAMS[0];

    // Visual column 2 with scrollOffsetX=16 maps to step 18.
    JButton overviewPad = panel.pads[0][2];
    assertNotNull(overviewPad);
    dispatchMousePress(overviewPad, 0);
    dispatchMouseRelease(overviewPad, 0);

    assertEquals(0.5f, clip.getAutomation(param0, 18), 1e-4f, "Step 18 must be set to 0.5f");
    assertEquals(
        -1f, clip.getAutomation(param0, 2), 1e-4f, "Unscrolled step 2 must remain unautomated");
    assertEquals(
        -1f, clip.getAutomation(param0, 0), 1e-4f, "Untouched steps must be -1f, not 0.0f");

    // Verify only one undo entry was pushed (not duplicate entries from press + release).
    assertTrue(project.getUndoRedoStack().canUndo());
    project.getUndoRedoStack().undo();
    assertEquals(-1f, clip.getAutomation(param0, 18), 1e-4f, "Single undo must revert step 18");
    assertFalse(
        project.getUndoRedoStack().canUndo(),
        "No duplicate undo entry should remain after undoing a single click");

    // Switch to detail editor mode and click band row 7, visual column 3 (step 19).
    panel.setAutoOverviewMode(false);
    panel.forceRebuild();

    JButton detailPad = panel.pads[7][3];
    assertNotNull(detailPad);
    dispatchMousePress(detailPad, 0);
    dispatchMouseRelease(detailPad, 0);

    float expectedBand7 = (7 * 16 + 8) / 127.0f;
    assertEquals(
        expectedBand7,
        clip.getAutomation(param0, 19),
        1e-4f,
        "Detail editor with scrollOffsetX=16 must write to step 19");
    assertEquals(-1f, clip.getAutomation(param0, 3), 1e-4f, "Step 3 must remain unautomated");

    bridge.shutdown();
  }

  @Test
  void testAutomationClearAllWithMultipleParamsDoesNotThrowConcurrentModification() {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    TrackModel track0 = new SynthTrackModel("SYNTH");
    ClipModel clip = new ClipModel("AUTO_CLIP", 8, 16);
    track0.addClip(clip);
    project.addTrack(track0);

    AutomationEditorController.setAutomationPoint(clip, AutomationParam.SYTH_PARAMS[0], 0, 0.25f);
    AutomationEditorController.setAutomationPoint(clip, AutomationParam.SYTH_PARAMS[1], 1, 0.50f);
    AutomationEditorController.setAutomationPoint(clip, AutomationParam.SYTH_PARAMS[2], 2, 0.75f);

    AutomationGridPanel panel = new AutomationGridPanel(bridge);
    panel.setProjectModel(project);
    panel.setEditedModelTrack(0);
    panel.setViewMode(SwingGridPanel.GridViewMode.AUTOMATION);
    panel.setAutoOverviewMode(true);
    panel.forceRebuild();

    JButton clearAllBtn = findButtonByText(panel, "Clear All");
    assertNotNull(clearAllBtn, "Clear All button must exist in overview header");
    assertDoesNotThrow(
        () ->
            clearAllBtn.getActionListeners()[0].actionPerformed(
                new ActionEvent(clearAllBtn, ActionEvent.ACTION_PERFORMED, "")));
    assertTrue(clip.getAutomatedParams().isEmpty(), "All automated parameters must be cleared");

    bridge.shutdown();
  }

  @Test
  void testSequencerClockBpmGuardAndBarSteps() {
    SequencerClock clock = new SequencerClock(null, (step, isNew) -> {});
    long expected120BpmNs = 125_000_000L; // 60 / 120 / 4 = 0.125s = 125_000_000 ns at swing 0.5

    assertEquals(expected120BpmNs, clock.calculateStepDurationNs(0, 120.0, 0.5));
    assertEquals(
        expected120BpmNs,
        clock.calculateStepDurationNs(0, 0.0, 0.5),
        "BPM 0 must fall back to 120 BPM instead of Long.MAX_VALUE");
    assertEquals(
        expected120BpmNs,
        clock.calculateStepDurationNs(0, -10.0, 0.5),
        "Negative BPM must fall back to 120 BPM");
    assertEquals(
        expected120BpmNs,
        clock.calculateStepDurationNs(0, Double.NaN, Double.NaN),
        "NaN BPM and swing must fall back to 120 BPM and neutral swing");

    // Empty track (0) and 4/4 lengths (16, 32, 48, 64, 96) must use 16-step bars.
    assertEquals(16, SequencerClock.resolveBarSteps(0));
    assertEquals(16, SequencerClock.resolveBarSteps(16));
    assertEquals(16, SequencerClock.resolveBarSteps(32));
    assertEquals(16, SequencerClock.resolveBarSteps(48));
    assertEquals(16, SequencerClock.resolveBarSteps(64));
    assertEquals(16, SequencerClock.resolveBarSteps(96));

    // Pure triplet lengths (12, 24, 36) must use 12-step bars.
    assertEquals(12, SequencerClock.resolveBarSteps(12));
    assertEquals(12, SequencerClock.resolveBarSteps(24));
    assertEquals(12, SequencerClock.resolveBarSteps(36));
  }

  private static void dispatchMousePress(JButton btn, int modifiersEx) {
    MouseEvent ev =
        new MouseEvent(
            btn,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            modifiersEx,
            2,
            2,
            1,
            false,
            MouseEvent.BUTTON1);
    for (var ml : btn.getMouseListeners()) {
      ml.mousePressed(ev);
    }
  }

  private static void dispatchMouseRelease(JButton btn, int modifiersEx) {
    MouseEvent ev =
        new MouseEvent(
            btn,
            MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(),
            modifiersEx,
            2,
            2,
            1,
            false,
            MouseEvent.BUTTON1);
    for (var ml : btn.getMouseListeners()) {
      ml.mouseReleased(ev);
    }
  }

  private static JButton findButtonByText(Container root, String text) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JButton btn && text.equals(btn.getText())) {
        return btn;
      }
      if (comp instanceof Container child) {
        JButton found = findButtonByText(child, text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
