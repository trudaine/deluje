package org.chuck.deluge.ui.popover;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chuck.deluge.BridgeContract;

public class ArpeggiatorDialog extends Dialog<Void> {

  private final BridgeContract bridge;
  private final int trackId;

  private final Slider hitsSlider;
  private final Slider stepsSlider;
  private final Slider offsetSlider;
  private final HBox previewBox;

  public ArpeggiatorDialog(BridgeContract bridge, int trackId) {
    this.bridge = bridge;
    this.trackId = trackId;

    setTitle("Euclidean Arpeggiator");
    setHeaderText("Generate a Euclidean rhythm for this track.");

    DialogPane pane = getDialogPane();
    pane.getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CLOSE);

    VBox content = new VBox(10);
    content.setPadding(new Insets(10));

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);

    grid.add(new Label("Hits (K):"), 0, 0);
    hitsSlider = new Slider(1, 32, 5);
    hitsSlider.setShowTickLabels(true);
    hitsSlider.setShowTickMarks(true);
    hitsSlider.setMajorTickUnit(8);
    hitsSlider.setMinorTickCount(1);
    hitsSlider.setSnapToTicks(true);
    grid.add(hitsSlider, 1, 0);

    grid.add(new Label("Steps (N):"), 0, 1);
    stepsSlider = new Slider(1, 32, 8);
    stepsSlider.setShowTickLabels(true);
    stepsSlider.setShowTickMarks(true);
    stepsSlider.setMajorTickUnit(8);
    stepsSlider.setMinorTickCount(1);
    stepsSlider.setSnapToTicks(true);
    grid.add(stepsSlider, 1, 1);

    grid.add(new Label("Offset (O):"), 0, 2);
    offsetSlider = new Slider(0, 32, 0);
    offsetSlider.setShowTickLabels(true);
    offsetSlider.setShowTickMarks(true);
    offsetSlider.setMajorTickUnit(8);
    offsetSlider.setMinorTickCount(1);
    offsetSlider.setSnapToTicks(true);
    grid.add(offsetSlider, 1, 2);

    previewBox = new HBox(5);
    previewBox.setAlignment(Pos.CENTER);
    previewBox.setPrefHeight(40);
    previewBox.setStyle(
        "-fx-background-color: #252525; -fx-border-color: #333; -fx-border-radius: 5; -fx-background-radius: 5;");

    content.getChildren().addAll(grid, new Label("Preview:"), previewBox);
    pane.setContent(content);

    // Listeners
    hitsSlider.valueProperty().addListener((obs, old, newVal) -> updatePreview());
    stepsSlider.valueProperty().addListener((obs, old, newVal) -> updatePreview());
    offsetSlider.valueProperty().addListener((obs, old, newVal) -> updatePreview());

    updatePreview();

    setResultConverter(
        buttonType -> {
          if (buttonType == ButtonType.APPLY) {
            applyPattern();
          }
          return null;
        });
  }

  private void updatePreview() {
    int hits = (int) hitsSlider.getValue();
    int steps = (int) stepsSlider.getValue();
    int offset = (int) offsetSlider.getValue();

    if (hits > steps) hits = steps;

    boolean[] pattern = generateEuclidean(hits, steps, offset);

    previewBox.getChildren().clear();
    for (int i = 0; i < steps; i++) {
      Button dot = new Button();
      dot.setPrefSize(20, 20);
      dot.setDisable(true);
      if (pattern[i]) {
        dot.setStyle("-fx-background-color: #00ffcc; -fx-opacity: 1.0;");
      } else {
        dot.setStyle("-fx-background-color: #444444; -fx-opacity: 1.0;");
      }
      previewBox.getChildren().add(dot);
    }
  }

  private void applyPattern() {
    int hits = (int) hitsSlider.getValue();
    int steps = (int) stepsSlider.getValue();
    int offset = (int) offsetSlider.getValue();

    if (hits > steps) hits = steps;

    boolean[] pattern = generateEuclidean(hits, steps, offset);

    // Apply to bridge
    for (int i = 0; i < 16; i++) {
      if (i < steps) {
        bridge.setStep(trackId, i, pattern[i]);
      } else {
        bridge.setStep(trackId, i, false); // Clear rest
      }
    }
  }

  private boolean[] generateEuclidean(int hits, int steps, int offset) {
    boolean[] pattern = new boolean[steps];
    if (steps == 0 || hits == 0) return pattern;

    float slope = (float) hits / steps;
    float current = 0;
    for (int i = 0; i < steps; i++) {
      current += slope;
      if (current >= 1.0f) {
        pattern[i] = true;
        current -= 1.0f;
      }
    }

    // Apply offset
    if (offset > 0) {
      boolean[] rotated = new boolean[steps];
      for (int i = 0; i < steps; i++) {
        rotated[(i + offset) % steps] = pattern[i];
      }
      return rotated;
    }

    return pattern;
  }
}
