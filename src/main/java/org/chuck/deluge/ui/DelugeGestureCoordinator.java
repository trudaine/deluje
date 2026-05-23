package org.chuck.deluge.ui;

import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

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
  }

  private final Component parentGridPanel;
  private final GestureListener listener;

  private int dragStartRow = -1;
  private int dragStartCol = -1;
  private int dragCurrentCol = -1;
  private boolean isDragging = false;
  private boolean longPressFired = false;
  private Timer longPressTimer;
  private boolean isAltCloning = false;
  private int cloneStartRow = -1;
  private int cloneStartCol = -1;
  private int cloneCurrentRow = -1;
  private int cloneCurrentCol = -1;

  public DelugeGestureCoordinator(Component parentGridPanel, GestureListener listener) {
    this.parentGridPanel = parentGridPanel;
    this.listener = listener;
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
        dragCurrentCol = col;
        isDragging = true;
        longPressFired = false;

        // Trigger note preview on press
        listener.onStepPressed(row, col);

        // Start long-press timer (500 ms threshold)
        if (longPressTimer != null) {
          longPressTimer.stop();
        }
        longPressTimer =
            new Timer(
                500,
                ev -> {
                  if (isDragging && dragCurrentCol == dragStartCol) {
                    longPressFired = true;
                    listener.onStepLongPressed(row, col, e.getLocationOnScreen());
                  }
                });
        longPressTimer.setRepeats(false);
        longPressTimer.start();
      }

      @Override
      public void mouseDragged(MouseEvent e) {
        if (isAltCloning) {
          Point parentPt =
              SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parentGridPanel);
          Component under = parentGridPanel.getComponentAt(parentPt);
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

        // Resolve component under current drag coordinates by converting to parent coordinate space
        Point parentPt =
            SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parentGridPanel);
        Component under = parentGridPanel.getComponentAt(parentPt);

        if (under instanceof javax.swing.JComponent jc) {
          Integer targetRow = (Integer) jc.getClientProperty("row");
          Integer targetCol = (Integer) jc.getClientProperty("col");
          if (targetRow != null && targetCol != null) {
            if (targetRow == dragStartRow) {
              if (targetCol != dragCurrentCol) {
                dragCurrentCol = targetCol;

                // Cancel long-press timer once cursor leaves the original starting column
                if (longPressTimer != null) {
                  longPressTimer.stop();
                }
                listener.onDragPreview(dragStartRow, dragStartCol, dragCurrentCol);
              }
            }
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
            Component under = parentGridPanel.getComponentAt(parentPt);
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

        if (longPressTimer != null) {
          longPressTimer.stop();
        }

        // Release note preview
        listener.onStepReleased(row, col);

        if (!isDragging || SwingUtilities.isRightMouseButton(e)) {
          isDragging = false;
          return;
        }

        isDragging = false;
        listener.onDragCleared();

        if (longPressFired) {
          return;
        }

        if (dragCurrentCol > dragStartCol) {
          listener.onStepTied(dragStartRow, dragStartCol, dragCurrentCol);
        } else if (dragCurrentCol < dragStartCol) {
          // Backward drag, tie left-to-right
          listener.onStepTied(dragStartRow, dragCurrentCol, dragStartCol);
        } else {
          // Normal tap (short click)
          listener.onStepToggled(row, col);
        }
      }

      @Override
      public void mouseExited(MouseEvent e) {
        if (parentGridPanel instanceof SwingGridPanel sg && sg.isShiftHeld()) {
          sg.handleShiftHoverExit();
        } else {
          // Release note preview if cursor leaves button bounds
          listener.onStepReleased(row, col);
        }
      }
    };
  }
}
