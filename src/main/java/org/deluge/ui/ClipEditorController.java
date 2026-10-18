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
    SwingHardwareTopPanel.repaintActive();
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

  TrackModel editedTrackOrNull() {
    return parent.editedTrackOrNull();
  }

  ClipModel activeClipOrNull() {
    return parent.activeClipOrNull();
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
              if (parent.isStepColumn(colId) && !parent.isSquareDefined(colId)) {
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
                int activeCol = parent.getActiveCol(visualRow, colId);
                if (parent.isStepColumn(colId) && activeCol < 0) return;
                ClipModel cModel = activeClipOrNull();
                int trackType = parent.resolveEditedTrackType();
                boolean isSynthMode = isSynthOrKit(trackType);

                if (trackType == 2) {
                  // MIDI Track
                  StepData oldStep =
                      cModel != null ? parent.getClipStep(cModel, modelRow, activeCol) : null;
                  int engineRow = parent.getAllocatedEngineRow(modelRow);
                  boolean st =
                      engineRow >= 0
                          ? bridge.getStep(engineRow, activeCol)
                          : (oldStep != null && oldStep.active());
                  if (cModel != null) {
                    int clipRow = parent.getClipRowIndex(cModel, modelRow, !st);
                    if (clipRow >= 0) {
                      StepData newStep =
                          StepData.of(!st, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, 0);
                      cModel.setStep(clipRow, activeCol, newStep);
                      engineRow = parent.getEngineRowForStoredClipRow(clipRow);
                      if (oldStep != null && projectModel != null) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.StepConsequence(
                                    projectModel,
                                    editedModelTrack,
                                    activeClipId,
                                    clipRow,
                                    activeCol,
                                    oldStep,
                                    cModel.getStep(clipRow, activeCol)));
                      }
                    }
                  }
                  if (engineRow >= 0) {
                    bridge.setStep(engineRow, activeCol, !st);
                  }
                  if (!st && getMidiOut() != null) {
                    parent.sendMidiNote(60 + modelRow, 100, 250); // preview
                  }
                  clipBtn.setBackground(!st ? getTrackColors()[6] : new Color(0x33, 0x33, 0x33));
                } else if (isSynthMode) {
                  // Synth Track
                  StepData oldStep =
                      cModel != null ? parent.getClipStep(cModel, modelRow, activeCol) : null;
                  int engineRow = parent.getAllocatedEngineRow(modelRow);
                  boolean stepState =
                      engineRow >= 0
                          ? bridge.getStep(engineRow, activeCol)
                          : (oldStep != null && oldStep.active());
                  boolean nextState = !stepState;
                  int midiPitch = parent.getRowPitch(modelRow);
                  int clipRow =
                      cModel != null ? parent.getClipRowIndex(cModel, modelRow, nextState) : -1;
                  if (clipRow >= 0) {
                    engineRow = parent.getEngineRowForStoredClipRow(clipRow);
                  }
                  if (engineRow >= 0) {
                    bridge.setStep(engineRow, activeCol, nextState);
                    if (nextState) {
                      bridge.setPitch(engineRow, activeCol, midiPitch);
                    }
                  }
                  double velS = engineRow >= 0 ? bridge.getVelocity(engineRow, activeCol) : 0.8;
                  clipBtn.setBackground(
                      nextState
                          ? SwingGridPanel.velocityBlend(
                              getTrackColors()[modelRow % getTrackColors().length], velS)
                          : new Color(0x33, 0x33, 0x33));

                  // Audition via engine preview
                  int slot = engineRow >= 0 ? engineRow : baseTrackId;
                  if (slot >= 0) {
                    bridge.setGlobalFloat(BridgeContract.G_PREVIEW_PITCH, (float) midiPitch);
                    bridge.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) slot);
                    bridge.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                  }

                  try {
                    String[] noteNames = {
                      "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
                    };
                    String nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
                    FirmwareDisplay.get().getVirtualOLED().setNoteOverride(nName);
                  } catch (Throwable th) {
                    // Shield
                  }

                  if (cModel != null && clipRow >= 0) {
                    double curVel = engineRow >= 0 ? bridge.getVelocity(engineRow, activeCol) : 0.8;
                    double curProb =
                        engineRow >= 0 ? bridge.getStepProbability(engineRow, activeCol) : 1.0;
                    cModel.setStep(
                        clipRow,
                        activeCol,
                        StepData.of(
                            nextState,
                            (float) curVel,
                            StepData.DEFAULT_CLICK_GATE,
                            (float) curProb,
                            midiPitch));
                    if (oldStep != null && projectModel != null) {
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.StepConsequence(
                                  projectModel,
                                  editedModelTrack,
                                  activeClipId,
                                  clipRow,
                                  activeCol,
                                  oldStep,
                                  cModel.getStep(clipRow, activeCol)));
                    }
                  }
                } else {
                  // Drum Track
                  if (parent.isStepColumn(colId)) {
                    // Step pad: Toggle step + Audition
                    int engineRow = parent.getAllocatedEngineRow(modelRow);
                    StepData oldStep =
                        cModel != null ? parent.getClipStep(cModel, modelRow, activeCol) : null;
                    boolean stepState =
                        engineRow >= 0
                            ? bridge.getStep(engineRow, activeCol)
                            : (oldStep != null && oldStep.active());
                    boolean nextState = !stepState;
                    int clipRow =
                        cModel != null ? parent.getClipRowIndex(cModel, modelRow, nextState) : -1;
                    if (clipRow >= 0) {
                      engineRow = parent.getEngineRowForStoredClipRow(clipRow);
                    }
                    if (engineRow >= 0) {
                      bridge.setStep(engineRow, activeCol, nextState);
                    }
                    double velK;
                    double probK;
                    if (nextState) {
                      velK =
                          (oldStep != null && oldStep.velocity() > 0.0f) ? oldStep.velocity() : 0.8;
                      probK = (oldStep != null) ? oldStep.probability() : 1.0;
                    } else {
                      velK = engineRow >= 0 ? bridge.getVelocity(engineRow, activeCol) : 0.8;
                      probK =
                          engineRow >= 0 ? bridge.getStepProbability(engineRow, activeCol) : 1.0;
                    }
                    clipBtn.setBackground(
                        nextState
                            ? parent.getGridNoteColor(modelRow, (float) velK)
                            : parent.getStepPadDefaultBg(modelRow, colId));

                    // Trigger the drum sound for audition
                    try {
                      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
                        if (editedModelTrack >= 0
                            && editedModelTrack < fwEngine.sounds.size()
                            && !parent.isSequencerPlaying()) {
                          GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
                          if (sound instanceof FirmwareKit kit) {
                            if (modelRow < kit.drumSounds.size()) kit.triggerDrum(modelRow, 127);
                          }
                        }
                      }
                    } catch (Exception ignored) {
                    }

                    if (cModel != null && clipRow >= 0) {
                      StepData newStep =
                          StepData.of(
                              nextState,
                              (float) velK,
                              StepData.DEFAULT_CLICK_GATE,
                              (float) probK,
                              0);
                      cModel.setStep(clipRow, activeCol, newStep);
                      if (oldStep != null && projectModel != null) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.StepConsequence(
                                    projectModel,
                                    editedModelTrack,
                                    activeClipId,
                                    clipRow,
                                    activeCol,
                                    oldStep,
                                    cModel.getStep(clipRow, activeCol)));
                      }
                    }
                    projectChangedCallback.run();
                  } else {
                    // Audition pad (col >= 16): Audition on press, no step toggle
                    try {
                      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
                        if (editedModelTrack >= 0
                            && editedModelTrack < fwEngine.sounds.size()
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
                  if (editedModelTrack >= 0 && editedModelTrack < fwEngine.sounds.size()) {
                    GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
                    if (sound instanceof FirmwareKit kit) {
                      if (modelRow < kit.drumSounds.size()) {
                        kit.drumSounds.get(modelRow).releaseNote(60);
                      }
                    } else if (sound instanceof FirmwareSound synth) {
                      boolean isSynthModeLocal = isSynthOrKit(parent.resolveEditedTrackType());
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
    if (parent.isStepColumn(col) && !parent.isSquareDefined(col)) return;
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);
    int trackType = parent.resolveEditedTrackType();
    boolean isSynthMode = isSynthOrKit(trackType);

    if (trackType == 2) {
      if (getMidiOut() != null) {
        parent.sendMidiNote(60 + visualModelRow, 100, 250);
      }
    } else if (isSynthMode) {
      int pitchMidi = parent.getRowPitch(visualModelRow);
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
        int editedModelTrack = getEditedModelTrack();
        if (editedModelTrack >= 0
            && editedModelTrack < fwEngine.sounds.size()
            && !parent.isSequencerPlaying()) {
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
        if (editedModelTrack >= 0
            && editedModelTrack < fwEngine.sounds.size()
            && !parent.isSequencerPlaying()) {
          GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof FirmwareKit kit) {
            if (engineRowOffset >= 0 && engineRowOffset < kit.drumSounds.size()) {
              kit.triggerDrum(engineRowOffset, 127);
            }
          }
        }
      }
    }
  }

  private void handleStepReleased(int row, int col) {
    if (parent.isStepColumn(col) && !parent.isSquareDefined(col)) return;
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int engineRowOffset = parent.getEngineRowOffset(visualModelRow);

    Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (fwEngineObj instanceof FirmwareAudioEngine fwEngine) {
      int editedModelTrack = getEditedModelTrack();
      if (editedModelTrack >= 0 && editedModelTrack < fwEngine.sounds.size()) {
        GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
        if (sound instanceof FirmwareKit kit) {
          if (engineRowOffset >= 0 && engineRowOffset < kit.drumSounds.size()) {
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
    if (!parent.isSquareDefined(col)) return;
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int visualModelRow = parent.getModelRow(row);
    int activeCol = parent.getActiveCol(row, col);
    if (activeCol < 0) return;
    boolean isSynthMode = isSynthOrKit(parent.resolveEditedTrackType());

    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();
    ClipModel cModel = activeClipOrNull();
    StepData oldStep =
        cModel != null ? parent.getClipStep(cModel, visualModelRow, activeCol) : null;

    int engineRow = parent.getAllocatedEngineRow(visualModelRow);
    boolean stepState =
        cModel != null
            ? (oldStep != null && oldStep.active())
            : (engineRow >= 0 && bridge.getStep(engineRow, activeCol));
    boolean nextState = !stepState;

    int clipRow = -1;
    if (cModel != null) {
      clipRow = parent.getClipRowIndex(cModel, visualModelRow, nextState);
      if (clipRow < 0) {
        return;
      }
      if (nextState) {
        isSynthMode = isSynthOrKit(parent.resolveEditedTrackType());
      }
      engineRow = parent.getEngineRowForStoredClipRow(clipRow);
    } else if (engineRow < 0) {
      return;
    }

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      // C instrument_clip_view.cpp:337 — wrapEditLevel equals current screen width
      int screenSteps = parent.getScreenStepCount();
      int baseCol = activeCol % screenSteps;
      java.util.List<Consequence> steps = new java.util.ArrayList<>();
      int trackLen = cModel.getStepCount();
      int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
      for (int c = baseCol; c < trackLen; c += screenSteps) {
        StepData oldSt = cModel.getStep(clipRow, c);
        if (engineRow >= 0) {
          bridge.setStep(engineRow, c, nextState);
        }
        float curVel =
            engineRow >= 0
                ? (float) bridge.getVelocity(engineRow, c)
                : (oldSt != null && oldSt.active() ? oldSt.velocity() : 0.8f);
        float curProb =
            engineRow >= 0
                ? (float) bridge.getStepProbability(engineRow, c)
                : (oldSt != null && oldSt.active() ? oldSt.probability() : 1.0f);
        StepData newSt =
            StepData.of(nextState, curVel, StepData.DEFAULT_CLICK_GATE, curProb, pitch);
        cModel.setStep(clipRow, c, newSt);
        if (oldSt != null && getProjectModel() != null) {
          steps.add(
              new Consequence.StepConsequence(
                  getProjectModel(), editedModelTrack, activeClipId, clipRow, c, oldSt, newSt));
        }
      }
      if (!steps.isEmpty() && getProjectModel() != null) {
        getProjectModel()
            .getUndoRedoStack()
            .push(new Consequence.CompoundConsequence("Toggle Step Wrap", steps));
      }
    } else {
      if (engineRow >= 0) {
        bridge.setStep(engineRow, activeCol, nextState);
      }
      if (cModel != null) {
        double curVel =
            engineRow >= 0
                ? bridge.getVelocity(engineRow, activeCol)
                : (oldStep != null && oldStep.active() ? oldStep.velocity() : 0.8);
        double curProb =
            engineRow >= 0
                ? bridge.getStepProbability(engineRow, activeCol)
                : (oldStep != null && oldStep.active() ? oldStep.probability() : 1.0);
        int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
        StepData newStep =
            StepData.of(
                nextState, (float) curVel, StepData.DEFAULT_CLICK_GATE, (float) curProb, pitch);
        cModel.setStep(clipRow, activeCol, newStep);

        if (oldStep != null && getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      clipRow,
                      activeCol,
                      oldStep,
                      newStep));
        }
      }
    }
    projectChangedCallback.run();
  }

  private void handleStepLongPressed(int row, int col, Point screenPos) {
    if (!parent.isSquareDefined(col)) return;
    StepPropertiesEditor.handleStepLongPressed(this, row, col, screenPos);
  }

  void handleStepTied(int row, int colStart, int colEnd) {
    if (!parent.isSquareDefined(colStart) || !parent.isSquareDefined(colEnd)) return;
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int start = Math.min(colStart, colEnd);
    int end = Math.max(colStart, colEnd);

    int startModelCol = parent.getActiveCol(row, start);
    int endModelCol = parent.getActiveCol(row, end);
    if (startModelCol < 0 || endModelCol < 0) return;
    int visualModelRow = parent.getModelRow(row);
    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();
    ClipModel cModel = activeClipOrNull();

    StepData existingStart =
        cModel != null ? parent.getClipStep(cModel, visualModelRow, startModelCol) : null;
    int clipRow = cModel != null ? parent.getClipRowIndex(cModel, visualModelRow, true) : -1;
    if (cModel != null && clipRow < 0) {
      return;
    }
    int engineRow =
        cModel != null
            ? parent.getEngineRowForStoredClipRow(clipRow)
            : parent.getAllocatedEngineRow(visualModelRow);
    if (cModel == null && engineRow < 0) {
      return;
    }
    boolean isSynthMode = isSynthOrKit(parent.resolveEditedTrackType());

    boolean startActive =
        cModel != null
            ? (existingStart != null && existingStart.active())
            : (engineRow >= 0 && bridge.getStep(engineRow, startModelCol));
    double vel =
        startActive
            ? (engineRow >= 0
                ? bridge.getVelocity(engineRow, startModelCol)
                : (existingStart != null ? existingStart.velocity() : 0.8))
            : 0.8;
    double prob =
        startActive
            ? (engineRow >= 0
                ? bridge.getStepProbability(engineRow, startModelCol)
                : (existingStart != null ? existingStart.probability() : 1.0))
            : 1.0;
    int pitch = isSynthMode ? parent.getRowPitch(visualModelRow) : 0;
    int iter =
        startActive
            ? (engineRow >= 0
                ? bridge.getIterance(engineRow, startModelCol)
                : (existingStart != null ? existingStart.iterance() : 0))
            : 0;
    double fill =
        startActive
            ? (engineRow >= 0
                ? bridge.getStepFill(engineRow, startModelCol)
                : (existingStart != null ? existingStart.fill() : 0.0))
            : 0.0;

    int tieSteps = Math.max(0, endModelCol - startModelCol);
    float newGate = tieSteps + 0.9f;

    if (cModel != null && SwingGridPanel.isCrossScreenWrapActive) {
      // C instrument_clip_view.cpp:337 — wrapEditLevel equals current screen width
      int screenSteps = parent.getScreenStepCount();
      int baseStart = startModelCol % screenSteps;
      int trackLen = cModel.getStepCount();
      java.util.List<Consequence> steps = new java.util.ArrayList<>();

      for (int offset = 0; offset < trackLen; offset += screenSteps) {
        int sCol = offset + baseStart;
        if (sCol >= trackLen) break;
        int eCol = sCol + tieSteps;

        // Apply start tie step
        StepData oldStart = cModel.getStep(clipRow, sCol);
        boolean sSt = true;
        int startPlayCond = (oldStart != null && oldStart.active()) ? oldStart.playCondition() : 0;
        int startFillMode =
            (oldStart != null
                    && oldStart.active()
                    && Math.abs((float) fill - oldStart.fill()) < 0.001f)
                ? oldStart.fillMode()
                : (fill > 0.001 ? 2 : 0);
        StepData startStep =
            new StepData(
                sSt,
                (float) vel,
                newGate,
                (float) prob,
                pitch,
                iter,
                (float) fill,
                0.0f,
                startPlayCond,
                startFillMode);
        cModel.setStep(clipRow, sCol, startStep);

        if (bridge != null && engineRow >= 0) {
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
                  clipRow,
                  sCol,
                  oldStart,
                  startStep));
        }

        // Apply tail clear steps
        for (int c = sCol + 1; c <= eCol; c++) {
          if (c < trackLen) {
            StepData oldStep = cModel.getStep(clipRow, c);
            StepData newStep = new StepData(false, 0.8f, 0.0f, 1.0f, 0, 0, 0.0f);
            cModel.setStep(clipRow, c, newStep);

            if (bridge != null && engineRow >= 0) {
              bridge.setStep(engineRow, c, false);
              bridge.setGate(engineRow, c, 0.0);
            }

            if (oldStep != null && getProjectModel() != null && oldStep.active()) {
              steps.add(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      clipRow,
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
      if (engineRow >= 0) {
        bridge.setStep(engineRow, startModelCol, true);
        bridge.setVelocity(engineRow, startModelCol, vel);
        bridge.setGate(engineRow, startModelCol, (double) newGate);
        bridge.setStepProbability(engineRow, startModelCol, prob);
        bridge.setIterance(engineRow, startModelCol, iter);
        bridge.setStepFill(engineRow, startModelCol, fill);
        bridge.setStepNudge(engineRow, startModelCol, 0.0);
      }

      if (cModel != null) {
        StepData oldStart = cModel.getStep(clipRow, startModelCol);
        boolean startSt = true;
        int startPlayCond = (oldStart != null && oldStart.active()) ? oldStart.playCondition() : 0;
        int startFillMode =
            (oldStart != null
                    && oldStart.active()
                    && Math.abs((float) fill - oldStart.fill()) < 0.001f)
                ? oldStart.fillMode()
                : (fill > 0.001 ? 2 : 0);
        StepData startStep =
            new StepData(
                startSt,
                (float) vel,
                newGate,
                (float) prob,
                pitch,
                iter,
                (float) fill,
                0.0f,
                startPlayCond,
                startFillMode);
        cModel.setStep(clipRow, startModelCol, startStep);
        if (oldStart != null && getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      getProjectModel(),
                      editedModelTrack,
                      activeClipId,
                      clipRow,
                      startModelCol,
                      oldStart,
                      startStep));
        }
      }

      for (int activeCol = startModelCol + 1; activeCol <= endModelCol; activeCol++) {
        if (engineRow >= 0) {
          bridge.setStep(engineRow, activeCol, false);
          bridge.setGate(engineRow, activeCol, 0.0);
        }

        if (cModel != null) {
          StepData oldStep = cModel.getStep(clipRow, activeCol);
          StepData newStep = new StepData(false, 0.8f, 0.0f, 1.0f, 0, 0, 0.0f);
          cModel.setStep(clipRow, activeCol, newStep);
          if (oldStep != null && getProjectModel() != null && oldStep.active()) {
            getProjectModel()
                .getUndoRedoStack()
                .push(
                    new Consequence.StepConsequence(
                        getProjectModel(),
                        editedModelTrack,
                        activeClipId,
                        clipRow,
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

    for (int c = 0; c < columnCount; c++) {
      if (pads[visRow][c] instanceof DelugePadButton pad) {
        int activeCol = parent.getActiveCol(row, c);
        if (activeCol < 0) {
          pad.setActive(false);
          pad.setTail(false);
          continue;
        }
        boolean tiedInModel = false;
        int modelRow = parent.getModelRow(row);
        if (bridge != null) {
          int engineRow = parent.getAllocatedEngineRow(modelRow);
          tiedInModel = engineRow >= 0 && bridge.getGate(engineRow, activeCol) >= 0.99;
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

    TrackModel genericTrack = editedTrackOrNull();
    if (genericTrack == null) return;

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
    if (!parent.isSquareDefined(startCol) || !parent.isSquareDefined(targetCol)) return;
    BridgeContract bridge = getBridge();
    if (bridge == null) return;
    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();

    int srcModelRow = parent.getModelRow(startRow);
    int dstModelRow = parent.getModelRow(targetRow);
    int srcCol = parent.getActiveCol(startRow, startCol);
    int dstCol = parent.getActiveCol(targetRow, targetCol);
    if (srcCol < 0 || dstCol < 0) return;

    TrackModel tModel = editedTrackOrNull();
    ClipModel cModel = activeClipOrNull();
    StepData srcModelStep = null;
    int srcClipRow = -1;
    if (cModel != null) {
      srcClipRow = parent.getClipRowIndex(cModel, srcModelRow, false);
      srcModelStep = srcClipRow >= 0 ? cModel.getStep(srcClipRow, srcCol) : StepData.empty();
    }
    int srcRow =
        cModel != null
            ? parent.getEngineRowForStoredClipRow(srcClipRow)
            : parent.getAllocatedEngineRow(srcModelRow);
    if (srcRow < 0 && srcModelStep == null) {
      return;
    }

    // Read step parameters from source row before any destination row creation shifts baseTrackId
    boolean active =
        srcRow >= 0
            ? bridge.getStep(srcRow, srcCol)
            : (srcModelStep != null && srcModelStep.active());
    double vel =
        srcRow >= 0
            ? bridge.getVelocity(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.velocity() : 0.8);
    double gate =
        srcRow >= 0
            ? bridge.getGate(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.gate() : 1.0);
    int pitch =
        srcRow >= 0
            ? bridge.getPitch(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.pitch() : 60);
    double prob =
        srcRow >= 0
            ? bridge.getStepProbability(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.probability() : 1.0);
    int iter =
        srcRow >= 0
            ? bridge.getIterance(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.iterance() : 0);
    double fill =
        srcRow >= 0
            ? bridge.getStepFill(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.fill() : 0.0);
    double nudge =
        srcRow >= 0
            ? bridge.getStepNudge(srcRow, srcCol)
            : (srcModelStep != null ? srcModelStep.nudge() : 0.0);
    float stepFilterVal =
        readSourceStepAuto(
            cModel,
            srcClipRow,
            srcRow,
            srcCol,
            org.deluge.model.AutomationParam.A_LPF_FREQ,
            srcRow >= 0 ? bridge.getStepFilter(srcRow, srcCol) : -1.0);
    float stepResVal =
        readSourceStepAuto(
            cModel,
            srcClipRow,
            srcRow,
            srcCol,
            org.deluge.model.AutomationParam.A_LPF_RES,
            srcRow >= 0 ? bridge.getStepRes(srcRow, srcCol) : -1.0);
    float stepPanVal =
        readSourceStepAuto(
            cModel,
            srcClipRow,
            srcRow,
            srcCol,
            org.deluge.model.AutomationParam.A_PAN,
            srcRow >= 0 ? bridge.getStepPan(srcRow, srcCol) : -1.0);
    float stepDelayVal =
        readSourceStepAuto(
            cModel,
            srcClipRow,
            srcRow,
            srcCol,
            org.deluge.model.AutomationParam.A_DELAY,
            srcRow >= 0 ? bridge.getStepDelay(srcRow, srcCol) : -1.0);
    float stepReverbVal =
        readSourceStepAuto(
            cModel,
            srcClipRow,
            srcRow,
            srcCol,
            org.deluge.model.AutomationParam.A_REVERB,
            srcRow >= 0 ? bridge.getStepReverb(srcRow, srcCol) : -1.0);

    // Push Undo/Redo step copy event and sync ClipModel first so destination synth row capacity is
    // reserved before writing to the bridge.
    StepData oldTargetStep = StepData.empty();
    StepData clonedStep =
        new StepData(
            active,
            (float) vel,
            (float) gate,
            (float) prob,
            pitch,
            iter,
            (float) fill,
            (float) nudge);
    int dstClipRow = dstModelRow;
    ArrayList<Consequence> steps = new ArrayList<>();
    if (cModel != null) {
      dstClipRow = parent.getClipRowIndex(cModel, dstModelRow, true);
      if (dstClipRow < 0) {
        return;
      }
      oldTargetStep = cModel.getStep(dstClipRow, dstCol);
      if (srcModelStep != null) {
        int trackType = parent.resolveEditedTrackType();
        int dstPitch;
        if (tModel instanceof org.deluge.model.KitTrackModel || trackType == 0) {
          dstPitch = dstClipRow;
        } else if (tModel instanceof org.deluge.model.SynthTrackModel || trackType == 1) {
          dstPitch = parent.getRowPitch(dstModelRow);
        } else {
          dstPitch = srcModelStep.pitch();
        }
        clonedStep = srcModelStep.withPitch(dstPitch);
      }
      cModel.setStep(dstClipRow, dstCol, clonedStep);
      applyClonedRowAuto(
          cModel,
          editedModelTrack,
          activeClipId,
          dstClipRow,
          dstCol,
          org.deluge.model.AutomationParam.A_LPF_FREQ,
          stepFilterVal,
          steps);
      applyClonedRowAuto(
          cModel,
          editedModelTrack,
          activeClipId,
          dstClipRow,
          dstCol,
          org.deluge.model.AutomationParam.A_LPF_RES,
          stepResVal,
          steps);
      applyClonedRowAuto(
          cModel,
          editedModelTrack,
          activeClipId,
          dstClipRow,
          dstCol,
          org.deluge.model.AutomationParam.A_PAN,
          stepPanVal,
          steps);
      applyClonedRowAuto(
          cModel,
          editedModelTrack,
          activeClipId,
          dstClipRow,
          dstCol,
          org.deluge.model.AutomationParam.A_DELAY,
          stepDelayVal,
          steps);
      applyClonedRowAuto(
          cModel,
          editedModelTrack,
          activeClipId,
          dstClipRow,
          dstCol,
          org.deluge.model.AutomationParam.A_REVERB,
          stepReverbVal,
          steps);
    }

    int dstRow =
        cModel != null
            ? parent.getEngineRowForStoredClipRow(dstClipRow)
            : parent.getAllocatedEngineRow(dstModelRow);
    if (dstRow >= 0) {
      bridge.setStep(dstRow, dstCol, clonedStep.active());
      bridge.setVelocity(dstRow, dstCol, clonedStep.velocity());
      bridge.setGate(dstRow, dstCol, clonedStep.gate());
      bridge.setPitch(dstRow, dstCol, clonedStep.pitch());
      bridge.setStepProbability(dstRow, dstCol, clonedStep.probability());
      bridge.setIterance(dstRow, dstCol, clonedStep.iterance());
      bridge.setStepFill(dstRow, dstCol, clonedStep.fill());
      bridge.setStepNudge(dstRow, dstCol, clonedStep.nudge());
      bridge.setStepFilter(dstRow, dstCol, stepFilterVal >= 0f ? stepFilterVal : 0.0);
      bridge.setStepRes(dstRow, dstCol, stepResVal >= 0f ? stepResVal : 0.0);
      bridge.setStepPan(dstRow, dstCol, stepPanVal >= 0f ? stepPanVal : 0.0);
      bridge.setStepDelay(dstRow, dstCol, stepDelayVal >= 0f ? stepDelayVal : 0.0);
      bridge.setStepReverb(dstRow, dstCol, stepReverbVal >= 0f ? stepReverbVal : 0.0);
    }
    steps.add(
        0,
        new Consequence.StepConsequence(
            getProjectModel(),
            editedModelTrack,
            activeClipId,
            dstClipRow,
            dstCol,
            oldTargetStep != null ? oldTargetStep : StepData.empty(),
            clonedStep));
    if (getProjectModel() != null) {
      getProjectModel()
          .getUndoRedoStack()
          .push(new Consequence.CompoundConsequence("Clone step to " + targetCol, steps));
    }

    projectChangedCallback.run();
    refreshCallback.run();
  }

  private static float readSourceStepAuto(
      ClipModel clip, int clipRow, int engineRow, int col, String param, double bridgeVal) {
    if (clip != null && clipRow >= 0 && clip.hasRowAutomation(clipRow, param, col)) {
      return clip.getRowAutomationValue(clipRow, param, col);
    }
    if (clip != null && clipRow == 0 && clip.hasAutomation(param, col)) {
      return clip.getAutomation(param, col);
    }
    if (engineRow >= 0 && bridgeVal > 0.0) {
      return (float) bridgeVal;
    }
    return -1f;
  }

  private void applyClonedRowAuto(
      ClipModel clip,
      int trackIdx,
      int clipIdx,
      int dstClipRow,
      int dstCol,
      String param,
      float newVal,
      java.util.List<Consequence> steps) {
    if (clip == null || dstClipRow < 0) return;
    float oldVal = clip.getRowAutomationValue(dstClipRow, param, dstCol);
    if (oldVal < 0f && newVal < 0f) return;
    clip.setRowAutomation(dstClipRow, param, dstCol, newVal);
    if (getProjectModel() != null) {
      steps.add(
          new Consequence.RowAutomationConsequence(
              getProjectModel(), trackIdx, clipIdx, dstClipRow, param, dstCol, oldVal, newVal));
    }
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

  static float delayFeedbackUnitFromQ31(int q31) {
    return (float) (((long) q31 - Integer.MIN_VALUE) / 4294967295.0);
  }

  static int delayFeedbackQ31FromUnit(double unit) {
    double clamped = Math.max(0.0, Math.min(1.0, unit));
    return (int) Math.round(clamped * 4294967295.0 + Integer.MIN_VALUE);
  }

  String getParamFormattedValue(String param, int row, int col) {
    TrackModel genericTrack = editedTrackOrNull();
    if (genericTrack == null) return "--";
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
      case "RATE":
        // C menus.cpp:1933 — (col 12, row 0) is modFXRateMenu
        if (row == 0 && col == 12 && track != null) {
          return String.format("%d%%", Math.round(track.getModFxRate() * 100.0f));
        }
        break;
      case "DEPTH":
        // C menus.cpp:1933 — (col 12, row 1) is modFXDepthMenu
        if (track != null) {
          return String.format("%d%%", Math.round(track.getModFxDepth() * 100.0f));
        }
        break;
      case "FDBACK":
        // C menus.cpp:1933 — (col 12, row 2) is modFXFeedbackMenu
        if (track != null) {
          return String.format("%d%%", Math.round(track.getModFxFeedback() * 100.0f));
        }
        break;
      case "OFFSET":
        // C menus.cpp:1933 — (col 12, row 3) is modFXOffsetMenu
        if (track != null) {
          return String.format("%d%%", Math.round(track.getModFxOffset() * 100.0f));
        }
        break;
      case "REVERB":
      case "WET":
        if (track != null) {
          return String.format("%d%%", (int) (track.getReverbSend() * 100.0f));
        }
        break;
      case "AMOUNT":
        // C menus.cpp:1934-1935 — col 13 row 4 is reverbAmountMenu; col 14 row 4 is
        // delayFeedbackMenu
        if (track != null) {
          if (col == 14) {
            return String.format(
                "%d%%", Math.round(delayFeedbackUnitFromQ31(track.getDelayFeedbackQ31()) * 100.0f));
          }
          return String.format("%d%%", (int) (track.getReverbSend() * 100.0f));
        }
        break;
      case "SIZE":
      case "ROOM SIZE":
      case "TIME":
      case "DAMP":
      case "DAMPING":
      case "WIDTH":
        if (SwingDelugeApp.mainInstance != null
            && SwingDelugeApp.mainInstance.getFirmwareAudioEngine() != null) {
          var revEngine = SwingDelugeApp.mainInstance.getFirmwareAudioEngine().masterReverb;
          float curRev =
              ("SIZE".equals(param) || "ROOM SIZE".equals(param) || "TIME".equals(param))
                  ? revEngine.getRoomSize()
                  : (("DAMP".equals(param) || "DAMPING".equals(param))
                      ? revEngine.getDamping()
                      : revEngine.getWidth());
          return String.format("%d%%", (int) (curRev * 100.0f));
        }
        break;
      case "DELAY":
        if (track != null) {
          return String.format("%d%%", (int) (track.getDelaySend() * 100.0f));
        }
        break;
      case "FEEDBACK":
        if (track != null) {
          return String.format(
              "%d%%", Math.round(delayFeedbackUnitFromQ31(track.getDelayFeedbackQ31()) * 100.0f));
        }
        break;
      case "MONO/ STEREO":
      case "PING PONG":
        if (track != null) {
          return track.isDelayPingPong() ? "ON" : "OFF";
        }
        break;
      case "DIGI/ ANALOG":
        if (track != null) {
          return track.isDelayAnalog() ? "ANLG" : "DIGI";
        }
        break;
    }
    return "--";
  }

  public void adjustRotaryParameter(int delta) {
    TrackModel genericTrack = editedTrackOrNull();
    if (activeShiftParam == null || genericTrack == null) return;
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
          float vol = Math.max(0.0f, Math.min(1.0f, genericTrack.getVolume() + delta * 0.02f));
          genericTrack.setVolume(vol);
        } else if (activeShiftRow == 7
            && (activeShiftCol == 2 || activeShiftCol == 3)
            && track != null) {
          float mix = Math.max(0.0f, Math.min(1.0f, track.getOscMix() + delta * 0.02f));
          track.setOscMix(mix);
        }
        break;
      case "GLIDE":
        if (track != null) {
          float port = Math.max(0.0f, Math.min(2.0f, track.getPortamento() + delta * 0.05f));
          track.setPortamento(port);
        }
        break;
      case "RATE":
        if (activeShiftRow == 0 && activeShiftCol == 12 && track != null) {
          float rate = Math.max(0.0f, Math.min(1.0f, track.getModFxRate() + delta * 0.02f));
          track.setModFxRate(rate);
        }
        break;
      case "DEPTH":
        if (track != null) {
          float depth = Math.max(0.0f, Math.min(1.0f, track.getModFxDepth() + delta * 0.02f));
          track.setModFxDepth(depth);
        }
        break;
      case "FDBACK":
        if (track != null) {
          float fb = Math.max(0.0f, Math.min(1.0f, track.getModFxFeedback() + delta * 0.02f));
          track.setModFxFeedback(fb);
        }
        break;
      case "OFFSET":
        if (track != null) {
          float off = Math.max(0.0f, Math.min(1.0f, track.getModFxOffset() + delta * 0.02f));
          track.setModFxOffset(off);
        }
        break;
      case "REVERB":
      case "WET":
        if (track != null) {
          float rev = Math.max(0.0f, Math.min(1.0f, track.getReverbSend() + delta * 0.05f));
          track.setReverbSend(rev);
        }
        break;
      case "AMOUNT":
        if (track != null) {
          if (activeShiftCol == 14) {
            float unit = delayFeedbackUnitFromQ31(track.getDelayFeedbackQ31());
            unit = Math.max(0.0f, Math.min(1.0f, unit + delta * 0.05f));
            track.setDelayFeedbackQ31(delayFeedbackQ31FromUnit(unit));
          } else {
            float rev = Math.max(0.0f, Math.min(1.0f, track.getReverbSend() + delta * 0.05f));
            track.setReverbSend(rev);
          }
        }
        break;
      case "SIZE":
      case "ROOM SIZE":
      case "TIME":
      case "DAMP":
      case "DAMPING":
      case "WIDTH":
        if (SwingDelugeApp.mainInstance != null
            && SwingDelugeApp.mainInstance.getFirmwareAudioEngine() != null) {
          var revEngine = SwingDelugeApp.mainInstance.getFirmwareAudioEngine().masterReverb;
          if ("SIZE".equals(activeShiftParam)
              || "ROOM SIZE".equals(activeShiftParam)
              || "TIME".equals(activeShiftParam)) {
            revEngine.setRoomSize(
                Math.max(0.0f, Math.min(1.0f, revEngine.getRoomSize() + delta * 0.05f)));
          } else if ("DAMP".equals(activeShiftParam) || "DAMPING".equals(activeShiftParam)) {
            revEngine.setDamping(
                Math.max(0.0f, Math.min(1.0f, revEngine.getDamping() + delta * 0.05f)));
          } else {
            revEngine.setWidth(
                Math.max(0.0f, Math.min(1.0f, revEngine.getWidth() + delta * 0.05f)));
          }
        }
        break;
      case "DELAY":
        if (track != null) {
          float dly = Math.max(0.0f, Math.min(1.0f, track.getDelaySend() + delta * 0.05f));
          track.setDelaySend(dly);
        }
        break;
      case "FEEDBACK":
        if (track != null) {
          float unit = delayFeedbackUnitFromQ31(track.getDelayFeedbackQ31());
          unit = Math.max(0.0f, Math.min(1.0f, unit + delta * 0.05f));
          track.setDelayFeedbackQ31(delayFeedbackQ31FromUnit(unit));
        }
        break;
      case "MONO/ STEREO":
      case "PING PONG":
        if (track != null && delta != 0) {
          track.setDelayPingPong(delta > 0);
        }
        break;
      case "DIGI/ ANALOG":
        if (track != null && delta != 0) {
          track.setDelayAnalog(delta > 0);
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
    if (!parent.isSquareDefined(visualCol)) return;
    int editedModelTrack = getEditedModelTrack();
    int activeClipId = getActiveClipId();
    org.deluge.model.TrackModel tModel = editedTrackOrNull();
    if (tModel == null) return;
    org.deluge.model.ClipModel cModel = activeClipOrNull();
    if (cModel == null) {
      cModel = tModel.getActiveClip();
    }
    if (cModel == null) return;

    final int srcModelRow = parent.getModelRow(visibleRow);
    final int srcClipRow = parent.getClipRowIndex(cModel, srcModelRow, false);
    if (srcClipRow < 0) {
      return;
    }
    int dstModelRow = srcModelRow;
    int activeCol = parent.getActiveCol(visibleRow, visualCol);
    if (activeCol < 0) return;

    org.deluge.model.StepData sd = cModel.getStep(srcClipRow, activeCol);
    if (sd == null || !sd.active()) {
      return; // Gestural pad adjustments only apply to active (programmed) notes!
    }

    int rotation = e.getWheelRotation();
    int dir = -rotation; // Scroll up = positive change, scroll down = negative change

    org.deluge.model.StepData updated = null;
    String oledParam = "";
    String oledValue = "";
    boolean isTranspose = false;

    if (parent.isShiftHeld() && e.isAltDown()) {
      // Shift + Alt held = Adjust note fill (0% to 100%, 5% increments)
      float newFill = Math.max(0.0f, Math.min(1.0f, sd.fill() + dir * 0.05f));
      newFill = Math.round(newFill * 100.0f) / 100.0f;
      updated = sd.withFill(newFill);
      oledParam = "FILL";
      oledValue = (newFill == 0.0f) ? "OFF" : (int) (newFill * 100) + "%";
    } else if (parent.isShiftHeld()) {
      // Shift held = Adjust note probability (0% to 100%, 5% increments)
      float newProb = Math.max(0.0f, Math.min(1.0f, sd.probability() + dir * 0.05f));
      newProb = Math.round(newProb * 100.0f) / 100.0f;
      updated = sd.withProbability(newProb);
      oledParam = "PROB";
      oledValue = (int) (newProb * 100) + "%";
    } else if (e.isAltDown()) {
      // Alt held = Adjust note gate/length (0.125 to 64.0 steps, 0.25 step increments)
      float newGate = Math.max(0.125f, Math.min(64.0f, sd.gate() + dir * 0.25f));
      updated = sd.withGate(newGate);
      oledParam = "GATE";
      oledValue = String.format("%.2f", newGate);
    } else if (e.isControlDown()) {
      // Ctrl held = Transpose note pitch (up/down by semitones)
      isTranspose = true;
      int newPitch = Math.max(0, Math.min(127, sd.pitch() + dir));
      int oldPitch = sd.pitch();
      if (newPitch != oldPitch) {
        int newModelRow = parent.getRowFromPitch(newPitch);
        if (newModelRow >= 0) {
          updated = sd.withPitch(newPitch);
          dstModelRow = newModelRow;
        }
      }
      oledParam = "PITCH";
      oledValue = String.valueOf(newPitch);
    } else {
      // No modifiers = Adjust note velocity (0.0 to 1.0, 0.05 increments, displayed as 0..127)
      float newVel = Math.max(0.0f, Math.min(1.0f, sd.velocity() + dir * 0.05f));
      newVel = Math.round(newVel * 100.0f) / 100.0f;
      updated = sd.withVelocity(newVel);
      oledParam = "VEL";
      oledValue = String.valueOf((int) (newVel * 127));
    }

    if (updated != null) {
      if (SwingGridPanel.isCrossScreenWrapActive) {
        int dstClipRow = srcClipRow;
        if (isTranspose && dstModelRow != srcModelRow) {
          dstClipRow = parent.getClipRowIndex(cModel, dstModelRow, true);
          if (dstClipRow < 0) {
            return;
          }
        }
        int srcEngineRow = parent.getEngineRowForStoredClipRow(srcClipRow);
        int dstEngineRow = parent.getEngineRowForStoredClipRow(dstClipRow);
        int screenSteps = parent.getScreenStepCount();
        int baseCol = activeCol % screenSteps;
        int trackLen = cModel.getStepCount();
        java.util.List<Consequence> steps = new java.util.ArrayList<>();
        for (int c = baseCol; c < trackLen; c += screenSteps) {
          StepData targetSd = cModel.getStep(srcClipRow, c);
          if (targetSd != null && targetSd.active()) {
            StepData newSt;
            boolean movedRow = false;
            StepData oldDstSd = cModel.getStep(dstClipRow, c);
            if (isTranspose) {
              int oldPitch = targetSd.pitch();
              int newPitch = updated.pitch();
              if (newPitch != oldPitch && dstClipRow != srcClipRow) {
                movedRow = true;
                cModel.setStep(srcClipRow, c, org.deluge.model.StepData.empty());
                if (parent.bridge != null && srcEngineRow >= 0) {
                  parent.bridge.setStep(srcEngineRow, c, false);
                }
              }
              newSt = targetSd.withPitch(newPitch);
            } else if (parent.isShiftHeld() && e.isAltDown()) {
              newSt = targetSd.withFill(updated.fill());
            } else if (parent.isShiftHeld()) {
              newSt = targetSd.withProbability(updated.probability());
            } else if (e.isAltDown()) {
              newSt = targetSd.withGate(updated.gate());
            } else {
              newSt = targetSd.withVelocity(updated.velocity());
            }

            cModel.setStep(dstClipRow, c, newSt);

            if (parent.bridge != null && dstEngineRow >= 0) {
              parent.bridge.setStep(dstEngineRow, c, newSt.active());
              parent.bridge.setVelocity(dstEngineRow, c, newSt.velocity());
              parent.bridge.setGate(dstEngineRow, c, newSt.gate());
              parent.bridge.setPitch(dstEngineRow, c, newSt.pitch());
              parent.bridge.setStepProbability(dstEngineRow, c, newSt.probability());
              parent.bridge.setIterance(dstEngineRow, c, newSt.iterance());
              parent.bridge.setStepFill(dstEngineRow, c, newSt.fill());
              parent.bridge.setStepNudge(dstEngineRow, c, newSt.nudge());
            }

            if (getProjectModel() != null) {
              if (movedRow) {
                steps.add(
                    new Consequence.StepConsequence(
                        getProjectModel(),
                        editedModelTrack,
                        activeClipId,
                        srcClipRow,
                        c,
                        targetSd,
                        org.deluge.model.StepData.empty()));
                steps.add(
                    new Consequence.StepConsequence(
                        getProjectModel(),
                        editedModelTrack,
                        activeClipId,
                        dstClipRow,
                        c,
                        oldDstSd != null ? oldDstSd : org.deluge.model.StepData.empty(),
                        newSt));
              } else {
                steps.add(
                    new Consequence.StepConsequence(
                        getProjectModel(),
                        editedModelTrack,
                        activeClipId,
                        dstClipRow,
                        c,
                        targetSd,
                        newSt));
              }
            }
          }
        }
        if (!steps.isEmpty() && getProjectModel() != null) {
          getProjectModel()
              .getUndoRedoStack()
              .push(new Consequence.CompoundConsequence("Adjust Step Wrap", steps));
        }
      } else {
        int dstClipRow = srcClipRow;
        boolean movedRow = false;
        StepData oldDstSd = cModel.getStep(srcClipRow, activeCol);
        if (isTranspose && dstModelRow != srcModelRow) {
          dstClipRow = parent.getClipRowIndex(cModel, dstModelRow, true);
          if (dstClipRow < 0) {
            return;
          }
          if (dstClipRow != srcClipRow) {
            movedRow = true;
            oldDstSd = cModel.getStep(dstClipRow, activeCol);
            cModel.setStep(srcClipRow, activeCol, org.deluge.model.StepData.empty());
            int srcEngineRow = parent.getEngineRowForStoredClipRow(srcClipRow);
            if (parent.bridge != null && srcEngineRow >= 0) {
              parent.bridge.setStep(srcEngineRow, activeCol, false);
            }
          }
        }
        cModel.setStep(dstClipRow, activeCol, updated);
        int dstEngineRow = parent.getEngineRowForStoredClipRow(dstClipRow);
        if (parent.bridge != null && dstEngineRow >= 0) {
          parent.bridge.setStep(dstEngineRow, activeCol, updated.active());
          parent.bridge.setVelocity(dstEngineRow, activeCol, updated.velocity());
          parent.bridge.setGate(dstEngineRow, activeCol, updated.gate());
          parent.bridge.setPitch(dstEngineRow, activeCol, updated.pitch());
          parent.bridge.setStepProbability(dstEngineRow, activeCol, updated.probability());
          parent.bridge.setIterance(dstEngineRow, activeCol, updated.iterance());
          parent.bridge.setStepFill(dstEngineRow, activeCol, updated.fill());
          parent.bridge.setStepNudge(dstEngineRow, activeCol, updated.nudge());
        }
        if (getProjectModel() != null) {
          if (movedRow) {
            java.util.List<Consequence> transposeSteps = new java.util.ArrayList<>();
            transposeSteps.add(
                new Consequence.StepConsequence(
                    getProjectModel(),
                    editedModelTrack,
                    activeClipId,
                    srcClipRow,
                    activeCol,
                    sd,
                    org.deluge.model.StepData.empty()));
            transposeSteps.add(
                new Consequence.StepConsequence(
                    getProjectModel(),
                    editedModelTrack,
                    activeClipId,
                    dstClipRow,
                    activeCol,
                    oldDstSd != null ? oldDstSd : org.deluge.model.StepData.empty(),
                    updated));
            getProjectModel()
                .getUndoRedoStack()
                .push(new Consequence.CompoundConsequence("Transpose Step", transposeSteps));
          } else {
            getProjectModel()
                .getUndoRedoStack()
                .push(
                    new Consequence.StepConsequence(
                        getProjectModel(),
                        editedModelTrack,
                        activeClipId,
                        dstClipRow,
                        activeCol,
                        sd,
                        updated));
          }
        }
      }

      // Display transient parameter change on OLED readout
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(oledParam, oledValue);
      }

      parent.fireProjectChanged();
      parent.refresh();
    }
  }
}
