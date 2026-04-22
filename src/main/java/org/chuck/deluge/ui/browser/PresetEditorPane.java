package org.chuck.deluge.ui.browser;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import java.io.File;
import java.io.FileWriter;

/** Graphical sound preset editor. */
public class PresetEditorPane extends VBox {

  private final Label titleLabel;

  private final ComboBox<String> typeCombo;
  private final Slider volSlider;
  private final Slider transSlider;
  private final CheckBox syncCheck;

  private final Slider lpfCutoffSlider;
  private final Slider lpfResSlider;

  private final Slider delaySlider;
  private final Slider reverbSlider;

  private File currentFile = null;

  public PresetEditorPane() {
    setSpacing(10);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #252525; -fx-border-color: #333;");

    titleLabel = new Label("NO PRESET SELECTED");
    titleLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-font-size: 12px;");
    getChildren().add(titleLabel);

    Button saveBtn = new Button("💾 SAVE XML");
    saveBtn.setStyle("-fx-background-color: #448844; -fx-text-fill: white; -fx-font-weight: bold;");
    saveBtn.setOnAction(e -> savePreset());
    getChildren().add(saveBtn);

    ScrollPane scroll = new ScrollPane();
    scroll.setFitToWidth(true);
    scroll.setStyle("-fx-background: #252525; -fx-border-color: transparent;");

    VBox content = new VBox(15);
    content.setPadding(new Insets(5));

    // 1. Oscillators
    VBox oscBox = new VBox(5);
    Label oscTitle = new Label("OSCILLATOR 1");
    oscTitle.setStyle("-fx-text-fill: #00ffcc; -fx-font-weight: bold; -fx-font-size: 12px;");
    
    GridPane oscGrid = new GridPane();
    oscGrid.setHgap(10);
    oscGrid.setVgap(5);

    oscGrid.add(new Label("Type:"), 0, 0);
    typeCombo = new ComboBox<>();
    typeCombo.getItems().addAll("SINE", "SAW", "SQUARE", "TRIANGLE", "SAMPLE");
    typeCombo.setValue("SINE");
    oscGrid.add(typeCombo, 1, 0);

    oscGrid.add(new Label("Volume:"), 0, 1);
    volSlider = new Slider(0, 127, 64);
    volSlider.setPrefWidth(100);
    volSlider.setShowTickLabels(true);
    oscGrid.add(volSlider, 1, 1);

    Button volModBtn = new Button("M");
    volModBtn.setPrefWidth(25);
    volModBtn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 10px;");
    volModBtn.setOnAction(e -> {
        org.chuck.deluge.ui.popover.ModulationPatchingDialog dialog = 
            new org.chuck.deluge.ui.popover.ModulationPatchingDialog("Oscillator 1 Volume");
        dialog.showAndWait();
    });
    oscGrid.add(volModBtn, 2, 1);

    oscGrid.add(new Label("Transpose:"), 0, 2);
    transSlider = new Slider(-24, 24, 0);
    transSlider.setPrefWidth(100);
    transSlider.setShowTickLabels(true);
    oscGrid.add(transSlider, 1, 2);

    oscGrid.add(new Label("Sync:"), 0, 3);
    syncCheck = new CheckBox();
    oscGrid.add(syncCheck, 1, 3);

    oscBox.getChildren().addAll(oscTitle, oscGrid);

    // 2. Filters
    VBox filterBox = new VBox(5);
    Label filterTitle = new Label("FILTERS");
    filterTitle.setStyle("-fx-text-fill: #00ffcc; -fx-font-weight: bold; -fx-font-size: 12px;");
    
    GridPane filterGrid = new GridPane();
    filterGrid.setHgap(10);
    filterGrid.setVgap(5);

    filterGrid.add(new Label("LPF Frequency:"), 0, 0);
    lpfCutoffSlider = new Slider(0, 127, 64);
    lpfCutoffSlider.setPrefWidth(100);
    filterGrid.add(lpfCutoffSlider, 1, 0);

    Button filterModBtn = new Button("M");
    filterModBtn.setPrefWidth(25);
    filterModBtn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 10px;");
    filterModBtn.setOnAction(e -> {
        org.chuck.deluge.ui.popover.ModulationPatchingDialog dialog = 
            new org.chuck.deluge.ui.popover.ModulationPatchingDialog("Filter Cutoff frequency");
        dialog.showAndWait();
    });
    filterGrid.add(filterModBtn, 2, 0);

    filterGrid.add(new Label("LPF Resonance:"), 0, 1);
    lpfResSlider = new Slider(0, 127, 64);
    lpfResSlider.setPrefWidth(100);
    filterGrid.add(lpfResSlider, 1, 1);

    filterBox.getChildren().addAll(filterTitle, filterGrid);

    // 3. FX
    VBox fxBox = new VBox(5);
    Label fxTitle = new Label("MASTER FX");
    fxTitle.setStyle("-fx-text-fill: #00ffcc; -fx-font-weight: bold; -fx-font-size: 12px;");
    
    GridPane fxGrid = new GridPane();
    fxGrid.setHgap(10);
    fxGrid.setVgap(5);

    fxGrid.add(new Label("Delay Amount:"), 0, 0);
    delaySlider = new Slider(0, 127, 32);
    delaySlider.setPrefWidth(100);
    fxGrid.add(delaySlider, 1, 0);

    fxGrid.add(new Label("Reverb Amount:"), 0, 1);
    reverbSlider = new Slider(0, 127, 32);
    reverbSlider.setPrefWidth(100);
    fxGrid.add(reverbSlider, 1, 1);

    fxBox.getChildren().addAll(fxTitle, fxGrid);

    content.getChildren().addAll(oscBox, filterBox, fxBox);
    scroll.setContent(content);
    getChildren().add(scroll);
  }

  public void loadPreset(File file, String name) {
    this.currentFile = file;
    this.titleLabel.setText("EDITING: " + name.toUpperCase());
  }

  private void savePreset() {
    if (currentFile == null) return;
    
    File outputDir = new File(currentFile.getParent(), "EDITS");
    outputDir.mkdirs();
    File outFile = new File(outputDir, "EDIT_" + currentFile.getName());

    try (FileWriter writer = new FileWriter(outFile)) {
      StringBuilder sb = new StringBuilder();
      sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      sb.append("<preset>\n");
      sb.append("  <osc1>\n");
      sb.append("    <type>").append(typeCombo.getValue()).append("</type>\n");
      sb.append("    <volume>").append((int) volSlider.getValue()).append("</volume>\n");
      sb.append("    <transpose>").append((int) transSlider.getValue()).append("</transpose>\n");
      sb.append("    <sync>").append(syncCheck.isSelected() ? 1 : 0).append("</sync>\n");
      sb.append("  </osc1>\n");
      sb.append("  <lpf>\n");
      sb.append("    <frequency>").append((int) lpfCutoffSlider.getValue()).append("</frequency>\n");
      sb.append("    <resonance>").append((int) lpfResSlider.getValue()).append("</resonance>\n");
      sb.append("  </lpf>\n");
      sb.append("  <fx>\n");
      sb.append("    <delay>").append((int) delaySlider.getValue()).append("</delay>\n");
      sb.append("    <reverb>").append((int) reverbSlider.getValue()).append("</reverb>\n");
      sb.append("  </fx>\n");
      sb.append("</preset>\n");

      writer.write(sb.toString());
      System.out.println("Preset saved to: " + outFile.getAbsolutePath());
    } catch (Exception e) {
      System.err.println("Failed to save preset XML: " + e.getMessage());
    }
  }
}
