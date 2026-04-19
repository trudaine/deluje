package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** The main 8x16 sequencer grid. */
public class MatrixPanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private final TrackRowPanel[] rows;
  private int currentStep = -1;

  public MatrixPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.TOP_CENTER);
    setSpacing(5);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #1a1a1a;");

    rows = new TrackRowPanel[8]; // 8 tracks (Kit)

    // Default labels for 4 Kit and 4 Synth tracks
    String[] trackNames = {
      "KICK", "SNARE", "HIHAT", "OPEN HAT", "SYNTH 1", "SYNTH 2", "SYNTH 3", "SYNTH 4"
    };

    for (int i = 0; i < 8; i++) {
      rows[i] = new TrackRowPanel(i, trackNames[i], bridge);
      getChildren().add(rows[i]);
    }
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
