package org.deluge.ui;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;

/**
 * Coordinates gestures for Deluge grid pads, translating raw mouse events into high-level sequencer
 * actions: short clicks, long clicks, and horizontal click-drag note ties.
 */
public class DelugeGestureCoordinator {

  /** Callback listener interface for gesture events. */
  public interface GestureListener {
    void onStepPressed(int row, int col);

    void onStepReleased(int row, int col);

    void onStepToggled(int row, int col);

    void onStepLongPressed(int row, int col, Point screenPos);

    void onStepTied(int row, int colStart, int colEnd);

    void onDragPreview(int row, int colStart, int colCurrent);

    void onDragCleared();

    // New multi-cell selection support default methods
    default void onDragSelectionStart(int row, int col, boolean isControlOrCmd) {}

    default void onDragSelectionUpdate(int startRow, int startCol, int currRow, int currCol) {}

    default void onDragSelectionFinalize(
        int startRow, int startCol, int currRow, int currCol, boolean isControlOrCmd) {}

    default void onStepCtrlClicked(int row, int col) {}

    default boolean hasMultiSelection() {
      return false;
    }

    default void clearMultiSelection() {}
  }

  private final Component parentGridPanel;
  private final GestureListener listener;

  private int dragStartRow = -1;
  private int dragStartCol = -1;
  private int dragCurrentRow = -1;
  private int dragCurrentCol = -1;
  private boolean isDragging = false;
  private boolean dragActive = false;
  private Point pressPoint = null;

  private boolean isAltCloning = false;
  private int cloneStartRow = -1;
  private int cloneStartCol = -1;
  private int cloneCurrentRow = -1;
  private int cloneCurrentCol = -1;

  private javax.swing.Timer autoScrollTimer = null;
  private int autoScrollDirection = 0; // 1 = RIGHT, -1 = LEFT

  public DelugeGestureCoordinator(Component parentGridPanel, GestureListener listener) {
    this.parentGridPanel = parentGridPanel;
    this.listener = listener;

    this.autoScrollTimer =
        new javax.swing.Timer(
            150,
            e -> {
              if (parentGridPanel instanceof SwingGridPanel sg && isDragging) {
                int scrollAmount = autoScrollDirection * 4;
                int oldOffset = sg.getScrollOffsetX();
                sg.scrollHorizontally(scrollAmount);
                int actualDiff = sg.getScrollOffsetX() - oldOffset;

                if (actualDiff != 0) {
                  dragCurrentCol += actualDiff;
                  listener.onDragSelectionUpdate(
                      dragStartRow, dragStartCol, dragCurrentRow, dragCurrentCol);
                  if (dragCurrentRow == dragStartRow) {
                    listener.onDragPreview(dragStartRow, dragStartCol, dragCurrentCol);
                  }
                }
              }
            });
  }

  /** Creates a MouseAdapter (for click and drag events) configured for a specific pad. */
  public MouseAdapter createMouseAdapter(final int row, final int col) {
    return new MouseAdapter() {
      @Override
      public void mouseEntered(MouseEvent e) {
        if (parentGridPanel instanceof SwingGridPanel sg && sg.isShiftHeld()) {
          sg.handleShiftHover(row, col);
        }
      }

      @Override
      public void mousePressed(MouseEvent e) {
        if (parentGridPanel instanceof SwingGridPanel sg && sg.isShiftHeld()) {
          sg.handleShiftClick(row, col, e.getPoint(), e.getComponent());
          return;
        }

        if (SwingUtilities.isRightMouseButton(e)) {
          // Right click immediately opens the popup settings menu
          listener.onStepLongPressed(row, col, e.getLocationOnScreen());
          return;
        }

        if (e.isAltDown()) {
          isAltCloning = true;
          cloneStartRow = row;
          cloneStartCol = col;
          cloneCurrentRow = row;
          cloneCurrentCol = col;
          if (parentGridPanel instanceof SwingGridPanel sg) {
            sg.setClonePreview(cloneStartRow, cloneStartCol, cloneCurrentRow, cloneCurrentCol);
          }
          return;
        }

        dragStartRow = row;
        dragStartCol = col;
        dragCurrentRow = row;
        dragCurrentCol = col;
        dragActive = false;
        pressPoint = e.getLocationOnScreen();
        isDragging = true;
        // Trigger note preview on press
        listener.onStepPressed(row, col);
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        if (isAltCloning) {
          Point parentPt =
              SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parentGridPanel);
          Component under = getDeepestComponentAt(parentGridPanel, parentPt);
          if (under instanceof javax.swing.JComponent jc) {
            Integer targetRow = (Integer) jc.getClientProperty("row");
            Integer targetCol = (Integer) jc.getClientProperty("col");
            if (targetRow != null && targetCol != null) {
              if (targetRow != cloneCurrentRow || targetCol != cloneCurrentCol) {
                cloneCurrentRow = targetRow;
                cloneCurrentCol = targetCol;
                if (parentGridPanel instanceof SwingGridPanel sg) {
                  sg.setClonePreview(
                      cloneStartRow, cloneStartCol, cloneCurrentRow, cloneCurrentCol);
                }
              }
            }
          }
          return;
        }

        if (!isDragging || SwingUtilities.isRightMouseButton(e)) return;

        if (!dragActive && pressPoint != null) {
          double dist = e.getLocationOnScreen().distance(pressPoint);
          if (dist > 8) {
            dragActive = true;
          }
        }

        if (dragActive) {
          Point parentPt =
              SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parentGridPanel);
          Component under = getDeepestComponentAt(parentGridPanel, parentPt);
          if (under instanceof javax.swing.JComponent jc) {
            Integer targetRow = (Integer) jc.getClientProperty("row");
            Integer targetCol = (Integer) jc.getClientProperty("col");
            int maxCol = 192;
            if (parentGridPanel instanceof SwingGridPanel sg) {
              maxCol = sg.getStepCount();
            }
            if (targetRow != null && targetCol != null && targetCol < maxCol) {
              if (targetRow != dragCurrentRow || targetCol != dragCurrentCol) {
                dragCurrentRow = targetRow;
                dragCurrentCol = targetCol;
                if (dragCurrentRow == dragStartRow) {
                  listener.onDragPreview(dragStartRow, dragStartCol, dragCurrentCol);
                } else {
                  listener.onDragSelectionUpdate(
                      dragStartRow, dragStartCol, dragCurrentRow, dragCurrentCol);
                }
              }
            }
          }
        }

        // ── Smooth auto-scrolling bounds check when dragging near panels borders ──
        if (dragActive && parentGridPanel.getWidth() > 140) {
          Point parentPt =
              SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parentGridPanel);
          if (parentPt.x >= parentGridPanel.getWidth() - 30) {
            autoScrollDirection = 1;
            if (!autoScrollTimer.isRunning()) {
              autoScrollTimer.start();
            }
          } else if (parentPt.x <= 130) {
            autoScrollDirection = -1;
            if (!autoScrollTimer.isRunning()) {
              autoScrollTimer.start();
            }
          } else {
            autoScrollTimer.stop();
          }
        }
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        if (isAltCloning) {
          isAltCloning = false;
          if (parentGridPanel instanceof SwingGridPanel sg) {
            sg.setClonePreview(-1, -1, -1, -1);
            Point parentPt =
                SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parentGridPanel);
            Component under = getDeepestComponentAt(parentGridPanel, parentPt);
            if (under instanceof javax.swing.JComponent jc) {
              Integer targetRow = (Integer) jc.getClientProperty("row");
              Integer targetCol = (Integer) jc.getClientProperty("col");
              if (targetRow != null && targetCol != null) {
                sg.duplicateStep(cloneStartRow, cloneStartCol, targetRow, targetCol);
              }
            }
          }
          return;
        }

        // Release note preview and stop drag auto-scroller
        listener.onStepReleased(row, col);
        if (autoScrollTimer != null) {
          autoScrollTimer.stop();
        }

        if (!isDragging || SwingUtilities.isRightMouseButton(e)) {
          isDragging = false;
          dragActive = false;
          return;
        }

        isDragging = false;

        if (dragActive) {
          dragActive = false;
          if (dragCurrentRow == dragStartRow) {
            listener.onStepTied(dragStartRow, dragStartCol, dragCurrentCol);
          } else {
            listener.onDragSelectionFinalize(
                dragStartRow,
                dragStartCol,
                dragCurrentRow,
                dragCurrentCol,
                e.isControlDown() || e.isMetaDown());
          }
        } else {
          // Normal tap (short click)
          boolean ctrlOrCmd = e.isControlDown() || e.isMetaDown();
          if (ctrlOrCmd) {
            listener.onStepCtrlClicked(row, col);
          } else {
            if (listener.hasMultiSelection()) {
              listener.clearMultiSelection();
            }
            listener.onStepToggled(row, col);
          }
        }
      }

      @Override
      public void mouseExited(MouseEvent e) {
        if (parentGridPanel instanceof SwingGridPanel sg && sg.isShiftHeld()) {
          sg.handleShiftHoverExit();
        } else {
          // Release note preview if cursor leaves button bounds (but not during active multi-cell
          // drag select)
          if (!dragActive) {
            listener.onStepReleased(row, col);
          }
        }
      }
    };
  }

  /** Recursively finds the deepest JComponent at a specific relative coordinate path */
  private Component getDeepestComponentAt(Component parent, Point pt) {
    if (pt.x < 0 || pt.x >= parent.getWidth() || pt.y < 0 || pt.y >= parent.getHeight()) {
      return parent;
    }
    Component child = parent.getComponentAt(pt);
    if (child == null || child == parent) {
      return parent;
    }
    Point childPt = new Point(pt.x - child.getX(), pt.y - child.getY());
    return getDeepestComponentAt(child, childPt);
  }
}
