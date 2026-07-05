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
class StepPropertiesEditor {

  public static void handleStepLongPressed(
      ClipEditorController controller, int row, int col, Point screenPos) {
    SwingGridPanel parent = controller.parent;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int activeCol = parent.getActiveCol(row, col);
    int baseTrackId = parent.getBaseTrackId();
    int engineRow = baseTrackId + engineRowOffset;
    int trackType = bridge.getTrackType(baseTrackId);
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
          new EuclideanRhythmDialog(
                  owner, 16, "Row " + (row + 1), pat -> parent.applyEuclideanFillToRow(row, pat))
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
          boolean wasActive = bridge.getStep(engineRow, activeCol);
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
            bridge.setGate(engineRow, activeCol, g);
            int editedModelTrack = parent.getEditedModelTrack();
            int activeClipId = parent.getActiveClipId();
            if (parent.getProjectModel() != null
                && editedModelTrack < parent.getProjectModel().getTracks().size()) {
              TrackModel tModel = parent.getProjectModel().getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                ClipModel cModel = tModel.getClips().get(activeClipId);
                StepData oldStep = parent.getClipStep(cModel, visualModelRow, activeCol);
                boolean st = bridge.getStep(engineRow, activeCol);
                double vel = bridge.getVelocity(engineRow, activeCol);
                double prob = bridge.getStepProbability(engineRow, activeCol);
                int iter = bridge.getIterance(engineRow, activeCol);
                double fill = bridge.getStepFill(engineRow, activeCol);
                boolean isSynthMode = trackType == 1 || trackType == 0;
                int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;

                StepData newStep =
                    new StepData(
                        st, (float) vel, (float) g, (float) prob, pitch, iter, (float) fill);
                parent.setClipStep(cModel, visualModelRow, activeCol, newStep);
                if (oldStep != null && parent.getProjectModel() != null) {
                  parent
                      .getProjectModel()
                      .getUndoRedoStack()
                      .push(
                          new Consequence.StepConsequence(
                              parent.getProjectModel(),
                              editedModelTrack,
                              activeClipId,
                              visualModelRow,
                              activeCol,
                              oldStep,
                              parent.getClipStep(cModel, visualModelRow, activeCol)));
                }
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
          boolean st = bridge.getStep(engineRow, activeCol);
          double vel = bridge.getVelocity(engineRow, activeCol);
          double gate = bridge.getGate(engineRow, activeCol);
          double prob = bridge.getStepProbability(engineRow, activeCol);
          int iter = bridge.getIterance(engineRow, activeCol);
          double fill = bridge.getStepFill(engineRow, activeCol);
          boolean isSynthMode = trackType == 1 || trackType == 0;
          int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
          controller.copiedStep =
              new StepData(st, (float) vel, (float) gate, (float) prob, pitch, iter, (float) fill);
        });
    popup.add(copyItem);

    JMenuItem pasteItem = new JMenuItem("Paste Step");
    pasteItem.setEnabled(controller.copiedStep != null);
    pasteItem.addActionListener(
        e -> {
          if (controller.copiedStep != null) {
            bridge.setStep(engineRow, activeCol, controller.copiedStep.active());
            bridge.setVelocity(engineRow, activeCol, controller.copiedStep.velocity());
            bridge.setGate(engineRow, activeCol, controller.copiedStep.gate());
            bridge.setStepProbability(engineRow, activeCol, controller.copiedStep.probability());
            bridge.setIterance(engineRow, activeCol, controller.copiedStep.iterance());
            bridge.setStepFill(engineRow, activeCol, controller.copiedStep.fill());
            bridge.setStepNudge(engineRow, activeCol, controller.copiedStep.nudge());

            int editedModelTrack = parent.getEditedModelTrack();
            int activeClipId = parent.getActiveClipId();
            if (parent.getProjectModel() != null
                && editedModelTrack < parent.getProjectModel().getTracks().size()) {
              TrackModel tModel = parent.getProjectModel().getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                ClipModel cModel = tModel.getClips().get(activeClipId);
                StepData oldStep = parent.getClipStep(cModel, visualModelRow, activeCol);
                boolean isSynthMode = trackType == 1 || trackType == 0;
                int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
                StepData newStep =
                    new StepData(
                        controller.copiedStep.active(),
                        controller.copiedStep.velocity(),
                        controller.copiedStep.gate(),
                        controller.copiedStep.probability(),
                        pitch,
                        controller.copiedStep.iterance(),
                        controller.copiedStep.fill());
                parent.setClipStep(cModel, visualModelRow, activeCol, newStep);
                if (oldStep != null && parent.getProjectModel() != null) {
                  parent
                      .getProjectModel()
                      .getUndoRedoStack()
                      .push(
                          new Consequence.StepConsequence(
                              parent.getProjectModel(),
                              editedModelTrack,
                              activeClipId,
                              visualModelRow,
                              activeCol,
                              oldStep,
                              parent.getClipStep(cModel, visualModelRow, activeCol)));
                }
              }
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

  public static void showStepPropertiesDialog(ClipEditorController controller, int row, int col) {
    SwingGridPanel parent = controller.parent;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int activeCol = parent.getActiveCol(row, col);
    int engineRow = parent.getBaseTrackId() + engineRowOffset;
    double curVel = bridge.getVelocity(engineRow, activeCol);
    int curIt = bridge.getIterance(engineRow, activeCol);
    double curProb = bridge.getStepProbability(engineRow, activeCol);
    double curGate = bridge.getGate(engineRow, activeCol);

    float curFill = 0.0f;
    float curNudge = 0.0f;

    org.deluge.model.ProjectModel projectModel = parent.getProjectModel();
    int editedModelTrack = parent.getEditedModelTrack();
    int activeClipId = parent.getActiveClipId();
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      TrackModel tm = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId >= 0 && activeClipId < tm.getClips().size()) {
        ClipModel cm = tm.getClips().get(activeClipId);
        StepData sd = parent.getClipStep(cm, visualModelRow, activeCol);
        if (sd != null) {
          curFill = sd.fill();
          curNudge = sd.nudge();
        }
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
            (int) (curNudge * 100));
    dlg.setVisible(true);
    if (dlg.isConfirmed()) {
      int newVel = dlg.getVelocity();
      int newIt = dlg.getIterance();
      int newFill = dlg.getFill();
      int newProb = dlg.getProbability();
      double newGate = dlg.getGate();
      int newNudge = dlg.getNudge();
      if (newVel != (int) (curVel * 100)
          || newIt != curIt
          || newFill != (int) (curFill * 100)
          || newProb != (int) (curProb * 100)
          || newGate != curGate
          || newNudge != (int) (curNudge * 100)) {
        applyStepProperties(
            controller,
            row,
            col,
            newVel / 100.0,
            newIt,
            newFill / 100.0,
            newProb / 100.0,
            newGate,
            newNudge / 100.0);
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
    SwingGridPanel parent = controller.parent;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int activeCol = parent.getActiveCol(row, col);
    int baseTrackId = parent.getBaseTrackId();
    int engineRow = baseTrackId + engineRowOffset;
    boolean isSynthMode =
        bridge.getTrackType(baseTrackId) == 1 || bridge.getTrackType(baseTrackId) == 0;
    int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;

    StepData oldStep = null;
    TrackModel tModel = null;
    ClipModel cModel = null;
    int editedModelTrack = parent.getEditedModelTrack();
    int activeClipId = parent.getActiveClipId();
    if (parent.getProjectModel() != null
        && editedModelTrack < parent.getProjectModel().getTracks().size()) {
      tModel = parent.getProjectModel().getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
        oldStep = parent.getClipStep(cModel, visualModelRow, activeCol);
      }
    }

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      int baseCol = activeCol % 16;
      int trackLen = cModel.getStepCount();
      java.util.List<Consequence> steps = new java.util.ArrayList<>();
      for (int c = baseCol; c < trackLen; c += 16) {
        StepData oldSt = parent.getClipStep(cModel, visualModelRow, c);
        if (oldSt.active()) {
          if (bridge != null) {
            bridge.setVelocity(engineRow, c, vel);
            bridge.setIterance(engineRow, c, iterance);
            bridge.setStepProbability(engineRow, c, prob);
            bridge.setGate(engineRow, c, gate);
            bridge.setStepFill(engineRow, c, fill);
            bridge.setStepNudge(engineRow, c, nudge);
          }

          StepData newStep =
              new StepData(
                  true,
                  (float) vel,
                  (float) gate,
                  (float) prob,
                  pitch,
                  iterance,
                  (float) fill,
                  (float) nudge);
          parent.setClipStep(cModel, visualModelRow, c, newStep);
          if (oldSt != null && parent.getProjectModel() != null) {
            steps.add(
                new Consequence.StepConsequence(
                    parent.getProjectModel(),
                    editedModelTrack,
                    activeClipId,
                    visualModelRow,
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
      if (bridge != null) {
        bridge.setVelocity(engineRow, activeCol, vel);
        bridge.setIterance(engineRow, activeCol, iterance);
        bridge.setStepProbability(engineRow, activeCol, prob);
        bridge.setGate(engineRow, activeCol, gate);
        bridge.setStepFill(engineRow, activeCol, fill);
        bridge.setStepNudge(engineRow, activeCol, nudge);
      }

      if (cModel != null) {
        boolean st = bridge != null ? bridge.getStep(engineRow, activeCol) : true;
        StepData newStep =
            new StepData(
                st,
                (float) vel,
                (float) gate,
                (float) prob,
                pitch,
                iterance,
                (float) fill,
                (float) nudge);
        parent.setClipStep(cModel, visualModelRow, activeCol, newStep);
        if (oldStep != null && parent.getProjectModel() != null) {
          parent
              .getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      parent.getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      visualModelRow,
                      activeCol,
                      oldStep,
                      parent.getClipStep(cModel, visualModelRow, activeCol)));
        }
      }
    }
    controller.projectChangedCallback.run();
  }

  public static void applyVelocity(ClipEditorController controller, int row, int col, double vel) {
    SwingGridPanel parent = controller.parent;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int activeCol = parent.getActiveCol(row, col);
    int engineRow = parent.getBaseTrackId() + engineRowOffset;
    int iter = bridge.getIterance(engineRow, activeCol);
    double fill = 0.0;
    double nudge = 0.0;
    org.deluge.model.ProjectModel projectModel = parent.getProjectModel();
    int editedModelTrack = parent.getEditedModelTrack();
    int activeClipId = parent.getActiveClipId();
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      TrackModel tm = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId >= 0 && activeClipId < tm.getClips().size()) {
        ClipModel cm = tm.getClips().get(activeClipId);
        StepData sd = parent.getClipStep(cm, visualModelRow, activeCol);
        if (sd != null) {
          fill = sd.fill();
          nudge = sd.nudge();
        }
      }
    }
    double prob = bridge.getStepProbability(engineRow, activeCol);
    double gate = bridge.getGate(engineRow, activeCol);
    applyStepProperties(controller, row, col, vel, iter, fill, prob, gate, nudge);
  }

  public static void saveStepProbability(
      ClipEditorController controller, int row, int col, double prob) {
    SwingGridPanel parent = controller.parent;
    BridgeContract bridge = parent.getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int activeCol = parent.getActiveCol(row, col);
    int baseTrackId = parent.getBaseTrackId();
    int engineRow = baseTrackId + engineRowOffset;
    boolean isSynthMode =
        bridge.getTrackType(baseTrackId) == 1 || bridge.getTrackType(baseTrackId) == 0;
    int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;

    StepData oldStep = null;
    TrackModel tModel = null;
    ClipModel cModel = null;
    int editedModelTrack = parent.getEditedModelTrack();
    int activeClipId = parent.getActiveClipId();
    if (parent.getProjectModel() != null
        && editedModelTrack < parent.getProjectModel().getTracks().size()) {
      tModel = parent.getProjectModel().getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
        oldStep = parent.getClipStep(cModel, visualModelRow, activeCol);
      }
    }

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      int baseCol = activeCol % 16;
      int trackLen = cModel.getStepCount();
      java.util.List<Consequence> steps = new java.util.ArrayList<>();
      for (int c = baseCol; c < trackLen; c += 16) {
        StepData oldSt = parent.getClipStep(cModel, visualModelRow, c);
        if (oldSt.active()) {
          if (bridge != null) {
            bridge.setStepProbability(engineRow, c, prob);
          }

          double vel = bridge != null ? bridge.getVelocity(engineRow, c) : oldSt.velocity();
          double gate = bridge != null ? bridge.getGate(engineRow, c) : oldSt.gate();
          int iter = bridge != null ? bridge.getIterance(engineRow, c) : oldSt.iterance();
          double fill = bridge != null ? bridge.getStepFill(engineRow, c) : oldSt.fill();
          StepData newStep =
              new StepData(true, (float) vel, (float) gate, (float) prob, pitch, iter, (float) fill);
          parent.setClipStep(cModel, visualModelRow, c, newStep);
          if (oldSt != null && parent.getProjectModel() != null) {
            steps.add(
                new Consequence.StepConsequence(
                    parent.getProjectModel(),
                    editedModelTrack,
                    activeClipId,
                    visualModelRow,
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
      if (bridge != null) {
        bridge.setStepProbability(engineRow, activeCol, prob);
      }

      if (cModel != null) {
        boolean st = bridge != null ? bridge.getStep(engineRow, activeCol) : true;
        double vel = bridge != null ? bridge.getVelocity(engineRow, activeCol) : 0.8;
        double gate = bridge != null ? bridge.getGate(engineRow, activeCol) : 0.9;
        int iter = bridge != null ? bridge.getIterance(engineRow, activeCol) : 0;
        double fill = bridge != null ? bridge.getStepFill(engineRow, activeCol) : 0.0;
        StepData newStep =
            new StepData(st, (float) vel, (float) gate, (float) prob, pitch, iter, (float) fill);
        parent.setClipStep(cModel, visualModelRow, activeCol, newStep);
        if (oldStep != null && parent.getProjectModel() != null) {
          parent
              .getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      parent.getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      visualModelRow,
                      activeCol,
                      oldStep,
                      parent.getClipStep(cModel, visualModelRow, activeCol)));
        }
      }
    }
  }
}
