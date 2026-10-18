package org.deluge.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Point;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.Consequence;
import org.deluge.model.StepData;
import org.deluge.model.TrackModel;

/**
 * Extracted properties editing logic for step sequencer cells. Accesses companion properties
 * package-privately to minimize ClipEditorController's size.
 */
public class StepPropertiesEditor {

  private static int resolveStepPitch(SwingGridPanel parent, int trackType, int visualModelRow) {
    TrackModel tModel = parent.editedTrackOrNull();
    if (tModel instanceof org.deluge.model.KitTrackModel || trackType == 0) {
      return visualModelRow;
    }
    if (tModel instanceof org.deluge.model.SynthTrackModel || trackType == 1) {
      return parent.getRowPitch(visualModelRow);
    }
    return 0;
  }

  public static void handleStepLongPressed(
      ClipEditorController controller, int row, int col, Point screenPos) {
    SwingGridPanel parent = controller.parent;
    if (!parent.isSquareDefined(col)) return;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int activeCol = parent.getActiveCol(row, col);
    if (activeCol < 0) return;
    int trackType = parent.resolveEditedTrackType();
    boolean synthModeActive = trackType == 1 || trackType == 0;
    JPopupMenu popup = new JPopupMenu();

    JMenuItem editProps = new JMenuItem("Edit Step Properties...");
    editProps.addActionListener(e -> showStepPropertiesDialog(controller, row, col));
    popup.add(editProps);

    JMenuItem toggleStep = new JMenuItem("Toggle Step");
    toggleStep.addActionListener(e -> controller.handleStepToggled(row, col));
    popup.add(toggleStep);

    if (synthModeActive) {
      JMenuItem pianoRollItem = new JMenuItem("Open Piano Roll Editor...");
      pianoRollItem.addActionListener(ev -> parent.openPianoRollForActiveClip());
      popup.add(pianoRollItem);
    }

    JMenuItem euclideanItem = new JMenuItem("Euclidean Fill Row...");
    euclideanItem.addActionListener(
        ev -> {
          Frame owner = (Frame) SwingUtilities.getWindowAncestor(parent);
          ClipModel cModel = resolveClipModel(parent);
          int euclideanSteps = cModel != null ? cModel.getStepCount() : parent.getScreenStepCount();
          new EuclideanRhythmDialog(
                  owner,
                  euclideanSteps,
                  "Row " + (row + 1),
                  pat -> parent.applyEuclideanFillToRow(row, pat))
              .setVisible(true);
        });
    popup.add(euclideanItem);

    JMenuItem quantizeItem = new JMenuItem("Quantize Row Notes");
    quantizeItem.addActionListener(ev -> parent.quantizeRow(row));
    popup.add(quantizeItem);

    JMenuItem humanizeItem = new JMenuItem("Humanize Row Notes...");
    humanizeItem.addActionListener(
        ev -> {
          String input =
              JOptionPane.showInputDialog(parent, "Max timing shift (nudge % from 1 to 99):", "15");
          if (input != null && !input.isBlank()) {
            try {
              float pct = Float.parseFloat(input.trim()) / 100.0f;
              pct = Math.max(0.0f, Math.min(0.99f, pct));
              parent.humanizeRow(row, pct);
            } catch (NumberFormatException ignored) {
            }
          }
        });
    popup.add(humanizeItem);

    JMenuItem clearStep = new JMenuItem("Clear Step");
    clearStep.addActionListener(
        e -> {
          ClipModel cModel = resolveClipModel(parent);
          StepData existing =
              cModel != null ? parent.getClipStep(cModel, visualModelRow, activeCol) : null;
          boolean wasActive;
          if (cModel != null) {
            wasActive = existing != null && existing.active();
          } else {
            int er = parent.getAllocatedEngineRow(visualModelRow);
            wasActive = er >= 0 && bridge.getStep(er, activeCol);
          }
          if (wasActive) {
            controller.handleStepToggled(row, col);
          }
        });
    popup.add(clearStep);

    popup.addSeparator();

    JMenu velMenu = new JMenu("Quick Velocity");
    double[] velocities = {0.25, 0.50, 0.75, 1.00};
    for (double v : velocities) {
      JMenuItem vItem = new JMenuItem((int) (v * 100) + "%");
      vItem.addActionListener(e -> applyVelocity(controller, row, col, v));
      velMenu.add(vItem);
    }
    popup.add(velMenu);

    JMenu probMenu = new JMenu("Quick Probability");
    double[] probabilities = {0.25, 0.50, 0.75, 1.00};
    for (double p : probabilities) {
      JMenuItem pItem = new JMenuItem((int) (p * 100) + "%");
      pItem.addActionListener(e -> saveStepProbability(controller, row, col, p));
      probMenu.add(pItem);
    }
    popup.add(probMenu);

    JMenu gateMenu = new JMenu("Quick Gate (Duration)");
    double[] gates = {0.0625, 0.125, 0.25, 0.5, 1.0};
    String[] gateLabels = {"1/16 step", "1/8 step", "1/4 step", "1/2 step", "1 step (tied)"};
    for (int i = 0; i < gates.length; i++) {
      final double g = gates[i];
      JMenuItem gItem = new JMenuItem(gateLabels[i]);
      gItem.addActionListener(
          e -> {
            ClipModel cModel = resolveClipModel(parent);
            int clipRow =
                cModel != null ? parent.getClipRowIndex(cModel, visualModelRow, false) : -1;
            if (cModel != null && clipRow < 0) {
              return;
            }
            StepData oldStep = cModel != null ? cModel.getStep(clipRow, activeCol) : null;
            int er =
                cModel != null
                    ? parent.getEngineRowForStoredClipRow(clipRow)
                    : parent.getAllocatedEngineRow(visualModelRow);
            if (er >= 0) {
              bridge.setGate(er, activeCol, g);
            }
            if (cModel != null) {
              boolean st =
                  er >= 0 ? bridge.getStep(er, activeCol) : (oldStep != null && oldStep.active());
              double vel =
                  er >= 0
                      ? bridge.getVelocity(er, activeCol)
                      : (oldStep != null ? oldStep.velocity() : 0.8);
              double prob =
                  er >= 0
                      ? bridge.getStepProbability(er, activeCol)
                      : (oldStep != null ? oldStep.probability() : 1.0);
              int iter =
                  er >= 0
                      ? bridge.getIterance(er, activeCol)
                      : (oldStep != null ? oldStep.iterance() : 0);
              double fill =
                  er >= 0
                      ? bridge.getStepFill(er, activeCol)
                      : (oldStep != null ? oldStep.fill() : 0.0);
              int pitch = resolveStepPitch(parent, trackType, visualModelRow);

              int resolvedFillMode =
                  (oldStep != null && Math.abs((float) fill - oldStep.fill()) < 0.001f)
                      ? oldStep.fillMode()
                      : (fill > 0.001 ? 2 : 0);
              StepData newStep =
                  new StepData(
                      st,
                      (float) vel,
                      (float) g,
                      (float) prob,
                      pitch,
                      iter,
                      (float) fill,
                      oldStep != null ? oldStep.nudge() : 0.0f,
                      oldStep != null ? oldStep.playCondition() : 0,
                      resolvedFillMode);
              cModel.setStep(clipRow, activeCol, newStep);
              if (oldStep != null && parent.getProjectModel() != null) {
                parent
                    .getProjectModel()
                    .getUndoRedoStack()
                    .push(
                        new Consequence.StepConsequence(
                            parent.getProjectModel(),
                            parent.getEditedModelTrack(),
                            parent.getActiveClipId(),
                            clipRow,
                            activeCol,
                            oldStep,
                            cModel.getStep(clipRow, activeCol)));
              }
            }
            controller.refreshCallback.run();
          });
      gateMenu.add(gItem);
    }
    popup.add(gateMenu);

    popup.addSeparator();

    JMenuItem copyItem = new JMenuItem("Copy Step");
    copyItem.addActionListener(
        e -> {
          ClipModel cModel = resolveClipModel(parent);
          int clipRow = cModel != null ? parent.getClipRowIndex(cModel, visualModelRow, false) : -1;
          if (cModel != null && clipRow < 0) {
            return;
          }
          StepData existing = cModel != null ? cModel.getStep(clipRow, activeCol) : null;
          int er =
              cModel != null
                  ? parent.getEngineRowForStoredClipRow(clipRow)
                  : parent.getAllocatedEngineRow(visualModelRow);
          if (er < 0 && existing == null) {
            return;
          }
          boolean st =
              er >= 0 ? bridge.getStep(er, activeCol) : (existing != null && existing.active());
          double vel =
              er >= 0
                  ? bridge.getVelocity(er, activeCol)
                  : (existing != null ? existing.velocity() : 0.8);
          double gate =
              er >= 0 ? bridge.getGate(er, activeCol) : (existing != null ? existing.gate() : 1.0);
          double prob =
              er >= 0
                  ? bridge.getStepProbability(er, activeCol)
                  : (existing != null ? existing.probability() : 1.0);
          int iter =
              er >= 0
                  ? bridge.getIterance(er, activeCol)
                  : (existing != null ? existing.iterance() : 0);
          double fill =
              er >= 0
                  ? bridge.getStepFill(er, activeCol)
                  : (existing != null ? existing.fill() : 0.0);
          int pitch = resolveStepPitch(parent, trackType, visualModelRow);
          int copiedFillMode =
              (existing != null && Math.abs((float) fill - existing.fill()) < 0.001f)
                  ? existing.fillMode()
                  : (fill > 0.001 ? 2 : 0);
          controller.copiedStep =
              new StepData(
                  st,
                  (float) vel,
                  (float) gate,
                  (float) prob,
                  pitch,
                  iter,
                  (float) fill,
                  existing != null ? existing.nudge() : 0.0f,
                  existing != null ? existing.playCondition() : 0,
                  copiedFillMode);
        });
    popup.add(copyItem);

    JMenuItem pasteItem = new JMenuItem("Paste Step");
    pasteItem.setEnabled(controller.copiedStep != null);
    pasteItem.addActionListener(
        e -> {
          if (controller.copiedStep != null) {
            ClipModel cModel = resolveClipModel(parent);
            int dstClipRow = -1;
            StepData oldStep = null;
            if (cModel != null) {
              dstClipRow = parent.getClipRowIndex(cModel, visualModelRow, true);
              if (dstClipRow < 0) {
                return;
              }
              oldStep = cModel.getStep(dstClipRow, activeCol);
            }
            int curTrackType =
                parent.getBaseTrackId() >= 0
                    ? bridge.getTrackType(parent.getBaseTrackId())
                    : trackType;
            int pitch = resolveStepPitch(parent, curTrackType, visualModelRow);
            StepData newStep = controller.copiedStep.withPitch(pitch);
            if (cModel != null) {
              cModel.setStep(dstClipRow, activeCol, newStep);
              if (oldStep != null && parent.getProjectModel() != null) {
                parent
                    .getProjectModel()
                    .getUndoRedoStack()
                    .push(
                        new Consequence.StepConsequence(
                            parent.getProjectModel(),
                            parent.getEditedModelTrack(),
                            parent.getActiveClipId(),
                            dstClipRow,
                            activeCol,
                            oldStep,
                            cModel.getStep(dstClipRow, activeCol)));
              }
            }
            int dstEngineRow =
                cModel != null
                    ? parent.getEngineRowForStoredClipRow(dstClipRow)
                    : parent.getAllocatedEngineRow(visualModelRow);
            if (dstEngineRow >= 0) {
              bridge.setStep(dstEngineRow, activeCol, newStep.active());
              bridge.setVelocity(dstEngineRow, activeCol, newStep.velocity());
              bridge.setGate(dstEngineRow, activeCol, newStep.gate());
              bridge.setPitch(dstEngineRow, activeCol, newStep.pitch());
              bridge.setStepProbability(dstEngineRow, activeCol, newStep.probability());
              bridge.setIterance(dstEngineRow, activeCol, newStep.iterance());
              bridge.setStepFill(dstEngineRow, activeCol, newStep.fill());
              bridge.setStepNudge(dstEngineRow, activeCol, newStep.nudge());
            }
            controller.refreshCallback.run();
          }
        });
    popup.add(pasteItem);

    SwingGridPanel.stylePopupMenu(popup);
    for (Component comp : popup.getComponents()) {
      if (comp instanceof JMenuItem mi && "Open Piano Roll Editor...".equals(mi.getText())) {
        mi.setForeground(new Color(0x00, 0xff, 0xcc));
      }
    }

    Point localPt = new Point(screenPos);
    SwingUtilities.convertPointFromScreen(localPt, parent);
    popup.show(parent, localPt.x, localPt.y);
  }

  private static ClipModel resolveClipModel(SwingGridPanel parent) {
    return parent.activeClipOrNull();
  }

  public static void showStepPropertiesDialog(ClipEditorController controller, int row, int col) {
    SwingGridPanel parent = controller.parent;
    if (!parent.isSquareDefined(col)) return;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int activeCol = parent.getActiveCol(row, col);
    if (activeCol < 0) return;
    ClipModel cm = resolveClipModel(parent);
    int clipRow = cm != null ? parent.getClipRowIndex(cm, visualModelRow, false) : -1;
    if (cm != null && clipRow < 0) {
      return;
    }
    StepData sd = cm != null ? cm.getStep(clipRow, activeCol) : null;
    int engineRow =
        cm != null
            ? parent.getEngineRowForStoredClipRow(clipRow)
            : parent.getAllocatedEngineRow(visualModelRow);
    double curVel =
        engineRow >= 0
            ? bridge.getVelocity(engineRow, activeCol)
            : (sd != null && sd.active() ? sd.velocity() : 0.8);
    int curIt =
        engineRow >= 0
            ? bridge.getIterance(engineRow, activeCol)
            : (sd != null ? sd.iterance() : 0);
    double curProb =
        engineRow >= 0
            ? bridge.getStepProbability(engineRow, activeCol)
            : (sd != null && sd.active() ? sd.probability() : 1.0);
    double curGate =
        engineRow >= 0
            ? bridge.getGate(engineRow, activeCol)
            : (sd != null && sd.active() ? sd.gate() : 1.0);

    float curFill = 0.0f;
    float curNudge = 0.0f;
    org.deluge.model.Iterance curCond = org.deluge.model.Iterance.CUSTOM_DEFAULT;

    if (sd != null) {
      curFill = sd.fill();
      curNudge = sd.nudge();
      if (sd.playCondition() != 0) {
        curCond = org.deluge.model.Iterance.fromInt(sd.playCondition());
      }
    }

    StepPropertiesDialog dlg =
        new StepPropertiesDialog(
            (Frame) SwingUtilities.getWindowAncestor(parent),
            (int) (curVel * 100),
            curIt,
            (int) (curFill * 100),
            (int) (curProb * 100),
            curGate,
            (int) (curNudge * 100),
            curCond);
    dlg.setVisible(true);
    if (dlg.isConfirmed()) {
      int newVel = dlg.getVelocity();
      int newIt = dlg.getIterance();
      int newFill = dlg.getFill();
      int newProb = dlg.getProbability();
      double newGate = dlg.getGate();
      int newNudge = dlg.getNudge();
      int newPlayCond = dlg.getPlayConditionInt();
      if (newVel != (int) (curVel * 100)
          || newIt != curIt
          || newFill != (int) (curFill * 100)
          || newProb != (int) (curProb * 100)
          || newGate != curGate
          || newNudge != (int) (curNudge * 100)
          || newPlayCond != curCond.toInt()) {
        applyStepProperties(
            controller,
            row,
            col,
            newVel / 100.0,
            newIt,
            newFill / 100.0,
            newProb / 100.0,
            newGate,
            newNudge / 100.0,
            newPlayCond);
      }
    }
  }

  public static void applyStepProperties(
      ClipEditorController controller,
      int row,
      int col,
      double vel,
      int iterance,
      double fill,
      double prob,
      double gate,
      double nudge) {
    applyStepProperties(controller, row, col, vel, iterance, fill, prob, gate, nudge, -1);
  }

  public static void applyStepProperties(
      ClipEditorController controller,
      int row,
      int col,
      double vel,
      int iterance,
      double fill,
      double prob,
      double gate,
      double nudge,
      int playCondition) {
    SwingGridPanel parent = controller.parent;
    if (!parent.isSquareDefined(col)) return;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int activeCol = parent.getActiveCol(row, col);
    if (activeCol < 0) return;
    ClipModel cModel = resolveClipModel(parent);
    int editedModelTrack = parent.getEditedModelTrack();
    int activeClipId = parent.getActiveClipId();
    int clipRow = cModel != null ? parent.getClipRowIndex(cModel, visualModelRow, false) : -1;
    if (cModel != null && clipRow < 0) {
      return;
    }
    StepData oldStep = cModel != null ? cModel.getStep(clipRow, activeCol) : null;
    int trackType = parent.resolveEditedTrackType();
    int pitch = resolveStepPitch(parent, trackType, visualModelRow);
    int engineRow =
        cModel != null
            ? parent.getEngineRowForStoredClipRow(clipRow)
            : parent.getAllocatedEngineRow(visualModelRow);

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      // C instrument_clip_view.cpp:337 — wrapEditLevel equals current screen width
      int screenSteps = parent.getScreenStepCount();
      int baseCol = activeCol % screenSteps;
      int trackLen = cModel.getStepCount();
      java.util.List<Consequence> steps = new java.util.ArrayList<>();
      for (int c = baseCol; c < trackLen; c += screenSteps) {
        StepData oldSt = cModel.getStep(clipRow, c);
        if (oldSt.active()) {
          if (bridge != null && engineRow >= 0) {
            bridge.setVelocity(engineRow, c, vel);
            bridge.setIterance(engineRow, c, iterance);
            bridge.setStepProbability(engineRow, c, prob);
            bridge.setGate(engineRow, c, gate);
            bridge.setStepFill(engineRow, c, fill);
            bridge.setStepNudge(engineRow, c, nudge);
          }

          int resolvedPlayCond = playCondition >= 0 ? playCondition : oldSt.playCondition();
          int resolvedFillMode =
              Math.abs((float) fill - oldSt.fill()) < 0.001f
                  ? oldSt.fillMode()
                  : (fill > 0.001 ? 2 : 0);
          StepData newStep =
              new StepData(
                  true,
                  (float) vel,
                  (float) gate,
                  (float) prob,
                  pitch,
                  iterance,
                  (float) fill,
                  (float) nudge,
                  resolvedPlayCond,
                  resolvedFillMode);
          cModel.setStep(clipRow, c, newStep);
          if (oldSt != null && parent.getProjectModel() != null) {
            steps.add(
                new Consequence.StepConsequence(
                    parent.getProjectModel(),
                    editedModelTrack,
                    activeClipId,
                    clipRow,
                    c,
                    oldSt,
                    newStep));
          }
        }
      }
      if (!steps.isEmpty() && parent.getProjectModel() != null) {
        parent
            .getProjectModel()
            .getUndoRedoStack()
            .push(new Consequence.CompoundConsequence("Edit Step Properties Wrap", steps));
      }
    } else {
      if (bridge != null && engineRow >= 0) {
        bridge.setVelocity(engineRow, activeCol, vel);
        bridge.setIterance(engineRow, activeCol, iterance);
        bridge.setStepProbability(engineRow, activeCol, prob);
        bridge.setGate(engineRow, activeCol, gate);
        bridge.setStepFill(engineRow, activeCol, fill);
        bridge.setStepNudge(engineRow, activeCol, nudge);
      }

      if (cModel != null) {
        boolean st =
            (bridge != null && engineRow >= 0)
                ? bridge.getStep(engineRow, activeCol)
                : (oldStep != null ? oldStep.active() : true);
        int resolvedPlayCond =
            playCondition >= 0 ? playCondition : (oldStep != null ? oldStep.playCondition() : 0);
        int resolvedFillMode =
            (oldStep != null && Math.abs((float) fill - oldStep.fill()) < 0.001f)
                ? oldStep.fillMode()
                : (fill > 0.001 ? 2 : 0);
        StepData newStep =
            new StepData(
                st,
                (float) vel,
                (float) gate,
                (float) prob,
                pitch,
                iterance,
                (float) fill,
                (float) nudge,
                resolvedPlayCond,
                resolvedFillMode);
        cModel.setStep(clipRow, activeCol, newStep);
        if (oldStep != null && parent.getProjectModel() != null) {
          parent
              .getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      parent.getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      clipRow,
                      activeCol,
                      oldStep,
                      cModel.getStep(clipRow, activeCol)));
        }
      }
    }
    controller.projectChangedCallback.run();
  }

  public static void applyVelocity(ClipEditorController controller, int row, int col, double vel) {
    SwingGridPanel parent = controller.parent;
    if (!parent.isSquareDefined(col)) return;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int activeCol = parent.getActiveCol(row, col);
    if (activeCol < 0) return;
    ClipModel cm = resolveClipModel(parent);
    int clipRow = cm != null ? parent.getClipRowIndex(cm, visualModelRow, false) : -1;
    if (cm != null && clipRow < 0) {
      return;
    }
    StepData sd = cm != null ? cm.getStep(clipRow, activeCol) : null;
    int engineRow =
        cm != null
            ? parent.getEngineRowForStoredClipRow(clipRow)
            : parent.getAllocatedEngineRow(visualModelRow);
    int iter =
        engineRow >= 0
            ? bridge.getIterance(engineRow, activeCol)
            : (sd != null ? sd.iterance() : 0);
    double fill = sd != null ? sd.fill() : 0.0;
    double nudge = sd != null ? sd.nudge() : 0.0;
    double prob =
        engineRow >= 0
            ? bridge.getStepProbability(engineRow, activeCol)
            : (sd != null ? sd.probability() : 1.0);
    double gate =
        engineRow >= 0 ? bridge.getGate(engineRow, activeCol) : (sd != null ? sd.gate() : 1.0);
    applyStepProperties(controller, row, col, vel, iter, fill, prob, gate, nudge);
  }

  public static void saveStepProbability(
      ClipEditorController controller, int row, int col, double prob) {
    SwingGridPanel parent = controller.parent;
    if (!parent.isSquareDefined(col)) return;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int activeCol = parent.getActiveCol(row, col);
    if (activeCol < 0) return;
    ClipModel cModel = resolveClipModel(parent);
    int editedModelTrack = parent.getEditedModelTrack();
    int activeClipId = parent.getActiveClipId();
    int clipRow = cModel != null ? parent.getClipRowIndex(cModel, visualModelRow, false) : -1;
    if (cModel != null && clipRow < 0) {
      return;
    }
    StepData oldStep = cModel != null ? cModel.getStep(clipRow, activeCol) : null;
    int trackType = parent.resolveEditedTrackType();
    int pitch = resolveStepPitch(parent, trackType, visualModelRow);
    int engineRow =
        cModel != null
            ? parent.getEngineRowForStoredClipRow(clipRow)
            : parent.getAllocatedEngineRow(visualModelRow);

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      // C instrument_clip_view.cpp:337 — wrapEditLevel equals current screen width
      int screenSteps = parent.getScreenStepCount();
      int baseCol = activeCol % screenSteps;
      int trackLen = cModel.getStepCount();
      java.util.List<Consequence> steps = new java.util.ArrayList<>();
      for (int c = baseCol; c < trackLen; c += screenSteps) {
        StepData oldSt = cModel.getStep(clipRow, c);
        if (oldSt.active()) {
          if (bridge != null && engineRow >= 0) {
            bridge.setStepProbability(engineRow, c, prob);
          }

          double vel =
              (bridge != null && engineRow >= 0)
                  ? bridge.getVelocity(engineRow, c)
                  : oldSt.velocity();
          double gate =
              (bridge != null && engineRow >= 0) ? bridge.getGate(engineRow, c) : oldSt.gate();
          int iter =
              (bridge != null && engineRow >= 0)
                  ? bridge.getIterance(engineRow, c)
                  : oldSt.iterance();
          double fill =
              (bridge != null && engineRow >= 0) ? bridge.getStepFill(engineRow, c) : oldSt.fill();
          int resolvedFillMode =
              Math.abs((float) fill - oldSt.fill()) < 0.001f
                  ? oldSt.fillMode()
                  : (fill > 0.001 ? 2 : 0);
          StepData newStep =
              new StepData(
                  true,
                  (float) vel,
                  (float) gate,
                  (float) prob,
                  pitch,
                  iter,
                  (float) fill,
                  oldSt.nudge(),
                  oldSt.playCondition(),
                  resolvedFillMode);
          cModel.setStep(clipRow, c, newStep);
          if (oldSt != null && parent.getProjectModel() != null) {
            steps.add(
                new Consequence.StepConsequence(
                    parent.getProjectModel(),
                    editedModelTrack,
                    activeClipId,
                    clipRow,
                    c,
                    oldSt,
                    newStep));
          }
        }
      }
      if (!steps.isEmpty() && parent.getProjectModel() != null) {
        parent
            .getProjectModel()
            .getUndoRedoStack()
            .push(new Consequence.CompoundConsequence("Save Step Probability Wrap", steps));
      }
    } else {
      if (bridge != null && engineRow >= 0) {
        bridge.setStepProbability(engineRow, activeCol, prob);
      }

      if (cModel != null) {
        boolean st =
            (bridge != null && engineRow >= 0)
                ? bridge.getStep(engineRow, activeCol)
                : (oldStep != null ? oldStep.active() : true);
        double vel =
            (bridge != null && engineRow >= 0)
                ? bridge.getVelocity(engineRow, activeCol)
                : (oldStep != null ? oldStep.velocity() : 0.8);
        double gate =
            (bridge != null && engineRow >= 0)
                ? bridge.getGate(engineRow, activeCol)
                : (oldStep != null ? oldStep.gate() : 0.9);
        int iter =
            (bridge != null && engineRow >= 0)
                ? bridge.getIterance(engineRow, activeCol)
                : (oldStep != null ? oldStep.iterance() : 0);
        double fill =
            (bridge != null && engineRow >= 0)
                ? bridge.getStepFill(engineRow, activeCol)
                : (oldStep != null ? oldStep.fill() : 0.0);
        int resolvedFillMode =
            (oldStep != null && Math.abs((float) fill - oldStep.fill()) < 0.001f)
                ? oldStep.fillMode()
                : (fill > 0.001 ? 2 : 0);
        StepData newStep =
            new StepData(
                st,
                (float) vel,
                (float) gate,
                (float) prob,
                pitch,
                iter,
                (float) fill,
                oldStep != null ? oldStep.nudge() : 0.0f,
                oldStep != null ? oldStep.playCondition() : 0,
                resolvedFillMode);
        cModel.setStep(clipRow, activeCol, newStep);
        if (oldStep != null && parent.getProjectModel() != null) {
          parent
              .getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      parent.getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      clipRow,
                      activeCol,
                      oldStep,
                      cModel.getStep(clipRow, activeCol)));
        }
      }
    }
  }
}
