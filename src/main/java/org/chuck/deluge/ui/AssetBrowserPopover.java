package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.AssetLibrary;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * An OLED-style searchable browser for Synths and Kits.
 */
public class AssetBrowserPopover extends Popup {

  public AssetBrowserPopover(BridgeContract bridge, int activeTrack, boolean kitsMode) {
    setAutoHide(true);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));
    root.setStyle("-fx-background-color: #1a1a1a; -fx-border-color: #00ff41; -fx-border-width: 2; -fx-border-radius: 5;");
    root.setPrefWidth(250);

    Label title = new Label(kitsMode ? "LOAD KIT" : "LOAD SYNTH");
    title.setStyle("-fx-font-family: 'Courier New'; -fx-font-weight: bold; -fx-text-fill: #00ff41;");
    title.setAlignment(Pos.CENTER);
    title.setMaxWidth(Double.MAX_VALUE);

    TextField search = new TextField();
    search.setPromptText("SEARCH...");
    search.setStyle("-fx-background-color: #333333; -fx-text-fill: white; -fx-font-family: 'Courier New';");

    ListView<AssetLibrary.AssetEntry> list = new ListView<>();
    list.setStyle("-fx-base: #1a1a1a; -fx-control-inner-background: #1a1a1a; -fx-font-family: 'Courier New'; -fx-font-size: 11px;");
    list.setPrefHeight(300);

    AssetLibrary lib = new AssetLibrary();
    java.util.List<AssetLibrary.AssetEntry> items = kitsMode ? lib.getKits() : lib.getSynths();
    list.getItems().addAll(items);

    // Apply selection logic
    list.getSelectionModel().selectedItemProperty().addListener((obs, old, entry) -> {
      if (entry != null) {
        try {
          if (entry.isFactory()) {
            java.io.InputStream is = getClass().getResourceAsStream(entry.path());
            if (kitsMode) {
               org.chuck.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(is, entry.name());
               // Implementation for loading kit path would go here
               System.out.println("Loaded Factory Kit: " + kit.getName() + " -> " + kit.getSamplePath());
            } else {
               org.chuck.deluge.model.SynthTrackModel model = DelugeXmlParser.parseSynth(is, entry.name());
               bridge.loadSynthPreset(activeTrack, model);
            }
          } else {
            java.io.File f = new java.io.File(entry.path());
            if (kitsMode) {
               org.chuck.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(f);
               System.out.println("Loaded User Kit: " + kit.getName());
            } else {
               org.chuck.deluge.model.SynthTrackModel model = DelugeXmlParser.parseSynth(f);
               bridge.loadSynthPreset(activeTrack, model);
            }
          }
          hide();
        } catch (Exception ex) {
          System.err.println("Load error: " + ex.getMessage());
        }
      }
    });

    // Search filter logic
    search.textProperty().addListener((obs, old, val) -> {
       list.getItems().clear();
       for (AssetLibrary.AssetEntry e : items) {
          if (e.name().toUpperCase().contains(val.toUpperCase())) {
             list.getItems().add(e);
          }
       }
    });

    root.getChildren().addAll(title, search, list);
    getContent().add(root);
  }
}
