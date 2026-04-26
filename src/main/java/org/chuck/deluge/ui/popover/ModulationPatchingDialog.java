package org.chuck.deluge.ui.popover;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/** Modular parameter modulation visual depth patching dialog. */
public class ModulationPatchingDialog extends Dialog<Void> {

  private static final String[] SOURCES = {
    "LFO1", "LFO2", "ENV1", "ENV2", "VELOCITY", "NOTE", "RANDOM", "SIDECHAIN", "AFTERTOUCH"
  };

  public ModulationPatchingDialog(String paramName) {
    setTitle("Modulation Patching Matrix");
    setHeaderText("Set modulation source depth levels for: " + paramName.toUpperCase());

    DialogPane pane = getDialogPane();
    pane.getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CLOSE);

    VBox content = new VBox(10);
    content.setPadding(new Insets(10));

    GridPane grid = new GridPane();
    grid.setHgap(15);
    grid.setVgap(10);

    for (int i = 0; i < SOURCES.length; i++) {
      String source = SOURCES[i];
      Label label = new Label(source + ":");
      label.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 11px; -fx-font-weight: bold;");
      grid.add(label, 0, i);

      Slider depthSlider = new Slider(-1.0, 1.0, 0.0);
      depthSlider.setPrefWidth(200);
      depthSlider.setShowTickLabels(true);
      depthSlider.setShowTickMarks(true);
      depthSlider.setMajorTickUnit(0.5);
      depthSlider.setSnapToTicks(false);

      grid.add(depthSlider, 1, i);
    }

    ScrollPane scroll = new ScrollPane(grid);
    scroll.setPrefHeight(250);
    scroll.setFitToWidth(true);
    scroll.setStyle("-fx-background: transparent; -fx-border-color: transparent;");

    content.getChildren().add(scroll);
    pane.setContent(content);

    setResultConverter(buttonType -> null);
  }
}
