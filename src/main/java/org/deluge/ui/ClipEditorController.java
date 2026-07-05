package org.deluge.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.GlobalEffectable;
import org.deluge.hid.FirmwareDisplay;
import org.deluge.model.ClipModel;
import org.deluge.model.Consequence;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.PreferencesManager;
import org.rtmidijava.RtMidiOut;

/**
 * Controller that encapsulates all step-sequencer Clip Editor interaction, advanced gestures (via
 * DelugeGestureCoordinator), and shift shortcut panels, keeping the main SwingGridPanel clean.
 */
public class ClipEditorController {
  final SwingGridPanel parent;
  final Runnable refreshCallback;
  final Runnable projectChangedCallback;

  // Gesture coordinator & listener
  private DelugeGestureCoordinator gestureCoordinator;

  // Clipboard & Preview States
  StepData copiedStep = null;
  private int clonePreviewStartRow = -1;
  private int clonePreviewStartCol = -1;
  private int clonePreviewCurrentRow = -1;
  private int clonePreviewCurrentCol = -1;

  // Active shift rotary encoder parameter state
  private String activeShiftParam = null;
  private int activeShiftRow = -1;
  private int activeShiftCol = -1;

  // Shift shortcuts labels & colors (copied from SwingGridPanel)
  private static final String[][] SHIFT_LABELS = SwingGridPanel.SHIFT_LABELS;
  private static final Color[][] SHIFT_COLORS = SwingGridPanel.SHIFT_COLORS;

  public ClipEditorController(
      SwingGridPanel parent, Runnable refreshCallback, Runnable projectChangedCallback) {
    this.parent = parent;
    this.refreshCallback = refreshCallback;
    this.projectChangedCallback = projectChangedCallback;

    // Initialize gesture coordinator with our inner listener
    this.gestureCoordinator = new DelugeGestureCoordinator(parent, new ControllerGestureListener());
  }

  // --- Getters for rendering/view queries ---

  public StepData getCopiedStep() {
    return copiedStep;
  }

  public int getClonePreviewStartRow() {
    return clonePreviewStartRow;
  }

  public int getClonePreviewStartCol() {
    return clonePreviewStartCol;
  }

  public int getClonePreviewCurrentRow() {
    return clonePreviewCurrentRow;
  }

  public int getClonePreviewCurrentCol() {
    return clonePreviewCurrentCol;
  }

  public String getActiveShiftParam() {
    return activeShiftParam;
  }

  public int getActiveShiftRow() {
    return activeShiftRow;
  }

  public int getActiveShiftCol() {
    return activeShiftCol;
  }

  public void resetActiveShiftParam() {
    this.activeShiftParam = null;
    this.activeShiftRow = -1;
    this.activeShiftCol = -1;
  }

  void setActiveShiftParam(String param, int row, int col) {
    this.activeShiftParam = param;
    this.activeShiftRow = row;
    this.activeShiftCol = col;
  }

  // --- Private Bridge/Model Shortcuts ---

  private BridgeContract getBridge() {
    return parent.getBridge();
  }

  ProjectModel getProjectModel() {
    return parent.getProjectModel();
  }

  int getEditedModelTrack() {
    return parent.getEditedModelTrack();
  }

  private int getActiveClipId() {
    return parent.getActiveClipId();
  }

  private int getBaseTrackId() {
    return parent.getBaseTrackId();
  }

  private int getScrollOffset() {
    return parent.getScrollOffset();
  }

  private int getScrollOffsetX() {
    return parent.getScrollOffsetX();
  }

  private int getStepCount() {
    return parent.getStepCount();
  }

  private Color[] getTrackColors() {
    return parent.getTrackColors();
  }

  private RtMidiOut getMidiOut() {
    return parent.getMidiOut();
  }

  // --- Main Listener Attachment ---

  /** Attaches the appropriate mouse/gesture listeners to a step pad button. */
  public void attachListeners(
      final JButton clipBtn, final int modelRow, final int visualRow, final int colId) {
    boolean isAdvanced =
        PreferencesManager.getGridPanelType() == PreferencesManager.GridPanelType.ADVANCED;

    if (isAdvanced && parent.isStepColumn(colId)) {
      // Advanced mode: delegate completely to gesture coordinator
      java.awt.event.MouseAdapter gestureAdapter =
          gestureCoordinator.createMouseAdapter(visualRow, colId);
      clipBtn.addMouseListener(gestureAdapter);
      clipBtn.addMouseMotionListener(gestureAdapter);
    } else {
      // Standard mode: simple mouse pressed/released triggers
      clipBtn.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              // Support shift-click bypass in constructor/static layouts
              if (parent.isShiftHeld() && visualRow < 8 && parent.isStepColumn(colId)) {
                parent.handleShiftClick(visualRow, colId, e.getPoint(), e.getComponent());
                return;
              }

              BridgeContract bridge = getBridge();
              ProjectModel projectModel = getProjectModel();
              int editedModelTrack = getEditedModelTrack();
              int activeClipId = getActiveClipId();
              int baseTrackId = getBaseTrackId();

              if (SwingUtilities.isRightMouseButton(e)) {
                handleStepLongPressed(visualRow, colId, e.getLocationOnScreen());
              } else if (SwingUtilities.isLeftMouseButton(e)) {
                if (bridge == null) return;
                boolean isSynthMode = isSynthOrKit(bridge.getTrackType(baseTrackId));
                int trackType = bridge.getTrackType(baseTrackId);

                if (trackType == 2) {
                  // MIDI Track
                  StepData oldStep = null;
                  if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                    TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
                    if (activeClipId < tModel.getClips().size()) {
                      oldStep =
                          parent.getClipStep(tModel.getClips().get(activeClipId), modelRow, colId);
                    }
                  }
                  boolean st = bridge.getStep(baseTrackId + modelRow, colId);
                  bridge.setStep(baseTrackId + modelRow, colId, !st);
                  if (!st && getMidiOut() != null) {
                    parent.sendMidiNote(60 + modelRow, 100, 250); // preview
                  }
                  clipBtn.setBackground(!st ? getTrackColors()[6] : new Color(0x33, 0x33, 0x33));

                  if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                    TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
                    if (activeClipId < tModel.getClips().size()) {
                      ClipModel cModel = tModel.getClips().get(activeClipId);
                      parent.setClipStep(
                          cModel,
                          modelRow,
                          colId,
                          StepData.of(!st, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, 0));
                      if (oldStep != null) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.StepConsequence(
                                    projectModel,
                                    editedModelTrack,
                                    activeClipId,
                                    modelRow,
                                    colId,
                                    oldStep,
                                    parent.getClipStep(cModel, modelRow, colId)));
                      }
                    }
                  }
                } else if (isSynthMode) {
                  // Synth Track
                  int engineRow = baseTrackId + modelRow;
                  StepData oldStep = null;
                  if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                    TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
                    if (activeClipId < tModel.getClips().size()) {
                      oldStep = tModel.getClips().get(activeClipId).getStep(modelRow, colId);
                    }
                  }
                  boolean stepState = bridge.getStep(engineRow, colId);
                  bridge.setStep(engineRow, colId, !stepState);
                  double velS = bridge.getVelocity(engineRow, colId);
                  clipBtn.setBackground(
                      !stepState
                          ? parent.velocityBlend(
                              getTrackColors()[modelRow % getTrackColors().length], velS)
                          : new Color(0x33, 0x33, 0x33));

                  // Audition via engine preview
                  int slot = baseTrackId + (modelRow % 8);
                  int midiPitch = parent.getRowPitch(modelRow);
                  bridge.setGlobalFloat(BridgeContract.G_PREVIEW_PITCH, (float) midiPitch);
                  bridge.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) slot);
                  bridge.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                  try {
                    String[] noteNames = {
                      "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
                    };
                    String nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
                    FirmwareDisplay.get().getVirtualOLED().setNoteOverride(nName);
                  } catch (Throwable th) {
                    // Shield
                  }

                  if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                    TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
                    if (activeClipId < tModel.getClips().size()) {
                      ClipModel cModel = tModel.getClips().get(activeClipId);
                      double curVel = bridge.getVelocity(engineRow, colId);
                      double curProb = bridge.getStepProbability(engineRow, colId);
                      cModel.setStep(
                          modelRow,
                          colId,
                          StepData.of(
                              !stepState,
                              (float) curVel,
                              StepData.DEFAULT_CLICK_GATE,
                              (float) curProb,
                              midiPitch));
                      if (oldStep != null) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.StepConsequence(
                                    projectModel,
                                    editedModelTrack,
                                    activeClipId,
                                    modelRow,
                                    colId,
                                    oldStep,
                                    cModel.getStep(modelRow, colId)));
                      }
                    }
                  }
                } else {
                  // Drum Track
                  if (parent.isStepColumn(colId)) {
                    // Step pad: Toggle step + Audition
                    int engineRow = baseTrackId + modelRow;
                    StepData oldStep = null;
                    if (projectModel != null
                        && editedModelTrack < projectModel.getTracks().size()) {
                      TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
                      if (activeClipId < tModel.getClips().size()) {
                        ClipModel cModel = tModel.getClips().get(activeClipId);
                        oldStep = parent.getClipStep(cModel, modelRow, colId);
                      }
                    }
                    boolean stepState = bridge.getStep(engineRow, colId);
                    boolean nextState = !stepState;
                    bridge.setStep(engineRow, colId, nextState);
                    double velK;
                    double probK;
                    if (nextState) {
                      velK =
                          (oldStep != null && oldStep.velocity() > 0.0f) ? oldStep.velocity() : 0.8;
                      probK = (oldStep != null) ? oldStep.probability() : 1.0;
                    } else {
                      velK = bridge.getVelocity(engineRow, colId);
                      probK = bridge.getStepProbability(engineRow, colId);
                    }
                    clipBtn.setBackground(
                        nextState
                            ? parent.getGridNoteColor(modelRow, (float) velK)
                            : parent.getStepPadDefaultBg(modelRow, colId));

                    // Trigger the drum sound for audition
                    try {
                      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
                        if (editedModelTrack < fwEngine.sounds.size()
                            && !parent.isSequencerPlaying()) {
                          GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
                          if (sound instanceof FirmwareKit kit) {
                            if (modelRow < kit.drumSounds.size()) kit.triggerDrum(modelRow, 127);
                          }
                        }
                      }
                    } catch (Exception ignored) {
                    }

                    if (projectModel != null
                        && editedModelTrack < projectModel.getTracks().size()) {
                      TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
                      if (activeClipId < tModel.getClips().size()) {
                        ClipModel cModel = tModel.getClips().get(activeClipId);
                        StepData newStep =
                            StepData.of(
                                nextState,
                                (float) velK,
                                StepData.DEFAULT_CLICK_GATE,
                                (float) probK,
                                0);
                        parent.setClipStep(cModel, modelRow, colId, newStep);
                        if (oldStep != null) {
                          projectModel
                              .getUndoRedoStack()
                              .push(
                                  new Consequence.StepConsequence(
                                      projectModel,
                                      editedModelTrack,
                                      activeClipId,
                                      modelRow,
                                      colId,
                                      oldStep,
                                      parent.getClipStep(cModel, modelRow, colId)));
                        }
                      }
                    }
                    projectChangedCallback.run();
                  } else {
                    // Audition pad (col >= 16): Audition on press, no step toggle
                    try {
                      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
                        if (editedModelTrack < fwEngine.sounds.size()
                            && !parent.isSequencerPlaying()) {
                          GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
                          if (sound instanceof FirmwareKit kit) {
                            if (modelRow < kit.drumSounds.size()) kit.triggerDrum(modelRow, 127);
                          }
                        }
                      }
                    } catch (Exception ignored) {
                    }
                  }
                }
              }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
              BridgeContract bridge = getBridge();
              if (bridge == null) return;

              // Stop kit preview on release
              bridge.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, -1L);
              bridge.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

              try {
                Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
                  int editedModelTrack = getEditedModelTrack();
                  if (editedModelTrack < fwEngine.sounds.size()) {
                    GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
                    if (sound instanceof FirmwareKit kit) {
                      if (modelRow < kit.drumSounds.size()) {
                        kit.drumSounds.get(modelRow).releaseNote(60);
                      }
                    } else if (sound instanceof FirmwareSound synth) {
                      boolean isSynthModeLocal =
                          isSynthOrKit(bridge.getTrackType(getBaseTrackId()));
                      int pitchMidi = isSynthModeLocal ? (((128 - 1) - modelRow) + 0) : 60;
                      synth.releaseNote(pitchMidi);
                    }
                  }
                }
              } catch (Exception ignored) {
              }

              try {
                FirmwareDisplay.get().getVirtualOLED().clearNoteOverride();
              } catch (Throwable th) {
                // Shield
              }
            }
          });
    }
  }

  // --- Gesture Coordinator Listener Implementation ---

  private class ControllerGestureListener implements DelugeGestureCoordinator.GestureListener {
    @Override
    public void onStepPressed(int row, int col) {
      handleStepPressed(row, col);
    }

    @Override
    public void onStepReleased(int row, int col) {
      handleStepReleased(row, col);
    }

    @Override
    public void onStepToggled(int row, int col) {
      handleStepToggled(row, col);
    }

    @Override
    public void onStepLongPressed(int row, int col, Point screenPos) {
      handleStepLongPressed(row, col, screenPos);
    }

    @Override
    public void onStepTied(int row, int colStart, int colEnd) {
      handleStepTied(row, colStart, colEnd);
    }

    @Override
    public void onDragPreview(int row, int colStart, int colCurrent) {
      handleDragPreview(row, colStart, colCurrent);
    }

    @Override
    public void onDragCleared() {
      handleDragCleared();
    }
  }

  // --- Core Gesture Handler Methods ---

  void handleStepPressed(int row, int col) {
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int baseTrackId = getBaseTrackId();
    boolean isSynthMode = isSynthOrKit(bridge.getTrackType(baseTrackId));
    int trackType = bridge.getTrackType(baseTrackId);

    if (trackType == 2) {
      if (getMidiOut() != null) {
        parent.sendMidiNote(60 + visualModelRow, 100, 250);
      }
    } else if (isSynthMode) {
      int pitchMidi = parent.getRowPitch(visualModelRow);
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
        int editedModelTrack = getEditedModelTrack();
        if (editedModelTrack < fwEngine.sounds.size() && !parent.isSequencerPlaying()) {
          GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof FirmwareSound synth) {
            synth.triggerNote(pitchMidi, 127);
          }
        }
      }
    } else {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
        int editedModelTrack = getEditedModelTrack();
        if (editedModelTrack < fwEngine.sounds.size() && !parent.isSequencerPlaying()) {
          GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof FirmwareKit kit) {
            kit.triggerDrum(engineRowOffset, 127);
          }
        }
      }
    }
  }

  private void handleStepReleased(int row, int col) {
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);

    Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
      int editedModelTrack = getEditedModelTrack();
      if (editedModelTrack < fwEngine.sounds.size()) {
        GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
        if (sound instanceof FirmwareKit kit) {
          if (engineRowOffset < kit.drumSounds.size()) {
            kit.drumSounds.get(engineRowOffset).releaseNote(60);
          }
        } else if (sound instanceof FirmwareSound synth) {
          int pitchMidi = parent.getRowPitch(visualModelRow);
          synth.releaseNote(pitchMidi);
        }
      }
    }
  }

  public void handleStepToggled(int row, int col) {
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int activeCol = parent.getActiveCol(row, col);
    int baseTrackId = getBaseTrackId();
    boolean isSynthMode = isSynthOrKit(bridge.getTrackType(baseTrackId));
    int engineRow = baseTrackId + engineRowOffset;

    StepData oldStep = null;
    ClipModel cModel = null;
    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();
    if (getProjectModel() != null && editedModelTrack < getProjectModel().getTracks().size()) {
      TrackModel tModel = getProjectModel().getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
        oldStep = parent.getClipStep(cModel, visualModelRow, activeCol);
      }
    }

    boolean stepState = bridge.getStep(engineRow, activeCol);
    boolean nextState = !stepState;

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      int baseCol = activeCol % 16;
      java.util.List<Consequence> steps = new java.util.ArrayList<>();
      int trackLen = cModel.getStepCount();
      int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
      for (int c = baseCol; c < trackLen; c += 16) {
        StepData oldSt = parent.getClipStep(cModel, visualModelRow, c);
        bridge.setStep(engineRow, c, nextState);
        StepData newSt =
            StepData.of(
                nextState,
                (float) bridge.getVelocity(engineRow, c),
                StepData.DEFAULT_CLICK_GATE,
                (float) bridge.getStepProbability(engineRow, c),
                pitch);
        parent.setClipStep(cModel, visualModelRow, c, newSt);
        if (oldSt != null && getProjectModel() != null) {
          steps.add(
              new Consequence.StepConsequence(
                  getProjectModel(),
                  editedModelTrack,
                  activeClipId,
                  visualModelRow,
                  c,
                  oldSt,
                  newSt));
        }
      }
      if (!steps.isEmpty() && getProjectModel() != null) {
        getProjectModel()
            .getUndoRedoStack()
            .push(new Consequence.CompoundConsequence("Toggle Step Wrap", steps));
      }
    } else {
      bridge.setStep(engineRow, activeCol, nextState);
      if (cModel != null) {
        double curVel = bridge.getVelocity(engineRow, activeCol);
        double curProb = bridge.getStepProbability(engineRow, activeCol);
        int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
        StepData newStep =
            StepData.of(
                nextState, (float) curVel, StepData.DEFAULT_CLICK_GATE, (float) curProb, pitch);
        parent.setClipStep(cModel, visualModelRow, activeCol, newStep);

        if (oldStep != null && getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      visualModelRow,
                      activeCol,
                      oldStep,
                      newStep));
        }
      }
    }
    projectChangedCallback.run();
  }

  private void handleStepLongPressed(int row, int col, Point screenPos) {
    StepPropertiesEditor.handleStepLongPressed(this, row, col, screenPos);
  }

  void handleStepTied(int row, int colStart, int colEnd) {
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int start = Math.min(colStart, colEnd);
    int end = Math.max(colStart, colEnd);

    int startModelCol = parent.getActiveCol(row, start);
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int baseTrackId = getBaseTrackId();
    int engineRow = baseTrackId + engineRowOffset;
    boolean isSynthMode = isSynthOrKit(bridge.getTrackType(baseTrackId));

    boolean startActive = bridge.getStep(engineRow, startModelCol);
    double vel = startActive ? bridge.getVelocity(engineRow, startModelCol) : 0.8;
    double prob = startActive ? bridge.getStepProbability(engineRow, startModelCol) : 1.0;
    int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
    int iter = startActive ? bridge.getIterance(engineRow, startModelCol) : 0;
    double fill = startActive ? bridge.getStepFill(engineRow, startModelCol) : 0.0;

    TrackModel tModel = null;
    ClipModel cModel = null;
    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();
    if (getProjectModel() != null && editedModelTrack < getProjectModel().getTracks().size()) {
      tModel = getProjectModel().getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
      }
    }

    float newGate = (end - start) + 0.9f;

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      int baseStart = start % 16;
      int baseEnd = end % 16;
      int trackLen = cModel.getStepCount();
      java.util.List<Consequence> steps = new java.util.ArrayList<>();

      for (int offset = 0; offset < trackLen; offset += 16) {
        int sCol = offset + baseStart;
        int eCol = offset + baseEnd;

        // Apply start tie step
        StepData oldStart = parent.getClipStep(cModel, visualModelRow, sCol);
        boolean sSt = true;
        StepData startStep =
            new StepData(sSt, (float) vel, newGate, (float) prob, pitch, iter, (float) fill);
        parent.setClipStep(cModel, visualModelRow, sCol, startStep);

        if (bridge != null) {
          bridge.setStep(engineRow, sCol, true);
          bridge.setVelocity(engineRow, sCol, vel);
          bridge.setGate(engineRow, sCol, (double) newGate);
          bridge.setStepProbability(engineRow, sCol, prob);
          bridge.setIterance(engineRow, sCol, iter);
          bridge.setStepFill(engineRow, sCol, fill);
          bridge.setStepNudge(engineRow, sCol, 0.0);
        }

        if (oldStart != null && getProjectModel() != null) {
          steps.add(
              new Consequence.StepConsequence(
                  getProjectModel(),
                  editedModelTrack,
                  activeClipId,
                  visualModelRow,
                  sCol,
                  oldStart,
                  startStep));
        }

        // Apply tail clear steps
        for (int c = sCol + 1; c <= eCol; c++) {
          if (c < trackLen) {
            StepData oldStep = parent.getClipStep(cModel, visualModelRow, c);
            StepData newStep = new StepData(false, 0.8f, 0.0f, 1.0f, 0, 0, 0.0f);
            parent.setClipStep(cModel, visualModelRow, c, newStep);

            if (bridge != null) {
              bridge.setStep(engineRow, c, false);
              bridge.setGate(engineRow, c, 0.0);
            }

            if (oldStep != null && getProjectModel() != null && oldStep.active()) {
              steps.add(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      visualModelRow,
                      c,
                      oldStep,
                      newStep));
            }
          }
        }
      }

      if (!steps.isEmpty() && getProjectModel() != null) {
        getProjectModel()
            .getUndoRedoStack()
            .push(new Consequence.CompoundConsequence("Tie Step Wrap", steps));
      }
    } else {
      bridge.setStep(engineRow, startModelCol, true);
      bridge.setVelocity(engineRow, startModelCol, vel);
      bridge.setGate(engineRow, startModelCol, (double) newGate);
      bridge.setStepProbability(engineRow, startModelCol, prob);
      bridge.setIterance(engineRow, startModelCol, iter);
      bridge.setStepFill(engineRow, startModelCol, fill);
      bridge.setStepNudge(engineRow, startModelCol, 0.0);

      if (cModel != null) {
        StepData oldStart = parent.getClipStep(cModel, visualModelRow, startModelCol);
        boolean startSt = true;
        StepData startStep =
            new StepData(startSt, (float) vel, newGate, (float) prob, pitch, iter, (float) fill);
        parent.setClipStep(cModel, visualModelRow, startModelCol, startStep);
        if (oldStart != null && getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      visualModelRow,
                      startModelCol,
                      oldStart,
                      startStep));
        }
      }

      for (int c = start + 1; c <= end; c++) {
        int activeCol = parent.getActiveCol(row, c);
        bridge.setStep(engineRow, activeCol, false);
        bridge.setGate(engineRow, activeCol, 0.0);

        if (cModel != null) {
          StepData oldStep = parent.getClipStep(cModel, visualModelRow, activeCol);
          StepData newStep = new StepData(false, 0.8f, 0.0f, 1.0f, 0, 0, 0.0f);
          parent.setClipStep(cModel, visualModelRow, activeCol, newStep);
          if (oldStep != null && getProjectModel() != null && oldStep.active()) {
            getProjectModel()
                .getUndoRedoStack()
                .push(
                    new Consequence.StepConsequence(
                        getProjectModel(),
                        editedModelTrack,
                        activeClipId,
                        visualModelRow,
                        activeCol,
                        oldStep,
                        newStep));
          }
        }
      }
    }

    projectChangedCallback.run();
    refreshCallback.run();
  }

  private void handleDragPreview(int row, int colStart, int colCurrent) {
    int start = Math.min(colStart, colCurrent);
    int end = Math.max(colStart, colCurrent);
    int rowsToScan =
        (parent.getViewMode() == SwingGridPanel.GridViewMode.CLIP)
            ? parent.getVoiceRowCount()
            : parent.getGridModeRows();

    int visRow = -1;
    for (int t = 0; t < rowsToScan; t++) {
      if (t == row) {
        visRow = t;
        break;
      }
    }
    if (visRow == -1) return;

    JButton[][] pads = parent.getPads();
    int columnCount = parent.getColumnCount();
    BridgeContract bridge = getBridge();
    int baseTrackId = getBaseTrackId();

    for (int c = 0; c < columnCount; c++) {
      if (pads[visRow][c] instanceof DelugePadButton pad) {
        boolean tiedInModel = false;
        int modelRow = parent.getModelRow(row);
        int activeCol = parent.getActiveCol(row, c);
        if (bridge != null) {
          int engineRow = baseTrackId + modelRow;
          tiedInModel = bridge.getGate(engineRow, activeCol) >= 0.99;
        }
        boolean isCurrentActive = parent.isStepActive(modelRow, activeCol) || (c == start);
        pad.setActive(isCurrentActive);
        pad.setTail((tiedInModel || (c > start && c <= end)) && !isCurrentActive);
      }
    }
  }

  public void handleShiftHover(int row, int col) {
    if (row < 0 || row >= 8 || col < 0 || col >= 16) return;
    String param = SHIFT_LABELS[row][col];
    if (param == null || param.isEmpty()) return;

    if (getProjectModel() == null || getEditedModelTrack() >= getProjectModel().getTracks().size())
      return;
    TrackModel genericTrack = getProjectModel().getTracks().get(getEditedModelTrack());

    boolean applicable = isParamApplicable(param, row, col, genericTrack);
    if (!applicable) {
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay("----", "----");
      }
      return;
    }

    String code = getParamShortCode(param);
    String valStr = getParamFormattedValue(param, row, col);
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
    }
  }

  public void handleShiftHoverExit() {
    if (activeShiftParam != null) {
      String code = getParamShortCode(activeShiftParam);
      String valStr = getParamFormattedValue(activeShiftParam, activeShiftRow, activeShiftCol);
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
      }
    } else {
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay(null, null);
      }
    }
  }

  public void setClonePreview(int startR, int startC, int currR, int currC) {
    this.clonePreviewStartRow = startR;
    this.clonePreviewStartCol = startC;
    this.clonePreviewCurrentRow = currR;
    this.clonePreviewCurrentCol = currC;
    refreshCallback.run();
  }

  public void duplicateStep(int startRow, int startCol, int targetRow, int targetCol) {
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int trackLen = bridge.getTrackLength(getBaseTrackId());
    int baseTrackId = getBaseTrackId();
    int scrollOffset = getScrollOffset();
    int scrollOffsetX = getScrollOffsetX();
    int stepCount = getStepCount();
    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();

    int srcRow =
        baseTrackId
            + (parent.getViewMode() == SwingGridPanel.GridViewMode.CLIP
                ? scrollOffset + startRow
                : startRow);
    int srcCol = (trackLen < stepCount) ? (startCol % trackLen) : (startCol + scrollOffsetX);

    int dstRow =
        baseTrackId
            + (parent.getViewMode() == SwingGridPanel.GridViewMode.CLIP
                ? scrollOffset + targetRow
                : targetRow);
    int dstCol = (trackLen < stepCount) ? (targetCol % trackLen) : (targetCol + scrollOffsetX);

    // Read step parameters
    boolean active = bridge.getStep(srcRow, srcCol);
    double vel = bridge.getVelocity(srcRow, srcCol);
    double gate = bridge.getGate(srcRow, srcCol);
    int pitch = bridge.getPitch(srcRow, srcCol);
    double prob = bridge.getStepProbability(srcRow, srcCol);
    double fill = bridge.getStepFill(srcRow, srcCol);
    double nudge = bridge.getStepNudge(srcRow, srcCol);
    double stepFilterVal = bridge.getStepFilter(srcRow, srcCol);
    double stepResVal = bridge.getStepRes(srcRow, srcCol);
    double stepPanVal = bridge.getStepPan(srcRow, srcCol);
    double stepDelayVal = bridge.getStepDelay(srcRow, srcCol);
    double stepReverbVal = bridge.getStepReverb(srcRow, srcCol);

    // Save to target step
    bridge.setStep(dstRow, dstCol, active);
    bridge.setVelocity(dstRow, dstCol, vel);
    bridge.setGate(dstRow, dstCol, gate);
    bridge.setPitch(dstRow, dstCol, pitch);
    bridge.setStepProbability(dstRow, dstCol, prob);
    bridge.setStepFill(dstRow, dstCol, fill);
    bridge.setStepNudge(dstRow, dstCol, nudge);
    bridge.setStepFilter(dstRow, dstCol, stepFilterVal);
    bridge.setStepRes(dstRow, dstCol, stepResVal);
    bridge.setStepPan(dstRow, dstCol, stepPanVal);
    bridge.setStepDelay(dstRow, dstCol, stepDelayVal);
    bridge.setStepReverb(dstRow, dstCol, stepReverbVal);

    // Push Undo/Redo step copy event!
    ArrayList<Consequence> steps = new ArrayList<>();
    steps.add(
        new Consequence.StepConsequence(
            getProjectModel(),
            editedModelTrack,
            activeClipId,
            targetRow,
            targetCol,
            StepData.of(active, (float) vel, (float) gate, (float) prob, (int) fill),
            StepData.empty()));
    if (getProjectModel() != null) {
      getProjectModel()
          .getUndoRedoStack()
          .push(new Consequence.CompoundConsequence("Clone step to " + targetCol, steps));
    }

    projectChangedCallback.run();
    refreshCallback.run();
  }

  public void hotSwapTrackSample(int modelRow, int visibleRow, java.io.File soundFile) {
    if (getProjectModel() == null) return;
    java.util.List<TrackModel> tracks = getProjectModel().getTracks();
    if (modelRow < 0 || modelRow >= tracks.size()) return;

    TrackModel track = tracks.get(modelRow);

    if (parent.getViewMode() == SwingGridPanel.GridViewMode.CLIP
        && track instanceof KitTrackModel kit) {
      java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
      int drumIdx = sounds.size() - 1 - visibleRow;
      if (drumIdx >= 0 && drumIdx < sounds.size()) {
        org.deluge.model.Drum drum = sounds.get(drumIdx);
        if (drum instanceof org.deluge.model.SoundDrum soundDrum) {
          soundDrum.setSamplePath(soundFile.getAbsolutePath());
        }
        if (getBridge() != null) {
          getBridge().setSamplePath(modelRow, soundFile.getAbsolutePath());
        }
        if (SwingDelugeApp.mainInstance != null) {
          SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SMPL", "SWAP");
        }
        projectChangedCallback.run();
        refreshCallback.run();
      }
    } else if (track instanceof org.deluge.model.AudioTrackModel audioTrack) {
      if (audioTrack.getAudioClips().isEmpty()) {
        org.deluge.model.AudioTrackModel.AudioClip clip =
            new org.deluge.model.AudioTrackModel.AudioClip();
        clip.setFilePath(soundFile.getAbsolutePath());
        audioTrack.getAudioClips().add(clip);
      } else {
        audioTrack.getAudioClips().get(0).setFilePath(soundFile.getAbsolutePath());
      }
      if (getBridge() != null) {
        getBridge().setSamplePath(modelRow, soundFile.getAbsolutePath());
      }
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SMPL", "SWAP");
      }
      projectChangedCallback.run();
      refreshCallback.run();
    }
  }

  public void handleShiftClick(int row, int col, Point localPos, Component comp) {
    ShiftClickController.handleShiftClick(this, row, col, localPos, comp);
  }

  // --- Helper Math & Parsing Methods (Internal to controller) ---

  public String getShiftShortcutTooltip(int row, int col, boolean applicable, TrackModel track) {
    String paramName = SHIFT_LABELS[row][col];
    if (paramName == null || paramName.isEmpty()) {
      return null;
    }
    String prefix = getGroupPrefix(col);
    String fullParam = (prefix != null) ? prefix + " " + paramName : paramName;

    String description = getParamDescription(paramName);
    String trackTypeStr =
        (track instanceof SynthTrackModel)
            ? "Synth Track"
            : (track instanceof KitTrackModel ? "Kit Track" : "Audio Track");
    String header =
        applicable
            ? ""
            : "<font color='#ff6666'><b>[NOT APPLICABLE FOR "
                + trackTypeStr.toUpperCase()
                + "]</b></font><br>";

    return String.format(
        "<html><body style='font-size: 9px; font-family: sans-serif; width: 180px;'>"
            + "%s"
            + "<b>Shift Shortcut: %s</b><br>"
            + "• Group: %s<br>"
            + "• Parameter: <b>%s</b><br>"
            + "• Action: %s"
            + "</body></html>",
        header, fullParam, (prefix != null ? prefix : "None"), paramName, description);
  }

  private String getParamDescription(String paramName) {
    switch (paramName) {
      case "WAVE FORM":
        return "Selects waveform shape (SINE, TRIANGLE, SAW, SQUARE, WAVETABLE).";
      case "NOISE":
        return "Toggles or adjusts white noise level.";
      case "OSC SYNC":
        return "Toggles hard oscillator pitch synchronization.";
      case "DIRECTION":
        return "Adjusts sample playback direction (Forward vs Reverse).";
      case "SATURATE":
        return "Applies analog saturation distortion gain.";
      case "CUTOFF":
        return "Adjusts Lowpass Filter Cutoff frequency (20Hz - 20kHz).";
      case "RESONANCE":
        return "Adjusts Lowpass Filter Resonance Q feedback factor.";
      case "HPF CUTOFF":
        return "Adjusts Highpass Filter Cutoff frequency.";
      case "HPF RES":
        return "Adjusts Highpass Filter Resonance Q feedback.";
      case "ATTACK":
        return "Adjusts Envelope Attack duration time (seconds).";
      case "DECAY":
        return "Adjusts Envelope Decay duration time.";
      case "SUSTAIN":
        return "Adjusts Envelope Sustain level height percentage.";
      case "RELEASE":
        return "Adjusts Envelope Release duration time.";
      case "SPEED":
        return "Adjusts LFO Speed rate frequency (Hz or subdivisions).";
      case "DEPTH":
        return "Adjusts LFO Modulation depth amount.";
      case "FEEDBACK":
        return "Adjusts Delay feedback or Mod FX regeneration level.";
      case "DELAY SEND":
        return "Adjusts master Delay send bus amount.";
      case "REVERB SEND":
        return "Adjusts master Reverb send bus amount.";
      case "VOLUME":
        return "Adjusts master channel volume level (0.0 - 1.5 multiplier).";
      case "PAN":
        return "Adjusts stereo balance panning position.";
      case "PITCH":
        return "Shifts note pitches (semitones / cents).";
      case "ARPRATE":
        return "Adjusts Arpeggiator rate clock speed.";
      case "GATE":
        return "Adjusts step gate length or Arp gate duration.";
      case "VELOCITY":
        return "Adjusts key trigger strike velocity.";
      default:
        return "Selects or adjusts the " + paramName.toLowerCase() + " parameter.";
    }
  }

  public boolean isParamApplicable(String param, int row, int col, TrackModel track) {
    if (track == null || param == null || param.isEmpty()) return false;
    if (track instanceof SynthTrackModel) {
      return true;
    }
    switch (param) {
      case "LEVEL":
        return (row == 7 && col == 6);
      case "PAN":
        return (row == 4 && col == 6);
      case "SIZE":
        return (row == 0 && col == 13);
      case "RATE":
        return (row == 7 && col == 14);
      case "SDCHAIN":
        return (row == 3 && col == 15);
      default:
        return false;
    }
  }

  public String getGroupPrefix(int colId) {
    if (colId == 0) return "S1";
    if (colId == 1) return "S2";
    if (colId == 2) return "OSC1";
    if (colId == 3) return "OSC2";
    if (colId == 4) return "FM1";
    if (colId == 5) return "FM2";
    if (colId == 8) return "ENV1";
    if (colId == 9) return "ENV2";
    if (colId == 12) return "LFO1";
    if (colId == 13) return "LFO2";
    return null;
  }

  String getParamShortCode(String param) {
    if (param == null) return "----";
    switch (param.toUpperCase()) {
      case "WAVE FORM":
        return "WAVE";
      case "INTER POLATION":
        return "INTR";
      case "BROWSE":
        return "BROW";
      case "RECORD":
        return "REC";
      case "PITCH SPEED":
        return "PTSP";
      case "SPEED":
        return "SPED";
      case "REVERSE":
        return "REV";
      case "MODE":
        return "MODE";
      case "NOISE":
        return "NOIS";
      case "OSC SYNC":
        return "SYNC";
      case "WAVETABLE":
        return "WTBL";
      case "FEED BACK":
        return "FDBK";
      case "RETRIG PHASE":
        return "RPHS";
      case "PW":
        return "PW";
      case "TYPE":
        return "TYPE";
      case "TRANS POSE":
        return "TRAN";
      case "LEVEL":
        return "LEVEL";
      case "DIRECTION":
        return "DIR";
      case "DESTI NATION":
        return "DEST";
      case "RETRIG":
        return "RTRG";
      case "SATURATE":
        return "SAT";
      case "BITCRUSH":
        return "CRSH";
      case "DECIMATE":
        return "DECI";
      case "SYNTH MODE":
        return "MODE";
      case "UNISON VOICES":
        return "UNIS";
      case "UNISON DETUNE":
        return "DETN";
      case "VOICE PRIORITY":
        return "PRIO";
      case "POLY PHONY":
        return "POLY";
      case "GLIDE":
        return "GLID";
      case "CUTOFF":
        return "CUT";
      case "RESONANCE":
        return "RES";
      case "SLOPE":
        return "SLOP";
      case "SEND":
        return "SEND";
      case "SHAPE":
        return "SHAP";
      case "ATTACK":
        return "ATK";
      case "DECAY":
        return "DECY";
      case "SUSTAIN":
        return "SUST";
      case "RELEASE":
        return "REL";
      case "VOL DUCK":
        return "DUCK";
      case "ARP MODE":
        return "AMOD";
      case "ARP OCTAVES":
        return "AOCT";
      case "ARP GATE":
        return "AGAT";
      case "ARP SYNC":
        return "ASYNC";
      case "ARP RATE":
        return "ARAT";
      case "RATE":
        return "RATE";
      case "DEPTH":
        return "DPTH";
      case "FDBACK":
        return "FDBK";
      case "OFFSET":
        return "OFST";
      case "SIZE":
        return "SIZE";
      case "DAMP":
        return "DAMP";
      case "WIDTH":
        return "WDTH";
      case "PAN":
        return "PAN";
      case "ENV 1":
        return "ENV1";
      case "ENV 2":
        return "ENV2";
      case "LFO 1":
        return "LFO1";
      case "LFO 2":
        return "LFO2";
      case "MONO/ STEREO":
        return "MSTE";
      case "AMOUNT":
        return "AMT";
      case "DIGI/ ANALOG":
        return "DIGI";
      case "SDCHAIN":
        return "SDCH";
      case "NOTE":
        return "NOTE";
      case "RANDOM":
        return "RAND";
      case "VELOCITY":
        return "VEL";
      case "AFTER TOUCH":
        return "AFTC";
      default:
        return param.length() > 4 ? param.substring(0, 4) : param;
    }
  }

  String getParamFormattedValue(String param, int row, int col) {
    if (getProjectModel() == null || getEditedModelTrack() >= getProjectModel().getTracks().size())
      return "--";
    TrackModel genericTrack = getProjectModel().getTracks().get(getEditedModelTrack());
    SynthTrackModel track =
        (genericTrack instanceof SynthTrackModel) ? (SynthTrackModel) genericTrack : null;

    final int envIdx = (col == 9) ? 1 : 0;

    switch (param) {
      case "CUTOFF":
        if (row == 0 && track != null) {
          float freq = (col == 8) ? track.getLpfFreq() : track.getHpfFreq();
          return (freq >= 1000.0f)
              ? String.format("%.1fk", freq / 1000.0f)
              : String.format("%.0f", freq);
        } else if (row == 1 && track != null) {
          float res = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          return String.format("%d%%", (int) (res * 100.0f));
        }
        break;
      case "RESONANCE":
        if (track != null) {
          float res = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          return String.format("%d%%", (int) (res * 100.0f));
        }
        break;
      case "ATTACK":
        if (track != null) {
          return String.format("%.2fs", track.getEnv(envIdx).attack());
        }
        break;
      case "DECAY":
        if (track != null) {
          return String.format("%.2fs", track.getEnv(envIdx).decay());
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          return String.format("%d%%", (int) (track.getEnv(envIdx).sustain() * 100.0f));
        }
        break;
      case "RELEASE":
        if (track != null) {
          return String.format("%.2fs", track.getEnv(envIdx).release());
        }
        break;
      case "PAN":
        if (row == 4 && col == 6) {
          int panVal = (int) (genericTrack.getPan() * 100.0f);
          if (panVal == 50) return "C";
          return (panVal < 50)
              ? String.format("L%d", 50 - panVal)
              : String.format("R%d", panVal - 50);
        }
        break;
      case "LEVEL":
        if (row == 7 && col == 6) {
          return String.format("%d%%", (int) (genericTrack.getVolume() * 100.0f));
        } else if (row == 7 && (col == 2 || col == 3) && track != null) {
          return String.format("%d%%", (int) (track.getOscMix() * 100.0f));
        }
        break;
      case "GLIDE":
        if (track != null) {
          return String.format("%.2fs", track.getPortamento());
        }
        break;
    }
    return "--";
  }

  public void adjustRotaryParameter(int delta) {
    if (activeShiftParam == null
        || getProjectModel() == null
        || getEditedModelTrack() >= getProjectModel().getTracks().size()) return;
    TrackModel genericTrack = getProjectModel().getTracks().get(getEditedModelTrack());
    SynthTrackModel track =
        (genericTrack instanceof SynthTrackModel) ? (SynthTrackModel) genericTrack : null;

    final int envIdx = (activeShiftCol == 9) ? 1 : 0;

    switch (activeShiftParam) {
      case "CUTOFF":
        if (activeShiftRow == 0 && track != null) {
          float freq = (activeShiftCol == 8) ? track.getLpfFreq() : track.getHpfFreq();
          freq = (float) (freq * Math.pow(1.05, delta));
          freq = Math.max(20.0f, Math.min(20000.0f, freq));
          if (activeShiftCol == 8) track.setLpfFreq(freq);
          else track.setHpfFreq(freq);
        } else if (activeShiftRow == 1 && track != null) {
          float res = (activeShiftCol == 8) ? track.getLpfRes() : track.getHpfRes();
          res = Math.max(0.0f, Math.min(1.0f, res + delta * 0.02f));
          if (activeShiftCol == 8) track.setLpfRes(res);
          else track.setHpfRes(res);
        }
        break;
      case "RESONANCE":
        if (track != null) {
          float res = (activeShiftCol == 8) ? track.getLpfRes() : track.getHpfRes();
          res = Math.max(0.0f, Math.min(1.0f, res + delta * 0.02f));
          if (activeShiftCol == 8) track.setLpfRes(res);
          else track.setHpfRes(res);
        }
        break;
      case "ATTACK":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float a = Math.max(0.0f, Math.min(10.0f, old.attack() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new EnvelopeModel(
                  a, old.decay(), old.sustain(), old.release(), old.target(), old.amount()));
        }
        break;
      case "DECAY":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float d = Math.max(0.0f, Math.min(10.0f, old.decay() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new EnvelopeModel(
                  old.attack(), d, old.sustain(), old.release(), old.target(), old.amount()));
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float s = Math.max(0.0f, Math.min(1.0f, old.sustain() + delta * 0.02f));
          track.setEnv(
              envIdx,
              new EnvelopeModel(
                  old.attack(), old.decay(), s, old.release(), old.target(), old.amount()));
        }
        break;
      case "RELEASE":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float r = Math.max(0.0f, Math.min(10.0f, old.release() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new EnvelopeModel(
                  old.attack(), old.decay(), old.sustain(), r, old.target(), old.amount()));
        }
        break;
      case "PAN":
        if (activeShiftRow == 4 && activeShiftCol == 6) {
          float p = Math.max(0.0f, Math.min(1.0f, genericTrack.getPan() + delta * 0.02f));
          genericTrack.setPan(p);
        }
        break;
      case "LEVEL":
        if (activeShiftRow == 7 && activeShiftCol == 6) {
          float vol = Math.max(0.0f, genericTrack.getVolume() + delta * 0.02f);
          genericTrack.setVolume(vol);
        } else if (activeShiftRow == 7
            && (activeShiftCol == 2 || activeShiftCol == 3)
            && track != null) {
          float mixVal = Math.max(0.0f, Math.min(1.0f, track.getOscMix() + delta * 0.02f));
          track.setOscMix(mixVal);
        }
        break;
      case "GLIDE":
        if (track != null) {
          float port = Math.max(0.0f, Math.min(2.0f, track.getPortamento() + delta * 0.02f));
          track.setPortamento(port);
        }
        break;
    }

    // Update LED readout panel with new value
    String code = getParamShortCode(activeShiftParam);
    String valStr = getParamFormattedValue(activeShiftParam, activeShiftRow, activeShiftCol);
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
    }

    parent.applyTrackModelToLiveSound(genericTrack);
    projectChangedCallback.run();
    refreshCallback.run();
  }

  private void handleDragCleared() {
    refreshCallback.run();
  }

  private boolean isSynthOrKit(int type) {
    return type == 1 || type == 0;
  }

  public void handlePadMouseWheel(int visibleRow, int visualCol, java.awt.event.MouseWheelEvent e) {
    if (parent.projectModel == null
        || parent.editedModelTrack >= parent.projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel tModel =
        parent.projectModel.getTracks().get(parent.editedModelTrack);
    org.deluge.model.ClipModel cModel = tModel.getActiveClip();
    if (cModel == null) return;

    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();

    int modelRow = parent.getModelRow(visibleRow);
    int activeCol = parent.getActiveCol(visibleRow, visualCol);

    org.deluge.model.StepData sd = parent.getClipStep(cModel, modelRow, activeCol);
    if (!sd.active()) {
      return; // Gestural pad adjustments only apply to active (programmed) notes!
    }

    int rotation = e.getWheelRotation();
    int dir = -rotation; // Scroll up = positive change, scroll down = negative change

    org.deluge.model.StepData updated = null;
    String oledParam = "";
    String oledValue = "";

    if (parent.isShiftHeld() && e.isAltDown()) {
      // Shift + Alt held = Adjust note fill (0% to 100%, 5% increments)
      float newFill = Math.max(0.0f, Math.min(1.0f, sd.fill() + dir * 0.05f));
      newFill = Math.round(newFill * 100.0f) / 100.0f;
      updated =
          new org.deluge.model.StepData(
              true,
              sd.velocity(),
              sd.gate(),
              sd.probability(),
              sd.pitch(),
              sd.iterance(),
              newFill,
              sd.nudge());
      oledParam = "FILL";
      oledValue = (newFill == 0.0f) ? "OFF" : (int) (newFill * 100) + "%";
    } else if (parent.isShiftHeld()) {
      // Shift held = Adjust note probability (0% to 100%, 5% increments)
      float newProb = Math.max(0.0f, Math.min(1.0f, sd.probability() + dir * 0.05f));
      newProb = Math.round(newProb * 100.0f) / 100.0f;
      updated =
          new org.deluge.model.StepData(
              true,
              sd.velocity(),
              sd.gate(),
              newProb,
              sd.pitch(),
              sd.iterance(),
              sd.fill(),
              sd.nudge());
      oledParam = "PROB";
      oledValue = (int) (newProb * 100) + "%";
    } else if (e.isAltDown()) {
      // Alt held = Adjust note gate/length (0.125 to 64.0 steps, 0.25 step increments)
      float newGate = Math.max(0.125f, Math.min(64.0f, sd.gate() + dir * 0.25f));
      updated =
          new org.deluge.model.StepData(
              true,
              sd.velocity(),
              newGate,
              sd.probability(),
              sd.pitch(),
              sd.iterance(),
              sd.fill(),
              sd.nudge());
      oledParam = "GATE";
      oledValue = String.format("%.2f", newGate);
    } else if (e.isControlDown()) {
      // Ctrl held = Transpose note pitch (up/down by semitones)
      int newPitch = Math.max(0, Math.min(127, sd.pitch() + dir));
      int oldPitch = sd.pitch();
      if (newPitch != oldPitch) {
        int oldClipRow = parent.getClipRowIndex(cModel, modelRow, false);
        if (oldClipRow >= 0) {
          cModel.setStep(oldClipRow, activeCol, org.deluge.model.StepData.empty());
          if (parent.bridge != null) {
            int oldEngineRow = parent.baseTrackId + modelRow;
            parent.bridge.setStep(oldEngineRow, activeCol, false); // Clear old step in bridge!
          }
        }
        int newModelRow = parent.getRowFromPitch(newPitch);
        if (newModelRow >= 0) {
          updated =
              new org.deluge.model.StepData(
                  true,
                  sd.velocity(),
                  sd.gate(),
                  sd.probability(),
                  newPitch,
                  sd.iterance(),
                  sd.fill(),
                  sd.nudge());
          modelRow =
              newModelRow; // Update modelRow reference for subsequent setClipStep/bridge sync
        }
      }
      oledParam = "PITCH";
      oledValue = String.valueOf(newPitch);
    } else {
      // No modifiers = Adjust note velocity (0.0 to 1.0, 0.05 increments, displayed as 0..127)
      float newVel = Math.max(0.0f, Math.min(1.0f, sd.velocity() + dir * 0.05f));
      newVel = Math.round(newVel * 100.0f) / 100.0f;
      updated =
          new org.deluge.model.StepData(
              true,
              newVel,
              sd.gate(),
              sd.probability(),
              sd.pitch(),
              sd.iterance(),
              sd.fill(),
              sd.nudge());
      oledParam = "VEL";
      oledValue = String.valueOf((int) (newVel * 127));
    }

    if (updated != null) {
      if (SwingGridPanel.isCrossScreenWrapActive) {
        int baseCol = activeCol % 16;
        int trackLen = cModel.getStepCount();
        java.util.List<Consequence> steps = new java.util.ArrayList<>();
        for (int c = baseCol; c < trackLen; c += 16) {
          StepData targetSd = parent.getClipStep(cModel, modelRow, c);
          if (targetSd.active()) {
            StepData newSt;
            int targetModelRow = modelRow;
            if (e.isControlDown() && !e.isAltDown() && !parent.isShiftHeld()) {
              int oldPitch = targetSd.pitch();
              int newPitch = updated.pitch();
              if (newPitch != oldPitch) {
                int oldClipRow = parent.getClipRowIndex(cModel, modelRow, false);
                if (oldClipRow >= 0) {
                  cModel.setStep(oldClipRow, c, org.deluge.model.StepData.empty());
                  if (parent.bridge != null) {
                    int oldEngineRow = parent.baseTrackId + modelRow;
                    parent.bridge.setStep(oldEngineRow, c, false);
                  }
                }
              }
              newSt =
                  new org.deluge.model.StepData(
                      true,
                      targetSd.velocity(),
                      targetSd.gate(),
                      targetSd.probability(),
                      newPitch,
                      targetSd.iterance(),
                      targetSd.fill(),
                      targetSd.nudge());
            } else {
              newSt = updated;
            }

            parent.setClipStep(cModel, targetModelRow, c, newSt);

            if (parent.bridge != null) {
              int engineRow = parent.baseTrackId + targetModelRow;
              parent.bridge.setStep(engineRow, c, newSt.active());
              parent.bridge.setVelocity(engineRow, c, newSt.velocity());
              parent.bridge.setGate(engineRow, c, newSt.gate());
              parent.bridge.setStepProbability(engineRow, c, newSt.probability());
              parent.bridge.setStepFill(engineRow, c, newSt.fill());
              parent.bridge.setStepNudge(engineRow, c, newSt.nudge());
            }

            if (getProjectModel() != null) {
              steps.add(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      targetModelRow,
                      c,
                      targetSd,
                      newSt));
            }
          }
        }
        if (!steps.isEmpty() && getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(new Consequence.CompoundConsequence("Adjust Step Wrap", steps));
        }
      } else {
        parent.setClipStep(cModel, modelRow, activeCol, updated);
        if (parent.bridge != null) {
          int engineRow = parent.baseTrackId + modelRow;
          parent.bridge.setStep(engineRow, activeCol, updated.active());
          parent.bridge.setVelocity(engineRow, activeCol, updated.velocity());
          parent.bridge.setGate(engineRow, activeCol, updated.gate());
          parent.bridge.setStepProbability(engineRow, activeCol, updated.probability());
          parent.bridge.setStepFill(engineRow, activeCol, updated.fill());
          parent.bridge.setStepNudge(engineRow, activeCol, updated.nudge());
        }
        if (getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      modelRow,
                      activeCol,
                      sd,
                      updated));
        }
      }

      // Display transient parameter change on OLED readout
      if (SwingDelugeApp.mainInstance != null && SwingDelugeApp.mainInstance.getTopBar() != null) {
        SwingDelugeApp.mainInstance
            .getTopBar()
            .getParamReadout()
            .printTransient(oledParam, oledValue);
      }

      parent.fireProjectChanged();
      parent.refresh();
    }
  }
}
