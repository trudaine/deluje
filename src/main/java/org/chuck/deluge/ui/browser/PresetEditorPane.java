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



  private final Slider lpfCutoffSlider;
  private final Slider lpfResSlider;

  private final Slider delaySlider;
  private final Slider reverbSlider;

  private File currentFile = null;

  private Slider osc1Vol, osc2Vol, mod1Amount, mod2Amount;
  private Slider lpfCutoff, lpfRes;

  public interface ParameterChangeCallback {
    void onParameterChange(String param, float value);
  }

  private ParameterChangeCallback onParameterChange;

  public void setOnParameterChange(ParameterChangeCallback callback) {
    this.onParameterChange = callback;
  }

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

    // 1. Oscillators inner sub-accordion
    javafx.scene.control.Accordion innerOscAccordion = new javafx.scene.control.Accordion();

    javafx.scene.control.TitledPane osc1Pane = new javafx.scene.control.TitledPane("OSCILLATOR 1", createOscGrid("OSCILLATOR 1", true));
    javafx.scene.control.TitledPane osc2Pane = new javafx.scene.control.TitledPane("OSCILLATOR 2", createOscGrid("OSCILLATOR 2", false));
    javafx.scene.control.TitledPane mod1Pane = new javafx.scene.control.TitledPane("MODULATOR 1", createModulatorGrid("MODULATOR 1", false));
    javafx.scene.control.TitledPane mod2Pane = new javafx.scene.control.TitledPane("MODULATOR 2", createModulatorGrid("MODULATOR 2", true));

    innerOscAccordion.getPanes().addAll(osc1Pane, osc2Pane, mod1Pane, mod2Pane);
    innerOscAccordion.setExpandedPane(osc1Pane);

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
    lpfCutoffSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
      if (onParameterChange != null) {
        onParameterChange.onParameterChange("FILTER", newVal.floatValue() / 127.0f);
      }
    });
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

    javafx.scene.control.Accordion accordion = new javafx.scene.control.Accordion();
    javafx.scene.control.TitledPane oscPane = new javafx.scene.control.TitledPane("OSCILLATORS", innerOscAccordion);
    javafx.scene.control.TitledPane filterPane = new javafx.scene.control.TitledPane("FILTERS", filterBox);
    javafx.scene.control.TitledPane fxPane = new javafx.scene.control.TitledPane("MASTER FX", fxBox);

    accordion.getPanes().addAll(oscPane, filterPane, fxPane);
    content.getChildren().add(accordion);
    scroll.setContent(content);
    getChildren().add(scroll);
  }

  private javafx.scene.layout.GridPane createOscGrid(String title, boolean isOsc1) {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Type:"), 0, 0);
    ComboBox<String> typeCombo = new ComboBox<>();
    typeCombo.getItems().addAll("SINE", "SAW", "SQUARE", "TRIANGLE", "SAMPLE");
    typeCombo.setValue("SINE");
    grid.add(typeCombo, 1, 0);

    grid.add(new Label("Volume:"), 0, 1);
    Slider volSlider = new Slider(0, 127, 64);
    volSlider.setPrefWidth(100);
    if (isOsc1) osc1Vol = volSlider;
    else osc2Vol = volSlider;
    grid.add(volSlider, 1, 1);

    grid.add(new Label("Transpose:"), 0, 2);
    Slider transSlider = new Slider(-24, 24, 0);
    transSlider.setPrefWidth(100);
    grid.add(transSlider, 1, 2);

    grid.add(new Label("Pulse Width:"), 0, 3);
    Slider pulseSlider = new Slider(0, 100, 50);
    pulseSlider.setPrefWidth(100);
    grid.add(pulseSlider, 1, 3);

    grid.add(new Label("Retrig Phase:"), 0, 4);
    Slider retrigSlider = new Slider(0, 360, 0);
    retrigSlider.setPrefWidth(100);
    grid.add(retrigSlider, 1, 4);

    return grid;
  }

  private javafx.scene.layout.GridPane createModulatorGrid(String title, boolean hasDest) {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Transpose:"), 0, 0);
    Slider transSlider = new Slider(-24, 24, 0);
    transSlider.setPrefWidth(100);
    grid.add(transSlider, 1, 0);

    grid.add(new Label("Amount:"), 0, 1);
    Slider amountSlider = new Slider(0, 127, 0);
    amountSlider.setPrefWidth(100);
    if (hasDest) mod2Amount = amountSlider;
    else mod1Amount = amountSlider;
    grid.add(amountSlider, 1, 1);

    grid.add(new Label("Feedback:"), 0, 2);
    Slider feedSlider = new Slider(0, 127, 0);
    feedSlider.setPrefWidth(100);
    grid.add(feedSlider, 1, 2);

    grid.add(new Label("Retrig Phase:"), 0, 3);
    Slider retrigSlider = new Slider(0, 360, 0);
    retrigSlider.setPrefWidth(100);
    grid.add(retrigSlider, 1, 3);

    if (hasDest) {
      grid.add(new Label("Destination:"), 0, 4);
      ComboBox<String> destCombo = new ComboBox<>();
      destCombo.getItems().addAll("CARS", "MOD1");
      destCombo.setValue("CARS");
      grid.add(destCombo, 1, 4);
    }

    return grid;
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
      sb.append("    <type>").append("SINE").append("</type>\n");
      sb.append("    <volume>").append(64).append("</volume>\n");
      sb.append("    <transpose>").append(0).append("</transpose>\n");
      sb.append("    <sync>").append(0).append("</sync>\n");
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
