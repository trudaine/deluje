package org.chuck.deluge.ui.config;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.FilterMode;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * Advanced Sound Editor dialog with OLED styling and Arpeggiator controls.
 */
public class SynthConfigDialog extends Stage {

  public SynthConfigDialog(
      SynthTrackModel model, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    setTitle("SOUND EDITOR: " + model.getName());
    initStyle(StageStyle.UTILITY);
    initModality(Modality.NONE);

    VBox root = new VBox(15);
    root.setPadding(new Insets(15));
    root.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #3d3d3d; -fx-border-width: 2;");

    // ── Global OLED Style ──
    String labelStyle = "-fx-font-family: 'Courier New'; -fx-font-weight: bold; -fx-text-fill: #00ff41;";
    String paneStyle = "-fx-font-family: 'Courier New'; -fx-font-weight: bold;";

    // ── Arpeggiator ──
    GridPane arpGrid = new GridPane();
    arpGrid.setHgap(10);
    arpGrid.setVgap(10);
    arpGrid.setPadding(new Insets(10));

    CheckBox arpOn = new CheckBox("ARP ON");
    arpOn.setSelected(bridge.getArpOn(trackIndex));
    arpOn.setStyle(labelStyle);
    arpOn.setOnAction(e -> bridge.setArpOn(trackIndex, arpOn.isSelected()));

    Slider rateSlider = new Slider(0.25, 4.0, bridge.getArpRate(trackIndex));
    rateSlider.valueProperty().addListener((obs, o, n) -> bridge.setArpRate(trackIndex, n.doubleValue()));
    
    ComboBox<Integer> octCombo = new ComboBox<>();
    octCombo.getItems().addAll(1, 2, 3, 4);
    octCombo.setValue(bridge.getArpOctave(trackIndex));
    octCombo.setStyle("-fx-font-family: 'Courier New'; -fx-base: #333333;");
    octCombo.setOnAction(e -> bridge.setArpOctave(trackIndex, octCombo.getValue()));

    arpGrid.add(arpOn, 0, 0, 2, 1);
    arpGrid.add(new Label("RATE"), 0, 1);
    arpGrid.add(rateSlider, 1, 1);
    arpGrid.add(new Label("OCTAVES"), 0, 2);
    arpGrid.add(octCombo, 1, 2);

    TitledPane arpPane = new TitledPane("ARPEGGIATOR", arpGrid);
    arpPane.setCollapsible(false);
    arpPane.setStyle(paneStyle);

    // ── Filter (SVF) ──
    GridPane filterGrid = new GridPane();
    filterGrid.setHgap(10);
    filterGrid.setVgap(10);
    filterGrid.setPadding(new Insets(10));

    ComboBox<FilterMode> fMode = new ComboBox<>();
    fMode.getItems().addAll(FilterMode.values());
    fMode.setValue(model.getFilterMode());
    fMode.setStyle("-fx-font-family: 'Courier New'; -fx-base: #333333;");
    fMode.setOnAction(e -> bridge.setFilterMode(trackIndex, fMode.getValue().ordinal()));

    Slider cutoff = new Slider(0, 1.0, bridge.getTrackFilterFreq(trackIndex));
    cutoff.valueProperty().addListener((obs, o, n) -> bridge.setFilterFreq(trackIndex, n.doubleValue()));

    Slider res = new Slider(0, 1.0, bridge.getTrackFilterRes(trackIndex));
    res.valueProperty().addListener((obs, o, n) -> bridge.setFilterRes(trackIndex, n.doubleValue()));

    filterGrid.add(new Label("MODE"), 0, 0);
    filterGrid.add(fMode, 1, 0);
    filterGrid.add(new Label("CUTOFF"), 0, 1);
    filterGrid.add(cutoff, 1, 1);
    filterGrid.add(new Label("RES (Q)"), 0, 2);
    filterGrid.add(res, 1, 2);

    TitledPane filterPane = new TitledPane("FILTER", filterGrid);
    filterPane.setCollapsible(false);
    filterPane.setStyle(paneStyle);

    // ── FM & Oscillators ──
    GridPane fmGrid = new GridPane();
    fmGrid.setHgap(10);
    fmGrid.setVgap(10);
    fmGrid.setPadding(new Insets(10));

    Slider ratio = new Slider(0.25, 4.0, vm.getGlobalFloat(BridgeContract.G_FM_RATIO));
    ratio.valueProperty().addListener((obs, o, n) -> vm.setGlobalFloat(BridgeContract.G_FM_RATIO, n.doubleValue()));

    Slider amount = new Slider(0, 1.0, vm.getGlobalFloat(BridgeContract.G_FM_AMOUNT));
    amount.valueProperty().addListener((obs, o, n) -> vm.setGlobalFloat(BridgeContract.G_FM_AMOUNT, n.doubleValue()));

    fmGrid.add(new Label("FM RATIO"), 0, 0);
    fmGrid.add(ratio, 1, 0);
    fmGrid.add(new Label("FM AMOUNT"), 0, 1);
    fmGrid.add(amount, 1, 1);

    TitledPane fmPane = new TitledPane("FM SYNTHESIS", fmGrid);
    fmPane.setCollapsible(false);
    fmPane.setStyle(paneStyle);

    root.getChildren().addAll(arpPane, filterPane, fmPane);

    // Final Style Polish
    root.lookupAll(".label").forEach(n -> n.setStyle(labelStyle));

    Scene scene = new Scene(root, 350, 500);
    setScene(scene);
  }
}
