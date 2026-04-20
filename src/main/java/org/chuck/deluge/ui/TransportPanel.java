package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Handles playback control and global tempo/swing parameters. */
public class TransportPanel extends HBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private final Button playBtn;
  private final Button stopBtn;
  private final Label tempoLabel;
  private final Slider tempoSlider;
  private final Label swingLabel;
  private final Slider swingSlider;

  public TransportPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(20);
    setPadding(new Insets(10));
    setStyle(
        "-fx-background-color: #2b2b2b; -fx-border-color: #3d3d3d; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-radius: 5;");

    // Transport Controls
    HBox transportButtons = new HBox(10);

    playBtn = new Button("▶ PLAY");
    playBtn.setStyle(
        "-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 20;");
    playBtn.setOnAction(e -> vm.setGlobalInt(BridgeContract.G_PLAY, 1L));

    stopBtn = new Button("■ STOP");
    stopBtn.setStyle(
        "-fx-background-color: #c62828; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 20;");
    stopBtn.setOnAction(
        e -> {
          vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
          vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
        });

    transportButtons.getChildren().addAll(playBtn, stopBtn);

    // Tempo Control
    VBox tempoBox = new VBox(5);
    tempoBox.setAlignment(Pos.CENTER);
    tempoLabel = new Label("TEMPO (BPM): 120.0");
    tempoLabel.setTextFill(Color.web("#e0e0e0"));
    tempoSlider = new Slider(60, 200, 120);
    tempoSlider.setPrefWidth(150);
    tempoSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              double bpm = newVal.doubleValue();
              tempoLabel.setText(String.format("TEMPO (BPM): %.1f", bpm));
              vm.setGlobalFloat(BridgeContract.G_BPM, bpm);
            });
    tempoBox.getChildren().addAll(tempoLabel, tempoSlider);

    // Swing Control
    VBox swingBox = new VBox(5);
    swingBox.setAlignment(Pos.CENTER);
    swingLabel = new Label("SWING: 50%");
    swingLabel.setTextFill(Color.web("#e0e0e0"));
    swingSlider = new Slider(0, 100, 50);
    swingSlider.setPrefWidth(150);
    swingSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              double swing = newVal.doubleValue() / 100.0;
              swingLabel.setText(String.format("SWING: %.0f%%", newVal.doubleValue()));
              vm.setGlobalFloat(BridgeContract.G_SWING, swing);
            });
    swingBox.getChildren().addAll(swingLabel, swingSlider);

    // Volume Control
    VBox volBox = new VBox(5);
    volBox.setAlignment(Pos.CENTER);
    Label volLabel = new Label("MASTER VOL");
    volLabel.setTextFill(Color.web("#e0e0e0"));
    Slider volSlider = new Slider(0, 100, 70); // Default 0.7
    volSlider.setPrefWidth(150);
    volSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, newVal.doubleValue() / 100.0);
            });
    volBox.getChildren().addAll(volLabel, volSlider);

    // File Control
    Button loadBtn = new Button("📂 LOAD XML");
    loadBtn.setStyle("-fx-background-color: #555555; -fx-text-fill: white; -fx-font-weight: bold;");
    loadBtn.setOnAction(
        e -> {
          javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
          fc.getExtensionFilters()
              .add(new javafx.stage.FileChooser.ExtensionFilter("Deluge XML", "*.XML"));
          java.io.File file = fc.showOpenDialog(null);
          if (file != null) {
            try {
              if (file.getName().toUpperCase().contains("SYNTH")) {
                org.chuck.deluge.model.SynthTrackModel synth =
                    org.chuck.deluge.xml.DelugeXmlParser.parseSynth(file);
                System.out.println(
                    "Loaded Synth XML: " + synth.getName() + " OSC1: " + synth.getOsc1Type());
              } else {
                org.chuck.deluge.model.KitTrackModel kit =
                    org.chuck.deluge.xml.DelugeXmlParser.parseKit(file);
                System.out.println(
                    "Loaded Kit XML: " + kit.getName() + " Sample: " + kit.getSamplePath());
              }
            } catch (Exception ex) {
              System.err.println("Failed to parse XML: " + ex.getMessage());
              ex.printStackTrace();
            }
          }
        });

    Button debugBtn = new Button("🐞 DEBUG");
    debugBtn.setStyle(
        "-fx-background-color: #555555; -fx-text-fill: white; -fx-font-weight: bold;");
    debugBtn.setOnAction(
        e -> {
          org.chuck.audio.util.DacChannel.DEBUG_AUDIO =
              !org.chuck.audio.util.DacChannel.DEBUG_AUDIO;
          if (org.chuck.audio.util.DacChannel.DEBUG_AUDIO) {
            debugBtn.setStyle(
                "-fx-background-color: #ff9800; -fx-text-fill: black; -fx-font-weight: bold;");
          } else {
            debugBtn.setStyle(
                "-fx-background-color: #555555; -fx-text-fill: white; -fx-font-weight: bold;");
          }
        });

    getChildren().addAll(transportButtons, tempoBox, swingBox, volBox, loadBtn, debugBtn);
  }
}
