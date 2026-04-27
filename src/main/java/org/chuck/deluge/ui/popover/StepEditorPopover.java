package org.chuck.deluge.ui.popover;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.chuck.deluge.BridgeContract;

/** A popup editor for a single sequencer step. Allows adjusting Velocity, Gate, and Probability. */
public class StepEditorPopover extends Popup {
  private final BridgeContract bridge;
  private final int track;
  private final int step;

  public StepEditorPopover(BridgeContract bridge, int track, int step) {
    this.bridge = bridge;
    this.track = track;
    this.step = step;

    setAutoHide(true);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));
    root.setStyle(
        "-fx-background-color: #333333; -fx-border-color: #555555; -fx-border-width: 2; -fx-background-radius: 5; -fx-border-radius: 5;");

    Label title = new Label(String.format("Step Edit: TR %d, ST %d", track + 1, step + 1));
    title.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
    root.getChildren().add(title);

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);
    grid.setAlignment(Pos.CENTER);

    // Velocity (Vertical Slider as per design)
    VBox velBox = new VBox(5);
    velBox.setAlignment(Pos.CENTER);
    Label velLabel = new Label("VEL");
    velLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 9;");
    Slider velSlider = new Slider(0, 1.0, bridge.getVelocity(track, step));
    velSlider.setOrientation(javafx.geometry.Orientation.VERTICAL);
    velSlider.setPrefHeight(100);
    velSlider
        .valueProperty()
        .addListener(
            (obs, o, n) -> {
              bridge.setVelocity(track, step, n.doubleValue());
              bridge.syncActiveClipToLibrary(track);
            });
    velBox.getChildren().addAll(velSlider, velLabel);

    // Gate (Horizontal Slider)
    VBox gateBox = new VBox(5);
    Label gateLabel = new Label("GATE");
    gateLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 9;");
    Slider gateSlider = new Slider(0, 1.0, bridge.getGate(track, step));
    gateSlider
        .valueProperty()
        .addListener(
            (obs, o, n) -> {
              bridge.setGate(track, step, n.doubleValue());
              bridge.syncActiveClipToLibrary(track);
            });
    gateBox.getChildren().addAll(gateSlider, gateLabel);

    // Probability (Horizontal Slider)
    VBox probBox = new VBox(5);
    Label probLabel = new Label("PROB");
    probLabel.setStyle("-fx-text-fill: #aaa; -fx-font-size: 9;");
    Slider probSlider = new Slider(0, 1.0, bridge.getStepProbability(track, step));
    probSlider
        .valueProperty()
        .addListener(
            (obs, o, n) -> {
              bridge.setStepProbability(track, step, n.doubleValue());
              bridge.syncActiveClipToLibrary(track);
            });
    probBox.getChildren().addAll(probSlider, probLabel);

    grid.add(velBox, 0, 0, 1, 2);
    grid.add(gateBox, 1, 0);
    grid.add(probBox, 1, 1);

    root.getChildren().add(grid);

    getContent().add(root);
  }
}
