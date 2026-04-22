package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.Scales;

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

  private java.util.function.Consumer<org.chuck.deluge.model.KitTrackModel> onKitLoaded;
  private java.util.function.Consumer<Boolean> onRecordToggled;

  public TransportPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(15);
    setPadding(new Insets(10));
    setStyle(
        "-fx-background-color: #2b2b2b; -fx-border-color: #3d3d3d; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-radius: 5;");

    // Transport Controls
    HBox transportButtons = new HBox(10);

    playBtn = new Button("▶ PLAY");
    playBtn.setStyle(
        "-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 15;");
    playBtn.setOnAction(e -> vm.setGlobalInt(BridgeContract.G_PLAY, 1L));

    stopBtn = new Button("■ STOP");
    stopBtn.setStyle(
        "-fx-background-color: #c62828; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 15;");
    stopBtn.setOnAction(
        e -> {
          vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
          vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
        });

    javafx.scene.control.ToggleButton recordBtn = new javafx.scene.control.ToggleButton("● REC");
    recordBtn.setStyle("-fx-base: #444; -fx-text-fill: #ff1744; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 15;");
    recordBtn.setOnAction(e -> {
        if (onRecordToggled != null) {
            onRecordToggled.accept(recordBtn.isSelected());
        }
    });

    transportButtons.getChildren().addAll(playBtn, stopBtn, recordBtn);

    // Tempo Control
    VBox tempoBox = new VBox(2);
    tempoBox.setAlignment(Pos.CENTER);
    tempoLabel = new Label("TEMPO: 120.0");
    tempoLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 9px;");
    tempoSlider = new Slider(60, 200, 120);
    tempoSlider.setPrefWidth(100);
    tempoSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              double bpm = newVal.doubleValue();
              tempoLabel.setText(String.format("TEMPO: %.1f", bpm));
              vm.setGlobalFloat(BridgeContract.G_BPM, bpm);
            });
    tempoBox.getChildren().addAll(tempoLabel, tempoSlider);

    // Swing Control
    VBox swingBox = new VBox(2);
    swingBox.setAlignment(Pos.CENTER);
    swingLabel = new Label("SWING: 50%");
    swingLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 9px;");
    swingSlider = new Slider(0, 100, 50);
    swingSlider.setPrefWidth(100);
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
    VBox volBox = new VBox(2);
    volBox.setAlignment(Pos.CENTER);
    Label volLabel = new Label("MASTER VOL");
    volLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 9px;");
    Slider volSlider = new Slider(0, 100, 70); // Default 0.7
    volSlider.setPrefWidth(100);
    volSlider
        .valueProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, newVal.doubleValue() / 100.0);
            });
    volBox.getChildren().addAll(volLabel, volSlider);

    // Scale selectors
    VBox scaleBox = new VBox(2);
    scaleBox.setAlignment(Pos.CENTER);
    Label scaleLabel = new Label("SCALE/KEY");
    scaleLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 9px;");
    HBox comboRow = new HBox(5);

    ComboBox<String> keyCombo = new ComboBox<>();
    keyCombo.getItems().addAll(Scales.KEY_NAMES);
    int initialKey = (int) vm.getGlobalInt(BridgeContract.G_ROOT_KEY);
    keyCombo.setValue(Scales.KEY_NAMES[Math.min(initialKey, 11)]);
    keyCombo.setStyle("-fx-font-size: 9px;");
    keyCombo.setOnAction(
        e -> {
          int idx = keyCombo.getSelectionModel().getSelectedIndex();
          vm.setGlobalInt(BridgeContract.G_ROOT_KEY, (long) idx);
        });

    ComboBox<Scales.ScaleType> scaleCombo = new ComboBox<>();
    scaleCombo.getItems().addAll(Scales.ScaleType.values());
    int initialScale = (int) vm.getGlobalInt(BridgeContract.G_SCALE);
    scaleCombo.setValue(
        Scales.ScaleType.values()[Math.min(initialScale, Scales.ScaleType.values().length - 1)]);
    scaleCombo.setStyle("-fx-font-size: 9px;");
    scaleCombo.setOnAction(
        e -> {
          int idx = scaleCombo.getSelectionModel().getSelectedIndex();
          vm.setGlobalInt(BridgeContract.G_SCALE, (long) idx);
        });

    comboRow.getChildren().addAll(keyCombo, scaleCombo);
    scaleBox.getChildren().addAll(scaleLabel, comboRow);

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
                    "Loaded Kit XML: "
                        + kit.getName()
                        + " with "
                        + kit.getSounds().size()
                        + " sounds.");
                if (onKitLoaded != null) {
                  onKitLoaded.accept(kit);
                }
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

  public void setOnKitLoaded(
      java.util.function.Consumer<org.chuck.deluge.model.KitTrackModel> onKitLoaded) {
    this.onKitLoaded = onKitLoaded;
  }

  public void setOnRecordToggled(java.util.function.Consumer<Boolean> onRecordToggled) {
    this.onRecordToggled = onRecordToggled;
  }
}
