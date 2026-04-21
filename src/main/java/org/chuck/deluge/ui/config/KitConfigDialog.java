package org.chuck.deluge.ui.config;

import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.KitTrackModel;

public class KitConfigDialog extends Stage {

  public KitConfigDialog(KitTrackModel.KitSound sound, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    setTitle("Kit Sound Config: " + sound.getName());
    initStyle(StageStyle.UTILITY);
    initModality(Modality.NONE);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));
    root.setStyle("-fx-background-color: #2b2b2b;");

    // ── Sample ──
    GridPane sampleGrid = new GridPane();
    sampleGrid.setHgap(10);
    sampleGrid.setVgap(5);

    TextField pathField = new TextField(sound.getSamplePath());
    pathField.setEditable(false);
    pathField.setPrefWidth(150);

    Button browseBtn = new Button("Browser...");
    browseBtn.setOnAction(
        e -> {
          org.chuck.deluge.ui.browser.SampleBrowserPanel browser =
              new org.chuck.deluge.ui.browser.SampleBrowserPanel(sound);
          browser.setOnHidden(
              ev -> {
                pathField.setText(sound.getSamplePath());
                // TODO: Hot-swap sample in engine.ck via bridge or event
              });
          browser.show();
        });

    sampleGrid.add(new Label("Sample:"), 0, 0);
    sampleGrid.add(pathField, 1, 0);
    sampleGrid.add(browseBtn, 2, 0);

    TitledPane samplePane = new TitledPane("Sample Source", sampleGrid);
    samplePane.setCollapsible(false);

    // ── Pitch & Pan ──
    GridPane pitchGrid = new GridPane();
    pitchGrid.setHgap(10);
    pitchGrid.setVgap(5);

    Slider pitch = new Slider(-24, 24, sound.getPitchSemitones());
    pitch
        .valueProperty()
        .addListener(
            (obs, o, n) -> {
              sound.setPitchSemitones(n.floatValue());
            });

    pitchGrid.add(new Label("Pitch (ST):"), 0, 0);
    pitchGrid.add(pitch, 1, 0);

    TitledPane pitchPane = new TitledPane("Pitch & Modulation", pitchGrid);
    pitchPane.setCollapsible(false);

    root.getChildren().addAll(samplePane, pitchPane);

    // Dark theme for dialog text
    root.lookupAll(".label")
        .forEach(n -> ((Label) n).setTextFill(javafx.scene.paint.Color.web("#e0e0e0")));

    Scene scene = new Scene(root, 320, 250);
    setScene(scene);
  }
}
