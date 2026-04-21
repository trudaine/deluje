package org.chuck.deluge.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.arranger.ArrangerPanel;
import org.chuck.deluge.ui.song.SongModePanel;

/**
 * The root container for the Deluge UI. Composes the Parameter Ribbon, Transport, Matrix, Song
 * Mode, Arranger Mode, and OLED panels.
 */
public class DelugeMainPanel extends BorderPane {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private TransportPanel transportPanel;
  private MatrixPanel matrixPanel;
  private SongModePanel songPanel;
  private ArrangerPanel arrangerPanel;
  private ParameterRibbonPanel ribbonPanel;
  private StatusRibbonPanel statusPanel;

  // New persistent panels
  private ProjectSidebarPanel sidebarPanel;
  private VelocityLanePanel velocityPanel;
  private DelugeKeyboardPanel keyboardPanel;

  public enum ViewMode {
    CLIP,
    SONG,
    ARRANGER
  }

  private ViewMode currentMode = ViewMode.CLIP;

  public DelugeMainPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    // Dark grey background
    setStyle("-fx-background-color: #1a1a1a;");
    setPadding(new Insets(10));

    // Initialize sub-panels
    transportPanel = new TransportPanel(vm, bridge);
    matrixPanel = new MatrixPanel(vm, bridge);
    songPanel = new SongModePanel(vm, bridge, 8, 8);
    arrangerPanel = new ArrangerPanel(vm, bridge);
    ribbonPanel = new ParameterRibbonPanel(vm, bridge);
    statusPanel = new StatusRibbonPanel(vm, bridge);

    sidebarPanel = new ProjectSidebarPanel(vm, bridge);
    sidebarPanel.setOnKitRequest(name -> {
        // Dummy loader for built-in kits
        org.chuck.deluge.model.KitTrackModel kit = new org.chuck.deluge.model.KitTrackModel(name);
        kit.addSound(new org.chuck.deluge.model.KitTrackModel.KitSound("BD 909", "examples/data/kick.wav"));
        kit.addSound(new org.chuck.deluge.model.KitTrackModel.KitSound("SD 909", "examples/data/snare.wav"));
        kit.addSound(new org.chuck.deluge.model.KitTrackModel.KitSound("CH 909", "examples/data/hihat.wav"));
        kit.addSound(new org.chuck.deluge.model.KitTrackModel.KitSound("OH 909", "examples/data/hihat-open.wav"));
        matrixPanel.applyKit(kit);
    });

    velocityPanel = new VelocityLanePanel(vm, bridge);
    keyboardPanel = new DelugeKeyboardPanel();

    // Wire up events
    transportPanel.setOnKitLoaded(matrixPanel::applyKit);
    matrixPanel.setOnTrackSelected(velocityPanel::setSelectedTrack);

    // Link Ribbon to Matrix
    ribbonPanel.setOnModeChange(matrixPanel::setEditMode);

    // Layout
    setLeft(sidebarPanel);

    // Mode Toggle
    HBox modeToggleBox = new HBox(5);
    modeToggleBox.setAlignment(Pos.CENTER_LEFT);
    modeToggleBox.setPadding(new Insets(0, 0, 0, 10));

    ToggleGroup modeGroup = new ToggleGroup();
    ToggleButton clipBtn = createModeBtn("CLIP", modeGroup);
    ToggleButton songBtn = createModeBtn("SONG", modeGroup);
    ToggleButton arrBtn = createModeBtn("ARR", modeGroup);
    clipBtn.setSelected(true);

    modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
      if (newVal == null) { oldVal.setSelected(true); return; }
      if (newVal == clipBtn) switchView(ViewMode.CLIP);
      else if (newVal == songBtn) switchView(ViewMode.SONG);
      else if (newVal == arrBtn) switchView(ViewMode.ARRANGER);
    });

    modeToggleBox.getChildren().addAll(clipBtn, songBtn, arrBtn);

    HBox transportWithMode = new HBox(20);
    transportWithMode.setAlignment(Pos.CENTER_LEFT);
    transportWithMode.getChildren().addAll(modeToggleBox, transportPanel);

    VBox topBox = new VBox(10);
    topBox.getChildren().addAll(transportWithMode, ribbonPanel);
    setTop(topBox);

    setCenter(matrixPanel);

    // Bottom Area: Velocity + Keyboard + Status
    VBox bottomBox = new VBox(5);
    bottomBox.getChildren().addAll(velocityPanel, keyboardPanel, statusPanel);
    setBottom(bottomBox);
  }

  private ToggleButton createModeBtn(String text, ToggleGroup group) {
    ToggleButton btn = new ToggleButton(text);
    btn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold;");
    btn.setToggleGroup(group);
    return btn;
  }

  private void switchView(ViewMode mode) {
    currentMode = mode;
    switch (mode) {
      case CLIP:
        setCenter(matrixPanel);
        ribbonPanel.setVisible(true);
        ribbonPanel.setManaged(true);
        break;
      case SONG:
        setCenter(songPanel);
        ribbonPanel.setVisible(false);
        ribbonPanel.setManaged(false);
        break;
      case ARRANGER:
        setCenter(arrangerPanel);
        ribbonPanel.setVisible(false);
        ribbonPanel.setManaged(false);
        break;
    }
  }

  public void updateFromVM() {
    int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

    switch (currentMode) {
      case CLIP:
        matrixPanel.updateStep(step);
        velocityPanel.draw(); // Refresh velocity lane
        break;
      case SONG:
        songPanel.update(step);
        break;
      case ARRANGER:
        arrangerPanel.update(step);
        break;
    }

    statusPanel.update(step);
  }
}
