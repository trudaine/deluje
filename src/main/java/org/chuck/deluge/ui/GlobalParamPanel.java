package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Simple visuals for global track parameters. */
public class GlobalParamPanel extends HBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private int selectedTrack = 0;

  private final Slider levelSlider;
  private final Slider transSlider;

  public GlobalParamPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(25);
    setPadding(new Insets(5, 10, 5, 369));
    setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #333; -fx-border-width: 1 0 0 0;");

    // 1. Track Level
    HBox levelBox = new HBox(10);
    levelBox.setAlignment(Pos.CENTER_LEFT);
    Label levelLabel = new Label("TRACK LEVEL:");
    levelLabel.setStyle("-fx-text-fill: #aaa; -fx-font-family: 'Monospaced'; -fx-font-size: 10px; -fx-font-weight: bold;");
    levelSlider = new Slider(0.0, 1.0, 1.0);
    levelSlider.setPrefWidth(150);
    levelSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
      Object obj = vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
      if (obj instanceof ChuckArray array) {
        array.setFloat(selectedTrack, newVal.floatValue());
      }
    });
    levelBox.getChildren().addAll(levelLabel, levelSlider);

    // 2. Track Pitch Transpose
    HBox transBox = new HBox(10);
    transBox.setAlignment(Pos.CENTER_LEFT);
    Label transLabel = new Label("TRANSPOSE:");
    transLabel.setStyle("-fx-text-fill: #aaa; -fx-font-family: 'Monospaced'; -fx-font-size: 10px; -fx-font-weight: bold;");
    transSlider = new Slider(-24, 24, 0);
    transSlider.setPrefWidth(150);
    transSlider.setShowTickMarks(true);
    transSlider.setShowTickLabels(true);
    transSlider.setSnapToTicks(true);
    transSlider.setMajorTickUnit(12);
    transSlider.setMinorTickCount(12);
    transBox.getChildren().addAll(transLabel, transSlider);

    getChildren().addAll(levelBox, transBox);
    startTimer();
  }

  public void setSelectedTrack(int trackIndex) {
    this.selectedTrack = trackIndex;
  }

  private void startTimer() {
    javafx.animation.AnimationTimer timer =
        new javafx.animation.AnimationTimer() {
          @Override
          public void handle(long now) {
            Object obj = vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
            if (obj instanceof ChuckArray array) {
              double current = array.getFloat(selectedTrack);
              if (Math.abs(levelSlider.getValue() - current) > 0.01) {
                levelSlider.setValue(current);
              }
            }
          }
        };
    timer.start();
  }
}
