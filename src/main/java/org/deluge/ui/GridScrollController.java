package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import org.deluge.BridgeContract;

/**
 * Manages vertical and horizontal viewport scrolling, scrollbar lifecycle, mouse-wheel
 * accumulation, and playhead-follow auto-scrolling for the grid.
 *
 * <p>This controller delegates the actual state storage (offsets and modes) back to the {@link
 * GridContext} (implemented by the view) so that the view's rendering code can read them directly
 * without massive code churn.
 */
public final class GridScrollController {

  private double preciseScrollAccumulatorX = 0.0;
  private double preciseScrollAccumulatorY = 0.0;
  private boolean isScrollingProgrammatically = false;

  private JScrollBar vertScrollBar;
  private JScrollBar horizScrollBar;

  private final GridContext context;
  private final Runnable refreshCallback;

  /** Callback interface to query and update the parent grid's state and dimensions. */
  public interface GridContext {
    int getScrollOffset();

    void setScrollOffset(int val);

    int getScrollOffsetX();

    void setScrollOffsetX(int val);

    boolean isPlayheadFollowMode();

    void setPlayheadFollowMode(boolean val);

    org.deluge.model.ProjectModel getProjectModel();

    BridgeContract getBridge();

    int getEditedModelTrack();

    int getActiveClipId();

    SwingGridPanel.GridViewMode getViewMode();

    boolean isSynthTrack();

    int getVoiceRowCount();

    int getRowsInView();

    int getStepCount();

    int getTrackLength();

    int getBaseTrackId();

    List<Integer> getFoldedPitches();

    void updateFoldedPitches();

    boolean isFoldMode();

    boolean isRefreshInProgress();

    int getRowPitch(int row);

    int getArrangerTicksPerColumn();
  }

  public GridScrollController(GridContext context, Runnable refreshCallback) {
    this.context = context;
    this.refreshCallback = refreshCallback;
  }

  public boolean isScrollingProgrammatically() {
    return isScrollingProgrammatically;
  }

  /** Returns the vertical scrollbar, creating and styling it on first access. */
  public JScrollBar getVerticalScrollBar() {
    if (vertScrollBar == null) {
      vertScrollBar = new JScrollBar(JScrollBar.VERTICAL);
      vertScrollBar.setBackground(new Color(0x15, 0x15, 0x18));
      vertScrollBar.setForeground(new Color(0x00, 0xff, 0xcc));
      vertScrollBar.setPreferredSize(new Dimension(14, 200));

      vertScrollBar.setUI(
          new BasicScrollBarUI() {
            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
              g.setColor(new Color(0x1a, 0x1a, 0x1c)); // matching deep panel background
              g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);

              // Draw a sleek, thin center off-white path line (2px wide)
              g.setColor(new Color(0xdd, 0xdd, 0xe0, 80));
              int midX = trackBounds.x + trackBounds.width / 2;
              g.fillRect(midX - 1, trackBounds.y, 2, trackBounds.height);
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
              // Draw a clean, solid white active segment thumb
              g.setColor(Color.WHITE);
              g.fillRoundRect(
                  thumbBounds.x + 3,
                  thumbBounds.y + 1,
                  thumbBounds.width - 6,
                  thumbBounds.height - 2,
                  4,
                  4);
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
              return createZeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
              return createZeroButton();
            }

            private JButton createZeroButton() {
              JButton b = new JButton();
              b.setPreferredSize(new Dimension(0, 0));
              b.setMinimumSize(new Dimension(0, 0));
              b.setMaximumSize(new Dimension(0, 0));
              return b;
            }
          });

      vertScrollBar.addAdjustmentListener(
          e -> {
            if (context.isRefreshInProgress()) return;
            int val = e.getValue();
            if (val != context.getScrollOffset()) {
              context.setScrollOffset(val);
              refreshCallback.run();
            }
          });
    }
    return vertScrollBar;
  }

  /** Returns the horizontal scrollbar, creating and styling it on first access. */
  public JScrollBar getHorizontalScrollBar() {
    if (horizScrollBar == null) {
      horizScrollBar = new JScrollBar(JScrollBar.HORIZONTAL);
      horizScrollBar.setBackground(new Color(0x15, 0x15, 0x18));
      horizScrollBar.setForeground(new Color(0x00, 0xff, 0xcc));

      horizScrollBar.setUI(
          new BasicScrollBarUI() {
            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
              g.setColor(new Color(0x1a, 0x1a, 0x1c)); // matching deep panel background
              g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);

              // Draw a sleek, thin horizontal center off-white path line (2px wide)
              g.setColor(new Color(0xdd, 0xdd, 0xe0, 80));
              int midY = trackBounds.y + trackBounds.height / 2;
              g.fillRect(trackBounds.x, midY - 2, trackBounds.width, 4);
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
              if (!horizScrollBar.isEnabled()) {
                g.setColor(new Color(0x55, 0x55, 0x5a, 80)); // Dimmed disabled gray
              } else {
                g.setColor(Color.WHITE); // Solid white active segment
              }
              g.fillRoundRect(
                  thumbBounds.x + 1,
                  thumbBounds.y + 3,
                  thumbBounds.width - 2,
                  thumbBounds.height - 6,
                  4,
                  4);
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
              return createZeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
              return createZeroButton();
            }

            private JButton createZeroButton() {
              JButton b = new JButton();
              b.setPreferredSize(new Dimension(0, 0));
              b.setMinimumSize(new Dimension(0, 0));
              b.setMaximumSize(new Dimension(0, 0));
              return b;
            }
          });

      horizScrollBar.addAdjustmentListener(
          e -> {
            if (context.isRefreshInProgress()) return;
            int val = e.getValue();
            if (val != context.getScrollOffsetX()) {
              context.setScrollOffsetX(val);
              if (!isScrollingProgrammatically) {
                context.setPlayheadFollowMode(false);
              }
              refreshCallback.run();
            }
          });
    }
    return horizScrollBar;
  }

  /** Reset scroll offsets when the edited track changes. */
  public void resetScrollOffset() {
    boolean isSynth = context.isSynthTrack();
    int scrollOffset;
    if (isSynth && (context.getViewMode() == SwingGridPanel.GridViewMode.CLIP
        || context.getViewMode() == SwingGridPanel.GridViewMode.AUTOMATION)) {
      if (context.isFoldMode()) {
        context.updateFoldedPitches();
        scrollOffset = 0;
      } else {
        context.updateFoldedPitches();
        List<Integer> foldedPitches = context.getFoldedPitches();
        if (!foldedPitches.isEmpty()) {
          // Center on the middle active pitch
          int midPitch = foldedPitches.get(foldedPitches.size() / 2);
          scrollOffset = 124 - midPitch;
        } else {
          // Default to centering on C4 (midi 60)
          scrollOffset = 67;
        }
        // Restrict scrollOffset to prevent octave 13 ghost zone (restrict to octaves 1 to 8)
        scrollOffset = Math.max(19, Math.min(107, scrollOffset));
      }
    } else {
      scrollOffset = 0;
    }
    context.setScrollOffset(scrollOffset);
    if (context.getViewMode() == SwingGridPanel.GridViewMode.ARRANGEMENT) {
      org.deluge.model.ProjectModel pm = context.getProjectModel();
      if (pm != null) {
        int ticksPerCol = context.getArrangerTicksPerColumn();
        context.setScrollOffsetX(pm.getXScrollArrangementView() / ticksPerCol);
      } else {
        context.setScrollOffsetX(0);
      }
    } else {
      context.setScrollOffsetX(0);
    }
  }

  /** Scroll the voice row viewport by delta rows. Positive = down, negative = up. */
  public void scrollBy(int delta) {
    int voiceRowCount = context.getVoiceRowCount();
    int rowsInView = context.getRowsInView();
    int maxOffset = Math.max(0, voiceRowCount - rowsInView);
    context.setScrollOffset(Math.max(0, Math.min(maxOffset, context.getScrollOffset() + delta)));
    refreshCallback.run();
  }

  /** Scroll by one full page (rowsInView rows). */
  public void scrollPage(int direction) {
    scrollBy(direction * context.getRowsInView());
  }

  /** Scroll horizontally by cellsOffset steps. */
  public void scrollHorizontally(int cellsOffset) {
    if (context.getBridge() == null) {
      return;
    }
    int stepCount = context.getStepCount();
    int trackLenH = context.getTrackLength();
    int scrollOffsetX = context.getScrollOffsetX();
    if (trackLenH > stepCount) {
      int maxOffX = trackLenH - stepCount;
      int newOffset = scrollOffsetX + cellsOffset;
      if (newOffset > maxOffX) newOffset = maxOffX;
      if (newOffset < 0) newOffset = 0;
      if (newOffset != scrollOffsetX) {
        context.setScrollOffsetX(newOffset);
        refreshCallback.run();
      }
    } else {
    }
  }

  /** Scroll vertically by cellsOffset rows. */
  public void scrollVertically(int cellsOffset) {
    if (context.getBridge() == null) {
      return;
    }
    int voiceRowCount = context.getVoiceRowCount();
    int rowsInView = context.getRowsInView();
    int scrollOffset = context.getScrollOffset();
    int maxOffset = Math.max(0, voiceRowCount - rowsInView);
    int newOffset = scrollOffset + cellsOffset;
    if (newOffset > maxOffset) newOffset = maxOffset;
    if (newOffset < 0) newOffset = 0;
    if (newOffset != scrollOffset) {
      context.setScrollOffset(newOffset);
      refreshCallback.run();
    } else {
    }
  }

  public void resetHorizontalScroll() {
    if (context.getScrollOffsetX() != 0) {
      context.setScrollOffsetX(0);
      refreshCallback.run();
    }
  }

  public void resetVerticalScroll() {
    if (context.getScrollOffset() != 0) {
      context.setScrollOffset(0);
      refreshCallback.run();
    }
  }

  /** Synchronizes the scrollbar values with the internal offsets during full UI rebuilds. */
  public void updateScrollBarValues() {
    int rowsInView = context.getRowsInView();
    int voiceRowCount = context.getVoiceRowCount();
    int stepCount = context.getStepCount();
    int trackLenH = context.getTrackLength();
    int scrollOffset = context.getScrollOffset();
    int scrollOffsetX = context.getScrollOffsetX();

    if (vertScrollBar != null) {
      vertScrollBar.setValues(scrollOffset, rowsInView, 0, voiceRowCount);
      updateScrollBarTooltip();
    }
    if (horizScrollBar != null) {
      isScrollingProgrammatically = true;
      try {
        horizScrollBar.setValues(scrollOffsetX, stepCount, 0, Math.max(stepCount, trackLenH));
      } finally {
        isScrollingProgrammatically = false;
      }
    }
  }

  /** Synchronizes scrollbar values in place without triggering full component rebuilds. */
  public void syncScrollBarValues() {
    if (vertScrollBar != null) {
      vertScrollBar.setValue(context.getScrollOffset());
      updateScrollBarTooltip();
    }
    if (horizScrollBar != null) {
      isScrollingProgrammatically = true;
      try {
        horizScrollBar.setValue(context.getScrollOffsetX());
      } finally {
        isScrollingProgrammatically = false;
      }
    }
  }

  /** Handles mouse wheel events and accumulates sub-pixel scrolling increments. */
  public void handleMouseWheel(MouseWheelEvent e) {
    if (context.getViewMode() != SwingGridPanel.GridViewMode.CLIP
        && context.getViewMode() != SwingGridPanel.GridViewMode.AUTOMATION) {
      return;
    }
    double preciseRotation = e.getPreciseWheelRotation();
    if (e.isShiftDown()) {
      preciseScrollAccumulatorX += preciseRotation;
      int cellsToScroll = (int) preciseScrollAccumulatorX;
      if (cellsToScroll != 0) {
        scrollHorizontally(cellsToScroll);
        preciseScrollAccumulatorX -= cellsToScroll;
      }
    } else {
      preciseScrollAccumulatorY += preciseRotation;
      int cellsToScroll = (int) preciseScrollAccumulatorY;
      if (cellsToScroll != 0) {
        scrollVertically(cellsToScroll);
        preciseScrollAccumulatorY -= cellsToScroll;
      }
    }
  }

  /** Scrolls horizontally to a specific page offset. */
  public void scrollHorizontallyToPage(int pageOffset) {
    if (context.getScrollOffsetX() != pageOffset) {
      context.setScrollOffsetX(pageOffset);
      if (horizScrollBar != null && horizScrollBar.isEnabled()) {
        isScrollingProgrammatically = true;
        try {
          horizScrollBar.setValue(pageOffset);
        } finally {
          isScrollingProgrammatically = false;
        }
      }
      refreshCallback.run();
    }
  }

  /**
   * Automatically shifts the viewport horizontally to follow the playhead if follow mode is active.
   */
  public void updatePlayheadFollow(int rawCol) {
    if (context.isPlayheadFollowMode()
        && context.getBridge() != null
        && context.getBridge().getGlobalInt(BridgeContract.G_PLAY) == 1L) {
      int stepCount = context.getStepCount();
      int playheadPage = (rawCol / stepCount) * stepCount;
      scrollHorizontallyToPage(playheadPage);
    }
  }

  private void updateScrollBarTooltip() {
    if (vertScrollBar != null && context.getViewMode() == SwingGridPanel.GridViewMode.CLIP) {
      try {
        int rowsInView = context.getRowsInView();
        int voiceRowCount = context.getVoiceRowCount();
        int scrollOffset = context.getScrollOffset();
        int lowestModelRow = scrollOffset + rowsInView - 1;
        int highestModelRow = scrollOffset;

        lowestModelRow = Math.max(0, Math.min(lowestModelRow, voiceRowCount - 1));
        highestModelRow = Math.max(0, Math.min(highestModelRow, voiceRowCount - 1));

        int lowPitch = context.getRowPitch(lowestModelRow);
        String lowNote = org.deluge.model.ScaleMapper.getNoteName(lowPitch);

        int highPitch = context.getRowPitch(highestModelRow);
        String highNote = org.deluge.model.ScaleMapper.getNoteName(highPitch);

        vertScrollBar.setToolTipText(
            "Scroll Pitches (Showing: " + lowNote + " to " + highNote + ")");
      } catch (Throwable t) {
        vertScrollBar.setToolTipText("Scroll Pitches");
      }
    } else if (vertScrollBar != null) {
      vertScrollBar.setToolTipText("Scroll Pitches");
    }
  }
}
