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

    filterGrid.add(new Label("LPF Mode:"), 0, 2);
    ComboBox<String> filterModeCombo = new ComboBox<>();
    filterModeCombo.getItems().addAll("12dB", "24dB", "ANALOG DRIVE");
    filterModeCombo.setValue("12dB");
    filterGrid.add(filterModeCombo, 1, 2);

    filterGrid.add(new Label("HPF Frequency:"), 0, 3);
    Slider hpfCutoff = new Slider(0, 127, 0);
    hpfCutoff.setPrefWidth(100);
    filterGrid.add(hpfCutoff, 1, 3);

    filterGrid.add(new Label("HPF Resonance:"), 0, 4);
    Slider hpfRes = new Slider(0, 127, 0);
    hpfRes.setPrefWidth(100);
    filterGrid.add(hpfRes, 1, 4);

    filterBox.getChildren().addAll(filterTitle, filterGrid);

    // 3. Master FX inner accordion
    javafx.scene.control.Accordion innerFxAccordion = new javafx.scene.control.Accordion();

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

    javafx.scene.control.TitledPane masterDelayPane = new javafx.scene.control.TitledPane("DELAYS & REVERB", fxGrid);
    javafx.scene.control.TitledPane distortPane = new javafx.scene.control.TitledPane("DISTORTIONS & DECIMATIONS", createDistortionsGrid());
    javafx.scene.control.TitledPane delayPatchPane = new javafx.scene.control.TitledPane("DELAY ROUTING", createDelayPatchGrid());
    javafx.scene.control.TitledPane sidechainPane = new javafx.scene.control.TitledPane("SIDECHAIN COMPRESSOR", createSidechainCompressorGrid());

    innerFxAccordion.getPanes().addAll(masterDelayPane, distortPane, delayPatchPane, sidechainPane);
    innerFxAccordion.setExpandedPane(masterDelayPane);

    // Envelopes Inner Accordion
    javafx.scene.control.Accordion innerEnvAccordion = new javafx.scene.control.Accordion();
    javafx.scene.control.TitledPane env1Pane = new javafx.scene.control.TitledPane("ENVELOPE 1", createEnvGrid("ENVELOPE 1"));
    javafx.scene.control.TitledPane env2Pane = new javafx.scene.control.TitledPane("ENVELOPE 2", createEnvGrid("ENVELOPE 2"));
    innerEnvAccordion.getPanes().addAll(env1Pane, env2Pane);
    innerEnvAccordion.setExpandedPane(env1Pane);

    // LFOs Inner Accordion
    javafx.scene.control.Accordion innerLfoAccordion = new javafx.scene.control.Accordion();
    javafx.scene.control.TitledPane lfo1Pane = new javafx.scene.control.TitledPane("LFO 1", createLfoGrid("LFO 1"));
    javafx.scene.control.TitledPane lfo2Pane = new javafx.scene.control.TitledPane("LFO 2", createLfoGrid("LFO 2"));
    innerLfoAccordion.getPanes().addAll(lfo1Pane, lfo2Pane);
    innerLfoAccordion.setExpandedPane(lfo1Pane);

    javafx.scene.control.Accordion accordion = new javafx.scene.control.Accordion();
    javafx.scene.control.TitledPane oscPane = new javafx.scene.control.TitledPane("OSCILLATORS", innerOscAccordion);
    javafx.scene.control.TitledPane filterPane = new javafx.scene.control.TitledPane("FILTERS", filterBox);
    javafx.scene.control.TitledPane envPane = new javafx.scene.control.TitledPane("ENVELOPES", innerEnvAccordion);
    javafx.scene.control.TitledPane lfoPane = new javafx.scene.control.TitledPane("LFOs", innerLfoAccordion);
    javafx.scene.control.TitledPane fxPane = new javafx.scene.control.TitledPane("MASTER FX", innerFxAccordion);

    accordion.getPanes().addAll(oscPane, filterPane, envPane, lfoPane, fxPane);
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

  private javafx.scene.layout.GridPane createDistortionsGrid() {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Bitcrush Bits:"), 0, 0);
    Slider bitSlider = new Slider(1, 16, 16);
    bitSlider.setPrefWidth(100);
    grid.add(bitSlider, 1, 0);

    grid.add(new Label("Decimations:"), 0, 1);
    Slider decimSlider = new Slider(0, 100, 0);
    decimSlider.setPrefWidth(100);
    grid.add(decimSlider, 1, 1);

    grid.add(new Label("Drive Saturation:"), 0, 2);
    Slider driveSlider = new Slider(0, 127, 0);
    driveSlider.setPrefWidth(100);
    grid.add(driveSlider, 1, 2);

    return grid;
  }

  private javafx.scene.layout.GridPane createDelayPatchGrid() {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Delay Type:"), 0, 0);
    ComboBox<String> typeCombo = new ComboBox<>();
    typeCombo.getItems().addAll("ANALOG", "DIGITAL");
    typeCombo.setValue("ANALOG");
    grid.add(typeCombo, 1, 0);

    grid.add(new Label("Pingpong:"), 0, 1);
    CheckBox pingpongCheck = new CheckBox();
    grid.add(pingpongCheck, 1, 1);

    grid.add(new Label("Delay Sync Rate:"), 0, 2);
    ComboBox<String> syncCombo = new ComboBox<>();
    syncCombo.getItems().addAll("Off", "1/4", "1/8", "1/16");
    syncCombo.setValue("Off");
    grid.add(syncCombo, 1, 2);

    return grid;
  }

  private javafx.scene.layout.GridPane createSidechainCompressorGrid() {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Threshold ducking:"), 0, 0);
    Slider thresSlider = new Slider(-60, 0, -12);
    thresSlider.setPrefWidth(100);
    grid.add(thresSlider, 1, 0);

    grid.add(new Label("Ducking Attack:"), 0, 1);
    Slider attackSlider = new Slider(0, 100, 10);
    attackSlider.setPrefWidth(100);
    grid.add(attackSlider, 1, 1);

    grid.add(new Label("Ducking Release:"), 0, 2);
    Slider releaseSlider = new Slider(0, 100, 40);
    releaseSlider.setPrefWidth(100);
    grid.add(releaseSlider, 1, 2);

    return grid;
  }

  private javafx.scene.layout.GridPane createEnvGrid(String title) {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Attack:"), 0, 0);
    Slider attackSlider = new Slider(0, 100, 10);
    attackSlider.setPrefWidth(100);
    grid.add(attackSlider, 1, 0);

    grid.add(new Label("Decay:"), 0, 1);
    Slider decaySlider = new Slider(0, 100, 20);
    decaySlider.setPrefWidth(100);
    grid.add(decaySlider, 1, 1);

    grid.add(new Label("Sustain:"), 0, 2);
    Slider sustainSlider = new Slider(0, 100, 80);
    sustainSlider.setPrefWidth(100);
    grid.add(sustainSlider, 1, 2);

    grid.add(new Label("Release:"), 0, 3);
    Slider releaseSlider = new Slider(0, 100, 30);
    releaseSlider.setPrefWidth(100);
    grid.add(releaseSlider, 1, 3);

    return grid;
  }

  private javafx.scene.layout.GridPane createLfoGrid(String title) {
    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
    grid.setHgap(10);
    grid.setVgap(5);

    grid.add(new Label("Shape:"), 0, 0);
    ComboBox<String> shapeCombo = new ComboBox<>();
    shapeCombo.getItems().addAll("SINE", "SAW", "SQUARE", "TRIANGLE");
    shapeCombo.setValue("SINE");
    grid.add(shapeCombo, 1, 0);

    grid.add(new Label("Rate:"), 0, 1);
    Slider rateSlider = new Slider(0, 100, 50);
    rateSlider.setPrefWidth(100);
    grid.add(rateSlider, 1, 1);

    grid.add(new Label("Sync:"), 0, 2);
    ComboBox<String> syncCombo = new ComboBox<>();
    syncCombo.getItems().addAll("Off", "1/4", "1/8", "1/16", "1/32");
    syncCombo.setValue("Off");
    grid.add(syncCombo, 1, 2);

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
