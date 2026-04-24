package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;

/** The central interaction zone for sequencing. */
public class MatrixPanel extends BorderPane {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private TrackRowPanel[] rows;
  private final javafx.scene.control.ScrollPane scrollPane;
  private final VBox rowContainer;

  private int currentStep = -1;
  private int selectedTrack = 0;
  private EditMode currentEditMode = EditMode.VELOCITY;
  private int currentBaseTrack = 0;
  private boolean isSynthMode = false;
  private DelugeKeyboardPanel keyboardPanel;
  private int lastPlayedNote = -1;
  private java.util.function.Consumer<Integer> onTrackSelected;
  private Runnable onEditPresetRequest;

  public void setOnEditPresetRequest(Runnable callback) {
    this.onEditPresetRequest = callback;
  }

  public MatrixPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    rowContainer = new VBox(5);
    rowContainer.setAlignment(Pos.TOP_CENTER);
    rowContainer.setPadding(new Insets(10));
    rowContainer.setStyle("-fx-background-color: #1a1a1a;");

    scrollPane = new javafx.scene.control.ScrollPane(rowContainer);
    scrollPane.setFitToHeight(true);
    scrollPane.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.ALWAYS);
    scrollPane.setStyle("-fx-background: #1a1a1a; -fx-border-color: transparent;");

    setCenter(scrollPane);

    keyboardPanel = new DelugeKeyboardPanel();
    setBottom(keyboardPanel);

    javafx.scene.control.Button editPresetBtn = new javafx.scene.control.Button("🎹 EDIT PRESET");
    editPresetBtn.setStyle(
        "-fx-background-color: #444; -fx-text-fill: white; -fx-font-weight: bold;");
    editPresetBtn.setOnAction(
        e -> {
          if (onEditPresetRequest != null) {
            onEditPresetRequest.run();
          }
        });
    setTop(editPresetBtn);

    createRows(8);
    selectTrack(0); // Default selection

    setFocusTraversable(true);
    setOnMouseClicked(e -> requestFocus());

    setOnKeyPressed(
        e -> {
          if (e.getCode() == javafx.scene.input.KeyCode.P) {
            org.chuck.deluge.ui.popover.ArpeggiatorDialog dialog =
                new org.chuck.deluge.ui.popover.ArpeggiatorDialog(bridge, selectedTrack);
            dialog.showAndWait();
            refreshCells();
          }
        });
  }

  private void createRows(int count) {
    rowContainer.getChildren().clear();
    rows = new TrackRowPanel[count];

    Object trackTypeObj = vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
    ChuckArray trackTypeArray =
        (trackTypeObj instanceof ChuckArray) ? (ChuckArray) trackTypeObj : null;

    for (int i = 0; i < count; i++) {
      int trackIdx = i;
      rows[i] = new TrackRowPanel(i, "EMPTY", vm, bridge, this::getCurrentEditMode);
      rows[i].setOnMouseClicked(e -> selectTrack(trackIdx));
      rows[i].setStyle("-fx-border-color: transparent; -fx-border-width: 0 0 0 4;");
      rowContainer.getChildren().add(rows[i]);

      if (trackTypeArray != null && i < 8) {
        trackTypeArray.setInt(i, 0L);
      }
      if (i < 8) {
        bridge.setMute(i, true);
      }
    }

    // Start Playhead Timer
    javafx.animation.AnimationTimer timer =
        new javafx.animation.AnimationTimer() {
          @Override
          public void handle(long now) {
            int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
            if (step != currentStep) {
              int oldPage = currentStep / 16;
              int newPage = step / 16;
              if (newPage != oldPage) {
                setStepOffset(newPage * 16);
              }
              setCurrentStep(step);
              updateKeyboard(step);
            }
          
            if (bridge.getStep(0, step)) {
              for (int t = 1; t < rows.length; t++) {
                final int trackIdx = t;
                bridge.setTrackLevel(trackIdx, 0.15);
                
                javafx.animation.PauseTransition release = new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));
                release.setOnFinished(ev -> bridge.setTrackLevel(trackIdx, 0.70));
                release.play();
              }
            }
          }
        };
    timer.start();
  }

  private void updateKeyboard(int step) {
    if (isSynthMode) {
      int idx = currentBaseTrack * 16 + step;
      ChuckArray pattern = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
      ChuckArray pitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);

      if (lastPlayedNote != -1) {
        keyboardPanel.noteOff(lastPlayedNote);
        lastPlayedNote = -1;
      }

      if (pattern != null && pattern.getInt(idx) != 0) {
        int pitch = pitchArr != null ? (int) pitchArr.getInt(idx) : 0;
        int note = pitch + 60; // Map relative pitch to MIDI note
        keyboardPanel.noteOn(note, javafx.scene.paint.Color.web("#00ffcc"));
        lastPlayedNote = note;
      }
    }
  }

  private void setCurrentStep(int step) {
    if (currentStep >= 0) {
      for (TrackRowPanel row : rows) {
        row.highlightStep(currentStep, false);
      }
    }
    currentStep = step;
    if (currentStep >= 0) {
      for (TrackRowPanel row : rows) {
        row.highlightStep(currentStep, true);
      }
    }
  }

  private void setStepOffset(int offset) {
    for (TrackRowPanel row : rows) {
      row.setStepOffset(offset);
    }
  }

  public void setOnTrackSelected(java.util.function.Consumer<Integer> callback) {
    this.onTrackSelected = callback;
  }

  private void selectTrack(int index) {
    if (index < 0 || index >= rows.length) return;

    // Clear old selection style
    rows[selectedTrack].setStyle("-fx-border-color: transparent; -fx-border-width: 0 0 0 4;");

    selectedTrack = index;
    // Highlight selected row
    rows[selectedTrack].setStyle("-fx-border-color: #00ffcc; -fx-border-width: 0 0 0 4;");

    if (onTrackSelected != null) {
      onTrackSelected.accept(selectedTrack);
    }
  }

  public void applyKit(org.chuck.deluge.model.KitTrackModel kit) {
    java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
    ChuckArray trackTypeArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);

    for (int i = 0; i < 8; i++) {
      if (i < sounds.size()) {
        org.chuck.deluge.model.KitTrackModel.KitSound sound = sounds.get(i);
        rows[i].updateForKit(sound);

        if (trackTypeArr != null) trackTypeArr.setInt(i, 0L);

        String path = sound.getSamplePath();
        if (path != null && !path.isEmpty()) {
          vm.setGlobalString("g_sample_" + i, path);
          bridge.setMute(i, false);
        } else {
          bridge.setMute(i, true);
        }
      } else {
        rows[i].updateForKit(new org.chuck.deluge.model.KitTrackModel.KitSound("EMPTY"));
        bridge.setMute(i, true);
        if (trackTypeArr != null) trackTypeArr.setInt(i, 0L);
        vm.setGlobalString("g_sample_" + i, "");
      }
    }
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
  }

  public void applyClip(org.chuck.deluge.model.ClipModel clip, int baseTrack) {
    for (int r = 0; r < 8; r++) {
      if (r < clip.getRowCount()) {
        for (int s = 0; s < 16; s++) {
          if (s < clip.getStepCount()) {
            org.chuck.deluge.model.StepData step = clip.getStep(r, s);
            bridge.setStep(baseTrack + r, s, step.active());
          } else {
            bridge.setStep(baseTrack + r, s, false);
          }
        }
        if (baseTrack == currentBaseTrack) {
          rows[r].refreshCells();
        }
      }
    }
  }

  public void setBaseTrack(int baseTrack) {
    this.currentBaseTrack = baseTrack;
    for (int r = 0; r < 8; r++) {
      rows[r].setBaseTrack(baseTrack);
    }
  }

  public void setSynthMode(boolean isSynthMode) {
    this.isSynthMode = isSynthMode;
    if (isSynthMode) {
      createRows(24); // Expand to 24 rows (2 octaves)
      String[] notes = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
      int maxRows = 24;
      for (int r = 0; r < 24; r++) {
        int reverseR = (maxRows - 1) - r;
        String note = notes[reverseR % 12] + (4 + reverseR / 12); // C4, C#4... C5...
        rows[r].setNoteName(note);
        rows[r].setSynthMode(true);
        rows[r].setBaseTrack(currentBaseTrack);
      }
    } else {
      createRows(8); // Revert to 8 rows
      for (int r = 0; r < 8; r++) {
        rows[r].setSynthMode(false);
      }
    }
  }

  public void setEditMode(EditMode mode) {
    this.currentEditMode = mode;
    for (TrackRowPanel row : rows) {
      row.setEditMode(mode);
    }
  }

  public void refreshCells() {
    for (TrackRowPanel row : rows) {
      row.refreshCells();
    }
  }

  public int getSelectedTrack() {
    return selectedTrack;
  }

  public EditMode getCurrentEditMode() {
    return currentEditMode;
  }


  private org.rtmidijava.RtMidiOut midiOut;

  public void updateStep(int step) {
    if (midiOut == null) {
       try {
          midiOut = org.rtmidijava.RtMidiFactory.createDefaultOut();
          if (midiOut.getPortCount() > 0) {
             midiOut.openPort(0, "DelugeFxOut");
          }
       } catch (Exception ex) {}
    }

    // Clear old highlight
    if (currentStep >= 0 && currentStep < 16) {
      for (TrackRowPanel row : rows) {
        row.highlightStep(currentStep, false);
      }
    }

    // Highlight new step and send MIDI out
    if (step >= 0 && step < 16) {
      if (step != currentStep) {
         for (int t = 0; t < rows.length; t++) {
            if (bridge.getStep(t, step)) {

               if (midiOut != null) {
                  try {
                     midiOut.sendMessage(new byte[]{(byte)0x90, (byte)(36 + t * 2), (byte)100});
                  } catch (Exception ex) {}
               }
            }
         }
      }

      for (TrackRowPanel row : rows) {
        row.highlightStep(step, true);
      }
    }

    currentStep = step;
  }

}
