package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GridScrollControllerTest {

  private TestGridContext context;
  private GridScrollController controller;
  private boolean refreshCalled;

  @BeforeEach
  void setUp() {
    context = new TestGridContext();
    refreshCalled = false;
    controller = new GridScrollController(context, () -> refreshCalled = true);
  }

  @Test
  void testScrollByClampsToVoiceRowCount() {
    context.voiceRowCount = 32;
    context.rowsInView = 8;
    context.scrollOffset = 0;

    // Scroll down by 4 rows
    controller.scrollBy(4);
    assertEquals(4, context.scrollOffset);
    assertTrue(refreshCalled);

    // Scroll down past the maximum possible offset (32 - 8 = 24)
    refreshCalled = false;
    controller.scrollBy(30);
    assertEquals(24, context.scrollOffset);
    assertTrue(refreshCalled);

    // Scroll up past 0
    refreshCalled = false;
    controller.scrollBy(-50);
    assertEquals(0, context.scrollOffset);
    assertTrue(refreshCalled);
  }

  @Test
  void testScrollPage() {
    context.voiceRowCount = 32;
    context.rowsInView = 8;
    context.scrollOffset = 0;

    // Page Down (direction = 1) -> scrolls by 8 rows
    controller.scrollPage(1);
    assertEquals(8, context.scrollOffset);

    // Page Up (direction = -1) -> scrolls back by 8 rows
    controller.scrollPage(-1);
    assertEquals(0, context.scrollOffset);
  }

  @Test
  void testScrollHorizontallyClampsToTrackLength() {
    context.stepCount = 16;
    context.trackLength = 64; // 4 pages of 16 steps
    context.scrollOffsetX = 0;

    // Scroll right by 16 steps
    controller.scrollHorizontally(16);
    assertEquals(16, context.scrollOffsetX);
    assertTrue(refreshCalled);

    // Scroll right past the maximum offset (64 - 16 = 48)
    refreshCalled = false;
    controller.scrollHorizontally(50);
    assertEquals(48, context.scrollOffsetX);
    assertTrue(refreshCalled);

    // Scroll left past 0
    refreshCalled = false;
    controller.scrollHorizontally(-100);
    assertEquals(0, context.scrollOffsetX);
    assertTrue(refreshCalled);
  }

  @Test
  void testPlayheadFollowAutoScrolling() {
    context.stepCount = 16;
    context.trackLength = 64;
    context.scrollOffsetX = 0;
    context.playheadFollowMode = true;
    context.bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Playhead moves to step 5 (still on page 0: steps 0-15) -> no scroll
    controller.updatePlayheadFollow(5);
    assertEquals(0, context.scrollOffsetX);

    // Playhead moves to step 18 (page 1: steps 16-31) -> scrolls to 16
    controller.updatePlayheadFollow(18);
    assertEquals(16, context.scrollOffsetX);

    // Playhead moves to step 35 (page 2: steps 32-47) -> scrolls to 32
    controller.updatePlayheadFollow(35);
    assertEquals(32, context.scrollOffsetX);

    // If playhead follow is disabled, playhead moves but scroll offset remains unchanged
    context.playheadFollowMode = false;
    controller.updatePlayheadFollow(50);
    assertEquals(32, context.scrollOffsetX);
  }

  @Test
  void testMouseWheelAccumulation() {
    context.voiceRowCount = 32;
    context.rowsInView = 8;
    context.scrollOffset = 0;

    JPanel dummySource = new JPanel();

    // 1. Vertical scroll (no shift key)
    // Simulate small fractional scroll ticks that accumulate
    MouseWheelEvent event1 =
        new MouseWheelEvent(
            dummySource,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            0,
            0.4);
    controller.handleMouseWheel(event1);
    assertEquals(0, context.scrollOffset); // Not enough to scroll 1 whole row yet

    MouseWheelEvent event2 =
        new MouseWheelEvent(
            dummySource,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            0,
            0.7);
    controller.handleMouseWheel(event2);
    // Accumulated: 0.4 + 0.7 = 1.1 -> scrolls by 1 row
    assertEquals(1, context.scrollOffset);

    // 2. Horizontal scroll (with shift key)
    context.stepCount = 16;
    context.trackLength = 64;
    context.scrollOffsetX = 0;

    MouseWheelEvent event3 =
        new MouseWheelEvent(
            dummySource,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            MouseWheelEvent.SHIFT_DOWN_MASK,
            0,
            0,
            0,
            0,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            0,
            1.5);
    controller.handleMouseWheel(event3);
    // Shift held + accumulated 1.5 -> scrolls horizontally by 1 step
    assertEquals(1, context.scrollOffsetX);
  }

  @Test
  void testResetScrollOffsetForSynthTrack() {
    context.isSynthTrack = true;
    context.foldMode = false;
    context.foldedPitches.add(60); // C4
    context.foldedPitches.add(64); // E4
    context.foldedPitches.add(67); // G4

    controller.resetScrollOffset();
    // Center on middle pitch (64): scrollOffset = 124 - 64 = 60
    assertEquals(60, context.scrollOffset);
    assertEquals(0, context.scrollOffsetX);
  }

  @Test
  void testResetScrollOffsetForKitTrack() {
    context.isSynthTrack = false;
    context.scrollOffset = 10;
    context.scrollOffsetX = 10;

    controller.resetScrollOffset();
    // Kit tracks reset to 0
    assertEquals(0, context.scrollOffset);
    assertEquals(0, context.scrollOffsetX);
  }

  // ── Test Stub for GridContext ──
  private static class TestGridContext implements GridScrollController.GridContext {
    int scrollOffset;
    int scrollOffsetX;
    boolean playheadFollowMode;
    ProjectModel projectModel = new ProjectModel();
    BridgeContract bridge = new BridgeContract();
    int editedModelTrack;
    int activeClipId;
    SwingGridPanel.GridViewMode viewMode = SwingGridPanel.GridViewMode.CLIP;
    boolean isSynthTrack;
    int voiceRowCount;
    int rowsInView;
    int stepCount;
    int trackLength;
    int baseTrackId;
    List<Integer> foldedPitches = new ArrayList<>();
    boolean foldMode;
    boolean refreshInProgress;

    @Override
    public int getScrollOffset() {
      return scrollOffset;
    }

    @Override
    public void setScrollOffset(int val) {
      this.scrollOffset = val;
    }

    @Override
    public int getScrollOffsetX() {
      return scrollOffsetX;
    }

    @Override
    public void setScrollOffsetX(int val) {
      this.scrollOffsetX = val;
    }

    @Override
    public boolean isPlayheadFollowMode() {
      return playheadFollowMode;
    }

    @Override
    public void setPlayheadFollowMode(boolean val) {
      this.playheadFollowMode = val;
    }

    @Override
    public ProjectModel getProjectModel() {
      return projectModel;
    }

    @Override
    public BridgeContract getBridge() {
      return bridge;
    }

    @Override
    public int getEditedModelTrack() {
      return editedModelTrack;
    }

    @Override
    public int getActiveClipId() {
      return activeClipId;
    }

    @Override
    public SwingGridPanel.GridViewMode getViewMode() {
      return viewMode;
    }

    @Override
    public boolean isSynthTrack() {
      return isSynthTrack;
    }

    @Override
    public int getVoiceRowCount() {
      return voiceRowCount;
    }

    @Override
    public int getRowsInView() {
      return rowsInView;
    }

    @Override
    public int getStepCount() {
      return stepCount;
    }

    @Override
    public int getTrackLength() {
      return trackLength;
    }

    @Override
    public int getBaseTrackId() {
      return baseTrackId;
    }

    @Override
    public List<Integer> getFoldedPitches() {
      return foldedPitches;
    }

    @Override
    public void updateFoldedPitches() {}

    @Override
    public boolean isFoldMode() {
      return foldMode;
    }

    @Override
    public boolean isRefreshInProgress() {
      return refreshInProgress;
    }

    @Override
    public int getRowPitch(int row) {
      return 60;
    }
  }
}
