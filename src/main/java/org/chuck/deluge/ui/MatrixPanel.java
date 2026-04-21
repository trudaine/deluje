package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;

/** The main 8x16 sequencer grid. */
public class MatrixPanel extends javafx.scene.layout.BorderPane {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private final TrackRowPanel[] rows;
  private final javafx.scene.control.ScrollPane scrollPane;
  private final VBox rowContainer;

  private int currentStep = -1;
  private int selectedTrack = 0;
  private EditMode currentEditMode = EditMode.VELOCITY;
  private java.util.function.Consumer<Integer> onTrackSelected;

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
    scrollPane.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
    scrollPane.setStyle("-fx-background: #1a1a1a; -fx-border-color: transparent;");

    setCenter(scrollPane);

    rows = new TrackRowPanel[8]; // 8 tracks (Kit)

    // Default labels for 4 Kit and 4 Synth tracks
    String[] trackNames = {
      "KICK", "SNARE", "HIHAT", "OPEN HAT", "SYNTH 1", "SYNTH 2", "SYNTH 3", "SYNTH 4"
    };

    for (int i = 0; i < 8; i++) {
      int trackIdx = i;
      rows[i] = new TrackRowPanel(i, trackNames[i], vm, bridge, this::getCurrentEditMode);
      rows[i].setOnMouseClicked(e -> selectTrack(trackIdx));
      rowContainer.getChildren().add(rows[i]);
    }

    selectTrack(0); // Default selection
  }

  public void setOnTrackSelected(java.util.function.Consumer<Integer> callback) {
    this.onTrackSelected = callback;
  }

  private void selectTrack(int index) {
    if (index < 0 || index >= rows.length) return;

    // Clear old selection style
    rows[selectedTrack].setStyle("");

    selectedTrack = index;
    // Highlight selected row
    rows[selectedTrack].setStyle("-fx-border-color: #00ffcc; -fx-border-width: 0 0 0 4;");

    if (onTrackSelected != null) {
      onTrackSelected.accept(selectedTrack);
    }
  }

  public void applyKit(org.chuck.deluge.model.KitTrackModel kit) {
    java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds = kit.getSounds();
    for (int i = 0; i < 8; i++) {
      if (i < sounds.size()) {
        org.chuck.deluge.model.KitTrackModel.KitSound sound = sounds.get(i);
        rows[i].updateForKit(sound);
        
        // Update bridge to indicate this is a kit track
        ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE)).setInt(i, 0L);
        
        // Send sample path to ChucK
        String path = sound.getSamplePath();
        if (path != null && !path.isEmpty()) {
            vm.setGlobalString("g_sample_" + i, path);
        }
      } else {
        // Clear/Mute unused tracks
        rows[i].updateForKit(new org.chuck.deluge.model.KitTrackModel.KitSound("EMPTY"));
        bridge.setMute(i, true);
        ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE)).setInt(i, 0L);
        vm.setGlobalString("g_sample_" + i, "");
      }
    }
    // Trigger engine load
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
  }

  public void setEditMode(EditMode mode) {
    this.currentEditMode = mode;
    for (TrackRowPanel row : rows) {
      row.setEditMode(mode);
    }
  }

  public EditMode getCurrentEditMode() {
    return currentEditMode;
  }

  public void updateStep(int step) {
    if (step == currentStep) return; // Prevent redundant UI updates

    // De-highlight old step
    if (currentStep >= 0 && currentStep < 16) {
      for (TrackRowPanel row : rows) {
        row.highlightStep(currentStep, false);
      }
    }

    // Highlight new step
    if (step >= 0 && step < 16) {
      for (TrackRowPanel row : rows) {
        row.highlightStep(step, true);
      }
    }

    currentStep = step;
  }
}
