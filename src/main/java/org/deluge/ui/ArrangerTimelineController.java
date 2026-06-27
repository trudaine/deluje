package org.deluge.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.deluge.model.ArrangerClip;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;

/**
 * Controller that encapsulates all Arranger Timeline interaction, drag-and-drop, clip resizing, and
 * context menu logic, keeping the main SwingGridPanel clean.
 */
public class ArrangerTimelineController {
  private final SwingGridPanel parent;
  private final Runnable projectChangedCallback;
  private final Runnable refreshCallback;

  // Drag-and-drop state
  private ArrangerClip dragArrangerClip = null;
  private int dragArrangerStartTicks = -1;
  private int dragArrangerDurationTicks = -1;
  private boolean isResizingArranger = false;
  private int dragArrangerStartCol = -1;

  public ArrangerTimelineController(
      SwingGridPanel parent, Runnable projectChangedCallback, Runnable refreshCallback) {
    this.parent = parent;
    this.projectChangedCallback = projectChangedCallback;
    this.refreshCallback = refreshCallback;
  }

  private ProjectModel getProjectModel() {
    return parent.getProjectModel();
  }

  /** Finds the arranger clip placement at the specified track and column (96 ticks per column). */
  public ArrangerClip getArrangerClipAt(int trackIndex, int col) {
    ProjectModel projectModel = getProjectModel();
    if (projectModel == null) return null;
    int queryTicks = col * 96;
    for (ArrangerClip placement : projectModel.getArrangerTimeline()) {
      if (placement.trackIndex() == trackIndex) {
        if (queryTicks >= placement.startTicks()
            && queryTicks < placement.startTicks() + placement.durationTicks()) {
          return placement;
        }
      }
    }
    return null;
  }

  /** Attaches mouse and motion listeners to a pad button for arranger timeline interaction. */
  public void attachListeners(final JButton clipBtn, final int currentTrack, final int colId) {
    clipBtn.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            ProjectModel projectModel = getProjectModel();
            if (SwingUtilities.isRightMouseButton(e)) {
              ArrangerClip placement = getArrangerClipAt(currentTrack, colId);
              if (placement != null && projectModel != null) {
                projectModel.getArrangerTimeline().remove(placement);
                projectChangedCallback.run();
                refreshCallback.run();
              }
              return;
            }

            ArrangerClip placement = getArrangerClipAt(currentTrack, colId);
            if (e.getClickCount() == 2) {
              if (placement != null && projectModel != null) {
                projectModel.getArrangerTimeline().remove(placement);
                projectChangedCallback.run();
                refreshCallback.run();
              }
              return;
            }

            if (placement != null) {
              dragArrangerClip = placement;
              dragArrangerStartTicks = placement.startTicks();
              dragArrangerDurationTicks = placement.durationTicks();
              dragArrangerStartCol = colId;
              isResizingArranger = e.isShiftDown();
            } else {
              showArrangerClipSelectionPopup(clipBtn, currentTrack, colId);
            }
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            dragArrangerClip = null;
            dragArrangerStartTicks = -1;
            dragArrangerDurationTicks = -1;
            isResizingArranger = false;
            dragArrangerStartCol = -1;
            projectChangedCallback.run();
            refreshCallback.run();
          }
        });

    clipBtn.addMouseMotionListener(
        new MouseMotionAdapter() {
          @Override
          public void mouseDragged(MouseEvent e) {
            ProjectModel projectModel = getProjectModel();
            if (dragArrangerClip == null || projectModel == null) return;

            Point pt = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parent);
            int currCol = colId;
            Component under = parent.getComponentAt(pt);
            if (under instanceof JPanel rowPanel) {
              Component deepest =
                  rowPanel.getComponentAt(
                      new Point(pt.x - rowPanel.getX(), pt.y - rowPanel.getY()));
              if (deepest instanceof javax.swing.JComponent jc) {
                Integer col = (Integer) jc.getClientProperty("col");
                if (col != null) currCol = col;
              }
            }

            int colDiff = currCol - dragArrangerStartCol;
            if (colDiff != 0) {
              if (isResizingArranger) {
                int newDurationTicks = Math.max(96, dragArrangerDurationTicks + colDiff * 96);
                projectModel.getArrangerTimeline().remove(dragArrangerClip);
                ArrangerClip updated =
                    new ArrangerClip(
                        currentTrack,
                        dragArrangerClip.clip(),
                        dragArrangerClip.startTicks(),
                        newDurationTicks);
                projectModel.addArrangerClip(updated);
                dragArrangerClip = updated;
                dragArrangerStartCol = currCol;
                dragArrangerDurationTicks = newDurationTicks;
                refreshCallback.run();
              } else {
                int newStartTicks = Math.max(0, dragArrangerStartTicks + colDiff * 96);
                projectModel.getArrangerTimeline().remove(dragArrangerClip);
                ArrangerClip updated =
                    new ArrangerClip(
                        currentTrack,
                        dragArrangerClip.clip(),
                        newStartTicks,
                        dragArrangerClip.durationTicks());
                projectModel.addArrangerClip(updated);
                dragArrangerClip = updated;
                dragArrangerStartCol = currCol;
                dragArrangerStartTicks = newStartTicks;
                refreshCallback.run();
              }
            }
          }
        });
  }

  /** Shows a popup menu to select or create a clip to place at the specified timeline slot. */
  public void showArrangerClipSelectionPopup(Component invoker, final int trackIdx, final int col) {
    ProjectModel projectModel = getProjectModel();
    if (projectModel == null || trackIdx >= projectModel.getTracks().size()) return;
    TrackModel track = projectModel.getTracks().get(trackIdx);

    JPopupMenu menu = new JPopupMenu();
    menu.setBackground(new Color(0x1e, 0x1e, 0x22));
    menu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));

    JMenuItem createNew = new JMenuItem("Create New Pattern Clip (1 bar)");
    createNew.setForeground(new Color(0x00, 0xff, 0xcc));
    createNew.setBackground(new Color(0x1e, 0x1e, 0x22));
    createNew.addActionListener(
        e -> {
          int clipCount = track.getClips().size();
          ClipModel newClip = new ClipModel("CLIP " + (clipCount + 1), 8, 16);
          track.addClip(newClip);
          projectModel.addArrangerClip(new ArrangerClip(trackIdx, newClip, col * 96, 96));
          projectChangedCallback.run();
          refreshCallback.run();
        });
    menu.add(createNew);

    if (!track.getClips().isEmpty()) {
      menu.addSeparator();
      for (int i = 0; i < track.getClips().size(); i++) {
        final ClipModel clip = track.getClips().get(i);
        String name =
            clip.getName() != null && !clip.getName().isBlank()
                ? clip.getName()
                : "Pattern Clip " + (i + 1);
        JMenuItem item = new JMenuItem("Place: " + name);
        item.setForeground(Color.WHITE);
        item.setBackground(new Color(0x1e, 0x1e, 0x22));
        item.addActionListener(
            e -> {
              projectModel.addArrangerClip(new ArrangerClip(trackIdx, clip, col * 96, 96));
              projectChangedCallback.run();
              refreshCallback.run();
            });
        menu.add(item);
      }
    }

    menu.show(invoker, 0, invoker.getHeight());
  }
}
