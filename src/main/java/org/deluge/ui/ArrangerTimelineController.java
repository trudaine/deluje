package org.deluge.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.JButton;
import javax.swing.JMenuItem;
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

  /** Ticks represented by one arranger column = the song's stored arrangement zoom (xZoom). */
  public int arrangerTicksPerColumn() {
    ProjectModel projectModel = getProjectModel();
    int zoom = projectModel != null ? projectModel.getXZoomArrangementView() : 0;
    return zoom > 0 ? zoom : 96;
  }

  /**
   * Unclamped 64-bit tick position of the left edge of arranger column {@code col} (incl.
   * horizontal scroll) so rendering and hit-testing near {@link Integer#MAX_VALUE} never overflow
   * signed 32-bit arithmetic.
   */
  public long arrangerTickForColumnLong(int col) {
    ProjectModel projectModel = getProjectModel();
    long scroll = projectModel != null ? projectModel.getXScrollArrangementView() : 0L;
    return scroll + (long) col * arrangerTicksPerColumn();
  }

  /**
   * Absolute tick position of the left edge of arranger column {@code col} (incl. horizontal
   * scroll), clamped to {@code [0, Integer.MAX_VALUE]} for {@code int}-typed model constructors.
   */
  public int arrangerTickForColumn(int col) {
    long tick = arrangerTickForColumnLong(col);
    return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, tick));
  }

  /**
   * Finds the arranger clip placement at the specified track and column (song arrangement zoom).
   * Delegates directly to {@link ArrangementProjector#clipAt} ({@code arranger_view.cpp:2348-2389})
   * for identical {@code long} bounds and latest-{@code startTicks} tie-breaking.
   */
  public ArrangerClip getArrangerClipAt(int trackIndex, int col) {
    ProjectModel projectModel = getProjectModel();
    if (projectModel == null) return null;
    return ArrangementProjector.clipAt(
        projectModel.getArrangerTimeline(),
        trackIndex,
        arrangerTickForColumnLong(col),
        arrangerTicksPerColumn());
  }

  /** Attaches mouse and motion listeners to a pad button for arranger timeline interaction. */
  public void attachListeners(final JButton clipBtn, final int currentTrack, final int colId) {
    clipBtn.putClientProperty("col", colId);
    clipBtn.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            ProjectModel projectModel = getProjectModel();
            if (SwingUtilities.isRightMouseButton(e)) {
              showArrangerCellContextMenu(clipBtn, currentTrack, colId, e.getX(), e.getY());
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
            Component deepest = SwingUtilities.getDeepestComponentAt(parent, pt.x, pt.y);
            while (deepest != null && deepest != parent) {
              if (deepest instanceof javax.swing.JComponent jc) {
                Integer col = (Integer) jc.getClientProperty("col");
                if (col != null) {
                  currCol = col;
                  break;
                }
              }
              deepest = deepest.getParent();
            }

            int colDiff = currCol - dragArrangerStartCol;
            if (colDiff != 0) {
              int ticksPerCol = arrangerTicksPerColumn();
              if (isResizingArranger) {
                int newDurationTicks =
                    Math.max(ticksPerCol, dragArrangerDurationTicks + colDiff * ticksPerCol);
                projectModel.getArrangerTimeline().remove(dragArrangerClip);
                ArrangerClip updated =
                    new ArrangerClip(
                        currentTrack,
                        dragArrangerClip.clip(),
                        dragArrangerClip.audioClip(),
                        dragArrangerClip.startTicks(),
                        newDurationTicks);
                projectModel.addArrangerClip(updated);
                dragArrangerClip = updated;
                dragArrangerStartCol = currCol;
                dragArrangerDurationTicks = newDurationTicks;
                refreshCallback.run();
              } else {
                int newStartTicks = Math.max(0, dragArrangerStartTicks + colDiff * ticksPerCol);
                projectModel.getArrangerTimeline().remove(dragArrangerClip);
                ArrangerClip updated =
                    new ArrangerClip(
                        currentTrack,
                        dragArrangerClip.clip(),
                        dragArrangerClip.audioClip(),
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

  public void showArrangerClipSelectionPopup(Component invoker, final int trackIdx, final int col) {
    JPopupMenu menu = buildArrangerClipSelectionPopup(trackIdx, col);
    if (menu != null) {
      menu.show(invoker, 0, invoker.getHeight());
    }
  }

  JPopupMenu buildArrangerClipSelectionPopup(final int trackIdx, final int col) {
    ProjectModel projectModel = getProjectModel();
    if (projectModel == null || trackIdx >= projectModel.getTracks().size()) return null;
    TrackModel track = projectModel.getTracks().get(trackIdx);

    JPopupMenu menu = new JPopupMenu();

    JMenuItem createNew = null;
    if (!(track instanceof org.deluge.model.AudioTrackModel)) {
      createNew = new JMenuItem("Create New Pattern Clip (1 bar)");
      createNew.addActionListener(
          e -> {
            int clipCount = track.getClips().size();
            ClipModel newClip = new ClipModel("CLIP " + (clipCount + 1), 8, 16);
            track.addClip(newClip);
            int startTicks = arrangerTickForColumn(col);
            projectModel.addArrangerClip(
                new ArrangerClip(trackIdx, newClip, startTicks, newClip.getLoopLength()));
            projectChangedCallback.run();
            refreshCallback.run();
          });
      menu.add(createNew);
    }

    if (track instanceof org.deluge.model.AudioTrackModel audioTrack
        && !audioTrack.getAudioClips().isEmpty()) {
      if (menu.getComponentCount() > 0) {
        menu.addSeparator();
      }
      for (int i = 0; i < audioTrack.getAudioClips().size(); i++) {
        final org.deluge.model.AudioTrackModel.AudioClip audioClip =
            audioTrack.getAudioClips().get(i);
        String name =
            audioClip.getClipName() != null && !audioClip.getClipName().isBlank()
                ? audioClip.getClipName()
                : "Audio Clip " + (i + 1);
        JMenuItem item = new JMenuItem("Place: " + name);
        item.addActionListener(
            e -> {
              int startTicks = arrangerTickForColumn(col);
              projectModel.addArrangerClip(
                  new ArrangerClip(
                      trackIdx, null, audioClip, startTicks, Math.max(1, audioClip.getLength())));
              projectChangedCallback.run();
              refreshCallback.run();
            });
        menu.add(item);
      }
    } else if (!track.getClips().isEmpty()) {
      menu.addSeparator();
      for (int i = 0; i < track.getClips().size(); i++) {
        final ClipModel clip = track.getClips().get(i);
        String name =
            clip.getName() != null && !clip.getName().isBlank()
                ? clip.getName()
                : "Pattern Clip " + (i + 1);
        JMenuItem item = new JMenuItem("Place: " + name);
        item.addActionListener(
            e -> {
              int startTicks = arrangerTickForColumn(col);
              projectModel.addArrangerClip(
                  new ArrangerClip(trackIdx, clip, startTicks, clip.getLoopLength()));
              projectChangedCallback.run();
              refreshCallback.run();
            });
        menu.add(item);
      }
    }

    SwingGridPanel.stylePopupMenu(menu);
    if (createNew != null) {
      createNew.setForeground(new Color(0x00, 0xff, 0xcc));
    }
    return menu;
  }

  public void showArrangerCellContextMenu(
      Component invoker, final int trackIdx, final int col, int x, int y) {
    ProjectModel projectModel = getProjectModel();
    if (projectModel == null || trackIdx >= projectModel.getTracks().size()) return;
    TrackModel track = projectModel.getTracks().get(trackIdx);
    ArrangerClip placement = getArrangerClipAt(trackIdx, col);

    JPopupMenu menu = new JPopupMenu();

    if (placement != null) {
      JMenuItem deleteItem = new JMenuItem("Delete Arranged Clip");
      deleteItem.addActionListener(
          e -> {
            projectModel.getArrangerTimeline().remove(placement);
            projectChangedCallback.run();
            refreshCallback.run();
          });
      menu.add(deleteItem);

      JMenuItem dupeItem = new JMenuItem("Duplicate Arranged Clip");
      dupeItem.addActionListener(
          e -> {
            int nextStart = placement.startTicks() + placement.durationTicks();
            projectModel.addArrangerClip(
                new ArrangerClip(
                    trackIdx,
                    placement.clip(),
                    placement.audioClip(),
                    nextStart,
                    placement.durationTicks()));
            projectChangedCallback.run();
            refreshCallback.run();
          });
      menu.add(dupeItem);

      menu.addSeparator();
    } else {
      JMenuItem placeNew = new JMenuItem("Add Arranged Clip...");
      placeNew.addActionListener(
          e -> {
            showArrangerClipSelectionPopup(invoker, trackIdx, col);
          });
      menu.add(placeNew);
    }

    JMenuItem editAuto = new JMenuItem("Edit Bar Automation...");
    editAuto.addActionListener(
        ev -> {
          int barIndex = col + parent.getScrollOffsetX();
          BarAutomationDialog dlg =
              new BarAutomationDialog(
                  (java.awt.Frame) javax.swing.SwingUtilities.getWindowAncestor(invoker), barIndex);
          dlg.setVisible(true); // modal
          if (!dlg.isConfirmed()) {
            return;
          }
          // Apply the chosen automations onto the clip arranged at this bar. (The dialog used
          // to be pure decoration: no buttons, and nothing ever read its checkboxes.)
          org.deluge.model.ArrangerClip ac = getArrangerClipAt(trackIdx, col);
          if (ac == null || ac.clip() == null) {
            javax.swing.JOptionPane.showMessageDialog(
                invoker,
                "No arranged clip at bar " + (barIndex + 1) + " — place a clip there first.",
                "Bar Automation",
                javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
          }
          org.deluge.model.ClipModel clip = ac.clip();
          int steps = Math.max(1, clip.getStepCount());
          for (int s = 0; s < steps; s++) {
            float ramp = steps > 1 ? (float) s / (steps - 1) : 1f;
            if (dlg.isLpfSweepEnabled()) {
              clip.setAutomation(org.deluge.model.AutomationParam.A_LPF_FREQ, s, 1f - ramp);
            }
            if (dlg.isVolumeFadeInEnabled()) {
              clip.setAutomation(org.deluge.model.AutomationParam.A_VOLUME, s, ramp);
            }
          }
          if (dlg.isLpfSweepEnabled() || dlg.isVolumeFadeInEnabled()) {
            projectChangedCallback.run();
            refreshCallback.run();
          }
        });
    menu.add(editAuto);

    SwingGridPanel.stylePopupMenu(menu);

    for (java.awt.Component comp : menu.getComponents()) {
      if (comp instanceof JMenuItem mi) {
        if ("Delete Arranged Clip".equals(mi.getText())) {
          mi.setForeground(Color.RED);
        } else if ("Add Arranged Clip...".equals(mi.getText())) {
          mi.setForeground(new Color(0x00, 0xff, 0xcc));
        }
      }
    }

    menu.show(invoker, x, y);
  }
}
