package org.chuck.deluge.ui.config;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.FilterMode;
import org.chuck.deluge.model.SynthTrackModel;

public class SynthConfigDialog extends Stage {

  public SynthConfigDialog(
      SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    setTitle("Synth Config: " + model.getName());
    initStyle(StageStyle.UTILITY);
    initModality(Modality.NONE);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));
    root.setStyle("-fx-background-color: #2b2b2b;");

    // ── Oscillators ──
    GridPane oscGrid = new GridPane();
    oscGrid.setHgap(10);
    oscGrid.setVgap(5);

    ComboBox<String> osc1Type = new ComboBox<>();
    osc1Type.getItems().addAll("SINE", "SAW", "SQUARE", "TRIANGLE", "ANALOG_SAW", "ANALOG_SQUARE");
    osc1Type.setValue(model.getOsc1Type());
    osc1Type.setOnAction(
        e -> {
          model.setOsc1Type(osc1Type.getValue());
          // In a full implementation, we'd update the ChucK engine here via BridgeContract
          // For now, our engine.ck uses MorphingWavetable (which defaults to Sine/Saw)
        });

    oscGrid.add(new Label("Oscillator 1:"), 0, 0);
    oscGrid.add(osc1Type, 1, 0);

    TitledPane oscPane = new TitledPane("Oscillators", oscGrid);
    oscPane.setCollapsible(false);

    // ── Filter (SVF) ──
    GridPane filterGrid = new GridPane();
    filterGrid.setHgap(10);
    filterGrid.setVgap(5);

    ComboBox<FilterMode> fMode = new ComboBox<>();
    fMode.getItems().addAll(FilterMode.values());
    fMode.setValue(model.getFilterMode());
    fMode.setOnAction(
        e -> {
          model.setFilterMode(fMode.getValue());
          bridge.setFilterMode(trackIndex, fMode.getValue().ordinal());
        });

    Slider cutoff =
        new Slider(
            0,
            1.0,
            vm.getGlobalObject(BridgeContract.G_FILTER) != null
                ? ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER))
                    .getFloat(trackIndex * 2)
                : 1.0);
    cutoff
        .valueProperty()
        .addListener(
            (obs, o, n) -> {
              bridge.setFilterFreq(trackIndex, n.doubleValue());
            });

    Slider res =
        new Slider(
            0,
            1.0,
            vm.getGlobalObject(BridgeContract.G_FILTER) != null
                ? ((org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER))
                    .getFloat(trackIndex * 2 + 1)
                : 0.0);
    res.valueProperty()
        .addListener(
            (obs, o, n) -> {
              bridge.setFilterRes(trackIndex, n.doubleValue());
            });

    filterGrid.add(new Label("Mode:"), 0, 0);
    filterGrid.add(fMode, 1, 0);
    filterGrid.add(new Label("Cutoff:"), 0, 1);
    filterGrid.add(cutoff, 1, 1);
    filterGrid.add(new Label("Resonance:"), 0, 2);
    filterGrid.add(res, 1, 2);

    TitledPane filterPane = new TitledPane("Filter (SVF/Ladder)", filterGrid);
    filterPane.setCollapsible(false);

    // ── Envelopes ──
    GridPane envGrid = new GridPane();
    envGrid.setHgap(10);
    envGrid.setVgap(5);

    // Simplification: Edit ENV 0 (Amp Envelope) for the MVP
    Slider attack = new Slider(0.001, 2.0, model.getEnv(0).attack());
    attack
        .valueProperty()
        .addListener(
            (obs, o, n) -> {
              bridge.setEnv(
                  trackIndex - 4,
                  n.doubleValue(),
                  model.getEnv(0).decay(),
                  model.getEnv(0).sustain(),
                  model
                      .getEnv(0)
                      .release()); // trackIndex - 4 because Synth tracks are 4-7 mapped to Env 0-3
              // in engine.ck MVP
            });

    envGrid.add(new Label("Attack (s):"), 0, 0);
    envGrid.add(attack, 1, 0);

    TitledPane envPane = new TitledPane("Envelopes (Amp)", envGrid);
    envPane.setCollapsible(false);

    root.getChildren().addAll(oscPane, filterPane, envPane);

    // Dark theme for dialog text
    root.lookupAll(".label")
        .forEach(n -> ((Label) n).setTextFill(javafx.scene.paint.Color.web("#e0e0e0")));

    Scene scene = new Scene(root, 300, 400);
    setScene(scene);
  }
}
