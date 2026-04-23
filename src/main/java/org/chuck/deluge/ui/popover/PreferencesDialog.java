package org.chuck.deluge.ui.popover;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import org.chuck.deluge.project.PreferencesManager;

/** Dialog to configure mappings between UI controls and ChucK engine elements. */
public class PreferencesDialog extends Dialog<Void> {
  private final org.chuck.deluge.midi.MidiService midiService;

  public PreferencesDialog(org.chuck.deluge.midi.MidiService midiService) {
    this.midiService = midiService;
    setTitle("Preferences");
    setHeaderText("Application Preferences");

    getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);
    grid.setPadding(new Insets(20, 150, 10, 10));

    // Reverb Mapping
    grid.add(new Label("Reverb Model:"), 0, 0);
    ComboBox<String> reverbCombo = new ComboBox<>();
    reverbCombo.getItems().addAll("JCRev", "FreeVerb", "MVerb", "ProceduralReverb");

    String currentReverb = PreferencesManager.get("reverb.model", "JCRev");
    reverbCombo.setValue(currentReverb);

    grid.add(reverbCombo, 1, 0);

    // MIDI Input Mapping
    grid.add(new Label("MIDI Input:"), 0, 1);
    ComboBox<String> midiCombo = new ComboBox<>();
    midiCombo.getItems().add("None");
    midiCombo.getItems().addAll(org.chuck.midi.MidiIn.list());

    String currentMidi = PreferencesManager.get("midi.input", "None");
    midiCombo.setValue(currentMidi);

    grid.add(midiCombo, 1, 1);

    // Visualizer Toggle
    grid.add(new Label("Show Visualizers:"), 0, 2);
    javafx.scene.control.CheckBox visCheck = new javafx.scene.control.CheckBox();
    boolean currentVis = Boolean.parseBoolean(PreferencesManager.get("show.visualizers", "true"));
    visCheck.setSelected(currentVis);
    grid.add(visCheck, 1, 2);

    // Display Mappings
    grid.add(new Label("Active Mappings:"), 0, 3);
    javafx.scene.control.ListView<String> mappingList = new javafx.scene.control.ListView<>();
    mappingList.setPrefHeight(100);

    java.util.Map<String, Integer> mappings = midiService.getMappings();
    for (java.util.Map.Entry<String, Integer> entry : mappings.entrySet()) {
      mappingList.getItems().add(entry.getKey() + " -> CC " + entry.getValue());
    }

    grid.add(mappingList, 1, 3);

    // Debug Audio Toggle
    grid.add(new Label("Debug Audio:"), 0, 4);
    javafx.scene.control.CheckBox debugCheck = new javafx.scene.control.CheckBox();
    boolean currentDebug = Boolean.parseBoolean(PreferencesManager.get("debug.audio", "false"));
    debugCheck.setSelected(currentDebug);
    grid.add(debugCheck, 1, 4);

    // MIDI Grid Mode Toggle
    grid.add(new Label("MIDI Grid Mode:"), 0, 5);
    javafx.scene.control.CheckBox gridModeCheck = new javafx.scene.control.CheckBox();
    boolean currentGridMode =
        Boolean.parseBoolean(PreferencesManager.get("midi.grid.mode", "false"));
    gridModeCheck.setSelected(currentGridMode);
    grid.add(gridModeCheck, 1, 5);

    grid.add(new Label("Show Tooltips:"), 0, 6);
    javafx.scene.control.CheckBox tooltipCheck = new javafx.scene.control.CheckBox();
    boolean currentTooltip = Boolean.parseBoolean(PreferencesManager.get("show.tooltips", "true"));
    tooltipCheck.setSelected(currentTooltip);
    grid.add(new Label("Preset Linking:"), 0, 7);
    ComboBox<String> linkingCombo = new ComboBox<>();
    linkingCombo.getItems().addAll("EMBED", "LINK_LIVE");
    linkingCombo.setValue(PreferencesManager.get("preset.linking.policy", "EMBED"));
    grid.add(linkingCombo, 1, 7);

    getDialogPane().setContent(grid);

    setResultConverter(
        dialogButton -> {
          if (dialogButton == ButtonType.OK) {
            PreferencesManager.set("reverb.model", reverbCombo.getValue());
            PreferencesManager.set("midi.input", midiCombo.getValue());
            PreferencesManager.set("show.visualizers", String.valueOf(visCheck.isSelected()));
            PreferencesManager.set("debug.audio", String.valueOf(debugCheck.isSelected()));
            org.chuck.audio.util.DacChannel.DEBUG_AUDIO = debugCheck.isSelected();
            PreferencesManager.set("midi.grid.mode", String.valueOf(gridModeCheck.isSelected()));
            PreferencesManager.set("show.tooltips", String.valueOf(tooltipCheck.isSelected()));
            PreferencesManager.set("preset.linking.policy", linkingCombo.getValue());
          }
          return null;
        });

  }
}
