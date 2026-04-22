package org.chuck.deluge.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;

/**
 * Multi-parameter Lane Panel. Visualizes the values (Velocity, Gate, or Pitch) for the 16 steps of
 * the selected track. Aligned with the Matrix Grid.
 */
public class VelocityLanePanel extends Pane {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final Canvas canvas;
  private int selectedTrack = 0;
  private java.util.function.Supplier<EditMode> editModeSupplier;

  public VelocityLanePanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.canvas = new Canvas(800, 80);

    getChildren().add(canvas);
    setPrefHeight(80);
    setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #333; -fx-border-width: 1 0 0 0;");

    widthProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              canvas.setWidth(newVal.doubleValue());
              draw();
            });
  }

  public void setEditModeSupplier(java.util.function.Supplier<EditMode> supplier) {
    this.editModeSupplier = supplier;
  }

  public void setSelectedTrack(int trackIndex) {
    this.selectedTrack = trackIndex;
    draw();
  }

  public void draw() {
    GraphicsContext gc = canvas.getGraphicsContext2D();
    double w = canvas.getWidth();
    double h = canvas.getHeight();

    gc.clearRect(0, 0, w, h);

    EditMode mode = (editModeSupplier != null) ? editModeSupplier.get() : EditMode.VELOCITY;

    // Offset to align with the grid cells (Label + Button + Spacing)
    // Label(75) + Button(25) + Spacing(5) = 110 approx.
    double gridXOffset = 119;
    double barWidth = 45; // 40 (cell width) + 5 (spacing)

    // Draw Mode Label
    gc.setFill(Color.web("#aaa"));
    gc.setFont(Font.font("Monospaced", 10));
    gc.fillText(mode.name() + " LANE", 10, 20);

    String color =
        switch (mode) {
          case VELOCITY -> "#00ffcc";
          case GATE -> "#ff9800";
          case PITCH -> "#e91e63";
          case PROBABILITY -> "#9c27b0";
          case FILTER -> "#2196f3";
          case RESONANCE -> "#3f51b5";
          case PAN -> "#ffeb3b";
          case DELAY -> "#4caf50";
          case REVERB -> "#00bcd4";
          case LEVEL -> "#f44336";
          case START_END -> "#795548";
          case STUTTER, MOD_FX -> "#607d8b";
        };

    gc.setFill(Color.web(color, 0.6));

    String globalParam =
        switch (mode) {
          case VELOCITY -> BridgeContract.G_VELOCITY;
          case GATE -> BridgeContract.G_GATE;
          case PITCH -> BridgeContract.G_PITCH;
          case PROBABILITY -> BridgeContract.G_PROBABILITY;
          case FILTER -> BridgeContract.G_STEP_FILTER;
          case RESONANCE -> BridgeContract.G_STEP_RES;
          case PAN -> BridgeContract.G_STEP_PAN;
          case DELAY -> BridgeContract.G_STEP_DELAY;
          case REVERB -> BridgeContract.G_STEP_REVERB;
          case LEVEL -> BridgeContract.G_TRACK_LEVEL;
          case START_END -> BridgeContract.G_STEP_START;
          case STUTTER, MOD_FX -> BridgeContract.G_VELOCITY; // Fallback
        };

    Object obj = vm.getGlobalObject(globalParam);
    if (!(obj instanceof ChuckArray array)) return;

    for (int i = 0; i < 16; i++) {
      double val;
      int arrayIdx =
          (globalParam.equals(BridgeContract.G_TRACK_LEVEL))
              ? selectedTrack
              : (selectedTrack * 16 + i);

      if (mode == EditMode.PITCH) {
        int p = (int) array.getInt(arrayIdx);
        val = (p + 12) / 24.0;
      } else {
        val = array.getFloat(arrayIdx);
      }

      double barHeight = val * (h - 30);
      double x = gridXOffset + (i * barWidth);

      gc.fillRect(x, h - barHeight - 5, 40, barHeight);

      if (val < 0.05 && bridge.getStep(selectedTrack, i)) {
        gc.setStroke(Color.web(color, 0.3));
        gc.strokeLine(x + 2, h - 10, x + barWidth - 2, h - 10);
      }
    }

    // Draw grid lines
    gc.setStroke(Color.web("#333"));
    gc.setLineWidth(1);
    for (int i = 0; i <= 16; i++) {
      double x = gridXOffset + (i * barWidth);
      gc.strokeLine(x, 10, x, h - 5);
    }
  }
}
