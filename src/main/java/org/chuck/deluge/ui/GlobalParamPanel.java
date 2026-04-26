package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
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
  private boolean isProgrammaticUpdate = false;
  private Slider tempoSlider;
  private int selectedTrack = 0;

  private java.util.function.Consumer<Float> onGlobalTempoChange;

  public void setOnGlobalTempoChange(java.util.function.Consumer<Float> callback) {
    this.onGlobalTempoChange = callback;
  }

  private final Slider levelSlider;
  private final Slider transSlider;

  public GlobalParamPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(25);
    setPadding(new Insets(5, 10, 5, 20));
    setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #333; -fx-border-width: 1 0 0 0;");

    // 1. Track Level
    HBox levelBox = new HBox(10);
    levelBox.setAlignment(Pos.CENTER_LEFT);
    Label levelLabel = new Label("TRACK LEVEL:");
    levelLabel.setStyle(
        "-fx-text-fill: #aaa; -fx-font-family: 'Monospaced'; -fx-font-size: 10px; -fx-font-weight: bold;");
    levelLabel.setPrefWidth(120);
    if (Boolean.parseBoolean(
        org.chuck.deluge.project.PreferencesManager.get("show.tooltips", "true"))) {
      javafx.scene.control.Tooltip tooltip =
          new javafx.scene.control.Tooltip(
              org.chuck.deluge.ui.util.HelpTextManager.getHelp("LEVEL"));
      tooltip.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 11px;");
      tooltip.setShowDelay(javafx.util.Duration.millis(100));
      javafx.scene.control.Tooltip.install(levelLabel, tooltip);
    }
    levelSlider = new Slider(0.0, 1.0, 1.0);
    levelSlider.setPrefWidth(150);
    levelSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
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
    transLabel.setStyle(
        "-fx-text-fill: #aaa; -fx-font-family: 'Monospaced'; -fx-font-size: 10px; -fx-font-weight: bold;");
    transLabel.setPrefWidth(120);
    if (Boolean.parseBoolean(
        org.chuck.deluge.project.PreferencesManager.get("show.tooltips", "true"))) {
      javafx.scene.control.Tooltip transTooltip =
          new javafx.scene.control.Tooltip(
              org.chuck.deluge.ui.util.HelpTextManager.getHelp("TRANSPOSE"));
      transTooltip.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 11px;");
      transTooltip.setShowDelay(javafx.util.Duration.millis(100));
      javafx.scene.control.Tooltip.install(transLabel, transTooltip);
    }
    transSlider = new Slider(-24, 24, 0);
    transSlider.setPrefWidth(150);
    transSlider.setShowTickMarks(true);
    transSlider.setShowTickLabels(true);
    transSlider.setSnapToTicks(true);
    transSlider.setMajorTickUnit(12);
    transSlider.setMinorTickCount(12);
    transBox.getChildren().addAll(transLabel, transSlider);

    // 3. Global Tempo
    HBox tempoBox = new HBox(10);
    tempoBox.setAlignment(Pos.CENTER_LEFT);
    Label tempoLabel = new Label("GLOBAL TEMPO:");
    tempoLabel.setStyle(
        "-fx-text-fill: #aaa; -fx-font-family: 'Monospaced'; -fx-font-size: 10px; -fx-font-weight: bold;");
    tempoLabel.setPrefWidth(120);
    tempoSlider = new Slider(60, 200, 120);
    tempoSlider.setPrefWidth(100);
    tempoSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (!isProgrammaticUpdate) {
                if (onGlobalTempoChange != null) {
                  onGlobalTempoChange.accept(newVal.floatValue());
                }
              }
            });
    tempoBox.getChildren().addAll(tempoLabel, tempoSlider);

    // 4. Global Scale combo
    HBox scaleBox = new HBox(10);
    scaleBox.setAlignment(Pos.CENTER_LEFT);
    Label scaleLabel = new Label("SCALE:");
    scaleLabel.setStyle(
        "-fx-text-fill: #aaa; -fx-font-family: 'Monospaced'; -fx-font-size: 10px; -fx-font-weight: bold;");
    scaleLabel.setPrefWidth(120);

    ComboBox<String> scaleCombo = new ComboBox<>();
    scaleCombo.getItems().addAll("Major", "Minor", "Pentatonic", "Chromatic");
    scaleCombo.setValue("Major");
    scaleBox.getChildren().addAll(scaleLabel, scaleCombo);

    javafx.scene.layout.VBox rows = new javafx.scene.layout.VBox(5);
    javafx.scene.layout.HBox row1 = new javafx.scene.layout.HBox(25);
    javafx.scene.layout.HBox row2 = new javafx.scene.layout.HBox(25);

    row1.getChildren().addAll(levelBox, transBox);
    row2.getChildren().addAll(tempoBox, scaleBox);
    rows.getChildren().addAll(row1, row2);

    getChildren().add(rows);
    startTimer();
  }

  public void setGlobalTempo(float bpm) {
    javafx.application.Platform.runLater(
        () -> {
          if (tempoSlider != null) {
            isProgrammaticUpdate = true;
            tempoSlider.setValue(bpm);
            isProgrammaticUpdate = false;
          }
        });
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
