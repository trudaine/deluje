package org.deluge.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import javax.swing.*;
import org.deluge.model.AutomationParam;
import org.deluge.model.ClipModel;
import org.deluge.model.Consequence;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;

/**
 * Controller that encapsulates all Automation Editing and Overview grid logic, parameter scrolling,
 * drag-to-paint automation values, and interpolation, keeping the main SwingGridPanel clean.
 */
public class AutomationEditorController {
  private final SwingGridPanel parent;
  private final Runnable refreshCallback;

  // Automation state
  private boolean autoOverviewMode = true; // true=overview grid, false=detail editor
  private String selectedAutomationParam = AutomationParam.SYTH_PARAMS[0];
  private int autoColScroll = 0; // horizontal scroll for overview param cols

  // Drag-to-paint state
  private boolean automationDragging = false;
  private String autoDragParam;
  private int autoDragStep = -1;
  private float autoDragOldValue = -1f;

  public AutomationEditorController(SwingGridPanel parent, Runnable refreshCallback) {
    this.parent = parent;
    this.refreshCallback = refreshCallback;
  }

  public boolean isOverviewMode() {
    return autoOverviewMode;
  }

  public void setOverviewMode(boolean overview) {
    this.autoOverviewMode = overview;
  }

  public String getSelectedParam() {
    return selectedAutomationParam;
  }

  public void setSelectedParam(String param) {
    this.selectedAutomationParam = param;
  }

  private ProjectModel getProjectModel() {
    return parent.getProjectModel();
  }

  private int getEditedModelTrack() {
    return parent.getEditedModelTrack();
  }

  private int getStepCount() {
    return parent.getStepCount();
  }

  /** Builds either the overview grid or the detailed parameter editor based on the current mode. */
  public void buildAutomationView(JPanel container, ClipModel autoClip, int padSz) {
    if (autoOverviewMode) {
      buildAutomationOverview(container, autoClip, padSz);
    } else {
      buildAutomationEditor(container, autoClip, selectedAutomationParam, padSz);
    }
  }

  /** Builds the 8-row value-band editor for a single parameter. */
  private void buildAutomationEditor(
      JPanel container, ClipModel autoClip, String param, int padSz) {
    if (param == null) param = AutomationParam.SYTH_PARAMS[0];
    final String finalParam = param;
    int stepCount = getStepCount();

    // Step number header row
    JPanel stepHeader = new JPanel();
    stepHeader.setLayout(new BoxLayout(stepHeader, BoxLayout.X_AXIS));
    stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
    stepHeader.setMaximumSize(new Dimension(3000, 20));
    int topLw = parent.currentLabelWidth();
    stepHeader.add(Box.createRigidArea(new Dimension(topLw + 91, 20)));
    for (int c = 0; c < stepCount; c++) {
      JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
      stepNum.setPreferredSize(new Dimension(padSz, 18));
      stepNum.setMinimumSize(new Dimension(padSz, 18));
      stepNum.setMaximumSize(new Dimension(padSz, 18));
      stepNum.setForeground(Color.GRAY);
      stepNum.setFont(new Font("Monospaced", Font.PLAIN, 10));
      stepHeader.add(stepNum);
    }
    container.add(stepHeader);

    String[] bandLabels = {
      "0-15", "16-31", "32-47", "48-63", "64-79", "80-95", "96-111", "112-127"
    };

    for (int r = 0; r < 8; r++) {
      final int rowIdx = r;
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      JLabel valLabel = new JLabel(bandLabels[r]);
      int lw = parent.currentLabelWidth();
      valLabel.setPreferredSize(new Dimension(lw, 30));
      valLabel.setMinimumSize(new Dimension(lw, 30));
      valLabel.setMaximumSize(new Dimension(lw, 30));
      valLabel.setForeground(new Color(0x88, 0x88, 0x88));
      valLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
      rowPanel.add(valLabel);

      rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
      rowPanel.add(Box.createHorizontalStrut(5));

      // Spacer VU panel
      VUMeterPanel vu = new VUMeterPanel();
      vu.setPreferredSize(new Dimension(12, padSz));
      vu.setMaximumSize(new Dimension(12, padSz));
      rowPanel.add(vu);
      rowPanel.add(Box.createHorizontalStrut(5));

      for (int c = 0; c < stepCount; c++) {
        final int colIdx = c;
        DelugePadButton cell = new DelugePadButton();
        cell.setPreferredSize(new Dimension(padSz, padSz));
        cell.setMinimumSize(new Dimension(padSz, padSz));
        cell.setMaximumSize(new Dimension(padSz, padSz));
        cell.setMargin(new Insets(0, 0, 0, 0));
        cell.setDrawCenterCircle(false);

        cell.putClientProperty("row", r);
        cell.putClientProperty("col", c);
        parent.setPad(r, c, cell); // Register pad with parent

        boolean lit = false;
        float autoVal = -1f;
        if (autoClip != null) {
          autoVal = autoClip.getAutomation(finalParam, colIdx);
          if (autoVal >= 0f) {
            int band = (int) (autoVal * 127f) / 16;
            lit = (band == rowIdx);
          }
        }

        if (lit) {
          int precise = (int) (autoVal * 127f) % 16;
          int bright = 0x55 + precise * 8;
          cell.setBaseColor(new Color(0x00, bright, Math.min(0xcc, bright / 2 + 0x44)));
          cell.setIntensity(1.0f);
          cell.setActive(true);
          cell.setNoteText("\u25CF");
        } else {
          cell.setBaseColor(new Color(0x1d, 0x1d, 0x22));
          cell.setIntensity(0.15f);
          cell.setActive(false);
          if (autoVal >= 0f) {
            cell.setNoteText(".");
          } else {
            cell.setNoteText("");
          }
        }

        cell.addMouseListener(
            new MouseAdapter() {
              @Override
              public void mousePressed(MouseEvent e) {
                ProjectModel projectModel = getProjectModel();
                if (projectModel == null) return;
                int tIdx = getEditedModelTrack();
                if (tIdx >= projectModel.getTracks().size()) return;
                TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                ClipModel cM = tM.getClips().get(acIdx2);

                if (SwingUtilities.isRightMouseButton(e)) {
                  showAutomationCellPopupMenu(cell, e.getX(), e.getY(), cM, finalParam, colIdx);
                  return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                  if (e.isShiftDown()) {
                    float oldVal = cM.getAutomation(finalParam, colIdx);
                    float[] arr = cM.getAutomationArray(finalParam);
                    if (arr != null && colIdx < arr.length) {
                      arr[colIdx] = -1f;
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.AutomationConsequence(
                                  projectModel, tIdx, acIdx2, finalParam, colIdx, oldVal, -1f));
                      refreshCallback.run();
                    }
                  } else {
                    float oldVal = cM.getAutomation(finalParam, colIdx);
                    float val = (rowIdx * 16 + 8) / 127.0f;
                    cM.setAutomation(finalParam, colIdx, val);
                    autoDragParam = finalParam;
                    autoDragStep = colIdx;
                    autoDragOldValue = oldVal;
                    automationDragging = true;
                    refreshCallback.run();
                  }
                }
              }

              @Override
              public void mouseReleased(MouseEvent e) {
                ProjectModel projectModel = getProjectModel();
                if (automationDragging
                    && projectModel != null
                    && autoDragParam != null
                    && autoDragStep >= 0) {
                  int tIdx = getEditedModelTrack();
                  if (tIdx < projectModel.getTracks().size()) {
                    TrackModel tM = projectModel.getTracks().get(tIdx);
                    int acIdx2 = tM.getActiveClipIndex();
                    if (acIdx2 >= 0 && acIdx2 < tM.getClips().size()) {
                      ClipModel cM = tM.getClips().get(acIdx2);
                      float newVal = cM.getAutomation(autoDragParam, autoDragStep);
                      if (newVal != autoDragOldValue) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.AutomationConsequence(
                                    projectModel,
                                    tIdx,
                                    acIdx2,
                                    autoDragParam,
                                    autoDragStep,
                                    autoDragOldValue,
                                    newVal));
                      }
                    }
                  }
                  autoDragParam = null;
                  autoDragStep = -1;
                  autoDragOldValue = -1f;
                }
                automationDragging = false;
              }
            });

        // Drag-to-paint listener for drawing automation curves
        cell.addMouseMotionListener(
            new MouseMotionAdapter() {
              @Override
              public void mouseDragged(MouseEvent e) {
                ProjectModel projectModel = getProjectModel();
                if (!automationDragging || projectModel == null) return;
                int tIdx = getEditedModelTrack();
                if (tIdx >= projectModel.getTracks().size()) return;
                TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                ClipModel cM = tM.getClips().get(acIdx2);

                Point pt = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), parent);
                Component under = parent.getComponentAt(pt);
                if (under instanceof JPanel rowPanel) {
                  Component deepest =
                      rowPanel.getComponentAt(
                          new Point(pt.x - rowPanel.getX(), pt.y - rowPanel.getY()));
                  if (deepest instanceof javax.swing.JComponent jc) {
                    Integer rProp = (Integer) jc.getClientProperty("row");
                    Integer cProp = (Integer) jc.getClientProperty("col");
                    if (rProp != null && cProp != null && cProp < stepCount && rProp < 8) {
                      float val = (rProp * 16 + 8) / 127.0f;
                      cM.setAutomation(finalParam, cProp, val);
                      refreshCallback.run();
                    }
                  }
                }
              }
            });

        rowPanel.add(cell);
        rowPanel.add(Box.createHorizontalStrut(3));
      }
      container.add(rowPanel);
    }
  }

  /** Builds the overview grid showing all parameters. */
  private void buildAutomationOverview(JPanel container, ClipModel autoClip, int padSz) {
    String[] allParams = AutomationParam.SYTH_PARAMS;
    int totalParams = allParams.length;
    int stepCount = getStepCount();

    int maxVisible = 8;
    int paramOffset = autoColScroll;
    int visibleParams = Math.min(maxVisible, totalParams - paramOffset);
    if (visibleParams <= 0) return;

    // Step header
    JPanel stepHeader = new JPanel();
    stepHeader.setLayout(new BoxLayout(stepHeader, BoxLayout.X_AXIS));
    stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
    stepHeader.setMaximumSize(new Dimension(3000, 20));
    int topPw = parent.currentLabelWidth();
    stepHeader.add(Box.createRigidArea(new Dimension(topPw + 17, 20)));
    for (int c = 0; c < stepCount; c++) {
      JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
      stepNum.setPreferredSize(new Dimension(padSz, 18));
      stepNum.setMinimumSize(new Dimension(padSz, 18));
      stepNum.setMaximumSize(new Dimension(padSz, 18));
      stepNum.setForeground(Color.GRAY);
      stepNum.setFont(new Font("Monospaced", Font.PLAIN, 10));
      stepHeader.add(stepNum);
    }
    container.add(stepHeader);

    for (int r = 0; r < visibleParams; r++) {
      int paramIdx = paramOffset + r;
      String paramName = allParams[paramIdx];
      String label = AutomationParam.labelFor(paramName);
      final String fParam = paramName;

      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      JButton paramBtn = new JButton(label);
      int pw = parent.currentLabelWidth();
      paramBtn.setPreferredSize(new Dimension(pw, 30));
      paramBtn.setMinimumSize(new Dimension(pw, 30));
      paramBtn.setMaximumSize(new Dimension(pw, 30));
      paramBtn.setMargin(new Insets(0, 2, 0, 2));

      boolean hasAnyAuto = autoClip != null && autoClip.hasAutomation(paramName);
      Color paramBg = hasAnyAuto ? new Color(0x1a, 0x4d, 0x1a) : new Color(0x2d, 0x2d, 0x32);
      Color paramFg = hasAnyAuto ? new Color(0x88, 0xff, 0x88) : Color.LIGHT_GRAY;
      parent.styleSystemButton(paramBtn, paramBg, paramFg, 10);
      paramBtn.setToolTipText(
          "Parameter: Click to edit automation steps / Shift-Click to clear parameter automation");

      paramBtn.addActionListener(
          e -> {
            autoOverviewMode = false;
            selectedAutomationParam = fParam;
            refreshCallback.run();
          });
      rowPanel.add(paramBtn);

      // Up/down scroll buttons
      JPanel scrollCol = new JPanel();
      scrollCol.setLayout(new BoxLayout(scrollCol, BoxLayout.Y_AXIS));
      scrollCol.setBackground(new Color(0x22, 0x22, 0x22));
      JButton upBtn = new JButton("\u25B2");
      upBtn.setMargin(new Insets(0, 0, 0, 0));
      upBtn.setPreferredSize(new Dimension(14, padSz / 2));
      upBtn.setEnabled(paramOffset > 0);
      parent.styleSystemButton(upBtn, new Color(0x2d, 0x2d, 0x32), Color.LIGHT_GRAY, 7);
      upBtn.setToolTipText("Scroll parameter list up");
      upBtn.addActionListener(
          e -> {
            autoColScroll = Math.max(0, autoColScroll - 1);
            refreshCallback.run();
          });
      scrollCol.add(upBtn);

      JButton downBtn = new JButton("\u25BC");
      downBtn.setMargin(new Insets(0, 0, 0, 0));
      downBtn.setPreferredSize(new Dimension(14, padSz / 2));
      downBtn.setEnabled(paramOffset + maxVisible < totalParams);
      parent.styleSystemButton(downBtn, new Color(0x2d, 0x2d, 0x32), Color.LIGHT_GRAY, 7);
      downBtn.setToolTipText("Scroll parameter list down");
      downBtn.addActionListener(
          e -> {
            autoColScroll = Math.min(totalParams - maxVisible, autoColScroll + 1);
            refreshCallback.run();
          });
      scrollCol.add(downBtn);
      rowPanel.add(scrollCol);

      rowPanel.add(Box.createHorizontalStrut(3));

      for (int c = 0; c < stepCount; c++) {
        final int colIdx = c;
        DelugePadButton cell = new DelugePadButton();
        cell.setPreferredSize(new Dimension(padSz, padSz));
        cell.setMinimumSize(new Dimension(padSz, padSz));
        cell.setMaximumSize(new Dimension(padSz, padSz));
        cell.setMargin(new Insets(0, 0, 0, 0));
        cell.setDrawCenterCircle(false);

        parent.setPad(r, c, cell); // Register pad with parent

        boolean hasAuto = autoClip != null && autoClip.hasAutomation(paramName, c);

        if (hasAuto) {
          float val = autoClip.getAutomation(paramName, c);
          int bright = 0x55 + (int) (val * 0xaa);
          cell.setBaseColor(new Color(0x00, bright, 0x66));
          cell.setIntensity(1.0f);
          cell.setActive(true);
          cell.setNoteText("\u25CF");
        } else {
          cell.setBaseColor(new Color(0x1d, 0x1d, 0x22));
          cell.setIntensity(0.15f);
          cell.setActive(false);
          cell.setNoteText("");
        }

        cell.addMouseListener(
            new MouseAdapter() {
              @Override
              public void mousePressed(MouseEvent e) {
                ProjectModel projectModel = getProjectModel();
                if (projectModel == null) return;
                int tIdx = getEditedModelTrack();
                if (tIdx >= projectModel.getTracks().size()) return;
                TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                ClipModel cM = tM.getClips().get(acIdx2);

                if (SwingUtilities.isRightMouseButton(e)) {
                  showAutomationCellPopupMenu(cell, e.getX(), e.getY(), cM, fParam, colIdx);
                  return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                  if (e.isShiftDown()) {
                    float oldVal = cM.getAutomation(fParam, colIdx);
                    float[] arr = cM.getAutomationArray(fParam);
                    if (arr != null && colIdx < arr.length) {
                      arr[colIdx] = -1f;
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.AutomationConsequence(
                                  projectModel, tIdx, acIdx2, fParam, colIdx, oldVal, -1f));
                      refreshCallback.run();
                    }
                  } else {
                    float oldVal = cM.getAutomation(fParam, colIdx);
                    if (cM.hasAutomation(fParam)) {
                      float[] arr = cM.getAutomationArray(fParam);
                      if (arr != null && colIdx < arr.length && arr[colIdx] >= 0f) {
                        arr[colIdx] = -1f;
                      } else if (arr != null && colIdx < arr.length) {
                        arr[colIdx] = 0.5f;
                      }
                    } else {
                      cM.setAutomation(fParam, colIdx, 0.5f);
                    }
                    float newVal = cM.getAutomation(fParam, colIdx);
                    if (newVal != oldVal && !e.isShiftDown()) {
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.AutomationConsequence(
                                  projectModel, tIdx, acIdx2, fParam, colIdx, oldVal, newVal));
                    }
                    autoDragParam = fParam;
                    autoDragStep = colIdx;
                    autoDragOldValue = oldVal;
                    automationDragging = true;
                    refreshCallback.run();
                  }
                }
              }

              @Override
              public void mouseReleased(MouseEvent e) {
                ProjectModel projectModel = getProjectModel();
                if (automationDragging
                    && projectModel != null
                    && autoDragParam != null
                    && autoDragStep >= 0) {
                  int tIdx = getEditedModelTrack();
                  if (tIdx < projectModel.getTracks().size()) {
                    TrackModel tM = projectModel.getTracks().get(tIdx);
                    int acIdx2 = tM.getActiveClipIndex();
                    if (acIdx2 >= 0 && acIdx2 < tM.getClips().size()) {
                      ClipModel cM = tM.getClips().get(acIdx2);
                      float newVal = cM.getAutomation(autoDragParam, autoDragStep);
                      if (newVal != autoDragOldValue) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.AutomationConsequence(
                                    projectModel,
                                    tIdx,
                                    acIdx2,
                                    autoDragParam,
                                    autoDragStep,
                                    autoDragOldValue,
                                    newVal));
                      }
                    }
                  }
                  autoDragParam = null;
                  autoDragStep = -1;
                  autoDragOldValue = -1f;
                }
                automationDragging = false;
              }
            });

        cell.addMouseMotionListener(
            new MouseMotionAdapter() {
              @Override
              public void mouseDragged(MouseEvent e) {
                if (!automationDragging || parent.getProjectModel() == null) return;
                int tIdx = getEditedModelTrack();
                if (tIdx >= parent.getProjectModel().getTracks().size()) return;
                TrackModel tM = parent.getProjectModel().getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                ClipModel cM = tM.getClips().get(acIdx2);
                cM.setAutomation(fParam, colIdx, 0.5f);
                refreshCallback.run();
              }
            });

        rowPanel.add(cell);
        rowPanel.add(Box.createHorizontalStrut(3));
      }
      container.add(rowPanel);
    }

    // Scroll indicator
    if (totalParams > maxVisible) {
      JPanel scrollBar = new JPanel();
      scrollBar.setLayout(new BoxLayout(scrollBar, BoxLayout.X_AXIS));
      scrollBar.setBackground(new Color(0x1a, 0x1a, 0x1a));
      scrollBar.setMaximumSize(new Dimension(3000, 24));

      int pw = parent.currentLabelWidth();
      scrollBar.add(Box.createRigidArea(new Dimension(pw + 17, 24)));

      for (int i = 0; i < totalParams; i += maxVisible) {
        int pageStart = i;
        int pageEnd = Math.min(i + maxVisible - 1, totalParams - 1);
        String pageLabel = (pageStart + 1) + "-" + (pageEnd + 1);
        boolean activePage = (autoColScroll >= pageStart && autoColScroll <= pageEnd);

        JButton dot = new JButton(pageLabel);
        dot.setMargin(new Insets(0, 4, 0, 4));
        dot.setFont(new Font("SansSerif", Font.PLAIN, 9));
        Color dotBg = activePage ? new Color(0x00, 0x88, 0xcc) : new Color(0x2d, 0x2d, 0x32);
        Color dotFg = activePage ? Color.WHITE : Color.LIGHT_GRAY;
        parent.styleSystemButton(dot, dotBg, dotFg, 9);
        final int fPage = pageStart;
        dot.addActionListener(
            e -> {
              autoColScroll = fPage;
              refreshCallback.run();
            });
        scrollBar.add(dot);
        scrollBar.add(Box.createHorizontalStrut(6));
      }
      container.add(scrollBar);
    }
  }

  /**
   * Linear interpolation between automated steps in a clip. Fills gaps (steps with -1) between two
   * known values. If fewer than 2 automated values exist, does nothing.
   */
  public void interpolateAutomation(ClipModel clip, String param) {
    if (clip == null || param == null) return;
    float[] arr = clip.getAutomationArray(param);
    if (arr == null) return;

    int first = -1, last = -1;
    for (int i = 0; i < arr.length; i++) {
      if (arr[i] >= 0f) {
        if (first < 0) first = i;
        last = i;
      }
    }
    if (first < 0 || first == last) return;

    int prevIdx = first;
    for (int i = first + 1; i <= last; i++) {
      if (arr[i] >= 0f) {
        int gapLen = i - prevIdx;
        if (gapLen > 1) {
          float startVal = arr[prevIdx];
          float endVal = arr[i];
          for (int g = 1; g < gapLen; g++) {
            float t = g / (float) gapLen;
            arr[prevIdx + g] = startVal + (endVal - startVal) * t;
          }
        }
        prevIdx = i;
      }
    }
    refreshCallback.run();
  }

  private void showAutomationCellPopupMenu(
      Component invoker, int x, int y, ClipModel clip, String paramName, int colIdx) {
    JPopupMenu menu = new JPopupMenu();

    float currentVal = clip.getAutomation(paramName, colIdx);
    boolean hasVal = currentVal >= 0.0f;

    JMenuItem setPrecise = new JMenuItem("Set Precise Value...");
    setPrecise.addActionListener(
        ev -> {
          String init = hasVal ? String.format("%.0f", currentVal * 100) : "50";
          String input =
              JOptionPane.showInputDialog(
                  parent,
                  "Enter automation value for "
                      + paramName
                      + " at step "
                      + (colIdx + 1)
                      + " (0 - 100):",
                  init);
          if (input != null && !input.isBlank()) {
            try {
              float pct = Float.parseFloat(input);
              if (pct >= 0f && pct <= 100f) {
                float oldVal = clip.getAutomation(paramName, colIdx);
                float newVal = pct / 100.0f;
                clip.setAutomation(paramName, colIdx, newVal);
                ProjectModel pm = getProjectModel();
                if (pm != null) {
                  int tIdx = getEditedModelTrack();
                  int acIdx = pm.getTracks().get(tIdx).getActiveClipIndex();
                  pm.getUndoRedoStack()
                      .push(
                          new Consequence.AutomationConsequence(
                              pm, tIdx, acIdx, paramName, colIdx, oldVal, newVal));
                }
                refreshCallback.run();
              }
            } catch (NumberFormatException ignored) {
            }
          }
        });
    menu.add(setPrecise);

    JMenuItem clearPoint = new JMenuItem("Clear Automation Point");
    clearPoint.setEnabled(hasVal);
    clearPoint.addActionListener(
        ev -> {
          float oldVal = clip.getAutomation(paramName, colIdx);
          float[] arr = clip.getAutomationArray(paramName);
          if (arr != null && colIdx < arr.length) {
            arr[colIdx] = -1f;
            ProjectModel pm = getProjectModel();
            if (pm != null) {
              int tIdx = getEditedModelTrack();
              int acIdx = pm.getTracks().get(tIdx).getActiveClipIndex();
              pm.getUndoRedoStack()
                  .push(
                      new Consequence.AutomationConsequence(
                          pm, tIdx, acIdx, paramName, colIdx, oldVal, -1f));
            }
            refreshCallback.run();
          }
        });
    menu.add(clearPoint);

    menu.addSeparator();

    // Quick presets
    JMenu quickVals = new JMenu("Quick Value");
    double[] vals = {0.0, 0.25, 0.50, 0.75, 1.00};
    for (double v : vals) {
      JMenuItem vItem = new JMenuItem((int) (v * 100) + "%");
      vItem.addActionListener(
          ev -> {
            float oldVal = clip.getAutomation(paramName, colIdx);
            clip.setAutomation(paramName, colIdx, (float) v);
            ProjectModel pm = getProjectModel();
            if (pm != null) {
              int tIdx = getEditedModelTrack();
              int acIdx = pm.getTracks().get(tIdx).getActiveClipIndex();
              pm.getUndoRedoStack()
                  .push(
                      new Consequence.AutomationConsequence(
                          pm, tIdx, acIdx, paramName, colIdx, oldVal, (float) v));
            }
            refreshCallback.run();
          });
      quickVals.add(vItem);
    }
    menu.add(quickVals);

    SwingGridPanel.stylePopupMenu(menu);
    for (java.awt.Component comp : menu.getComponents()) {
      if (comp instanceof JMenuItem mi && "Clear Automation Point".equals(mi.getText())) {
        mi.setForeground(hasVal ? Color.RED : Color.GRAY);
      }
    }

    menu.show(invoker, x, y);
  }
}
