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
import org.chuck.deluge.ui.song.SongModePanel;

/**
 * The root container for the Deluge UI. Composes the Parameter Ribbon, Transport, Matrix, Song
 * Mode, and OLED panels.
 */
public class DelugeMainPanel extends BorderPane {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private TransportPanel transportPanel;
  private MatrixPanel matrixPanel;
  private SongModePanel songPanel;
  private ParameterRibbonPanel ribbonPanel;
  private StatusRibbonPanel statusPanel;

  private boolean inSongMode = false;

  public DelugeMainPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    // Dark grey background
    setStyle("-fx-background-color: #1a1a1a;");
    setPadding(new Insets(10));

    // Initialize sub-panels
    transportPanel = new TransportPanel(vm, bridge);
    matrixPanel = new MatrixPanel(vm, bridge);
    songPanel = new SongModePanel(vm, bridge, 8, 8); // 8 tracks, 8 columns (A-H)
    ribbonPanel = new ParameterRibbonPanel(vm, bridge);
    statusPanel = new StatusRibbonPanel(vm, bridge);

    // Mode Toggle (CLIP vs SONG)
    HBox modeToggleBox = new HBox(5);
    modeToggleBox.setAlignment(Pos.CENTER_LEFT);
    modeToggleBox.setPadding(new Insets(0, 0, 0, 10));

    ToggleGroup modeGroup = new ToggleGroup();
    ToggleButton clipBtn = new ToggleButton("CLIP");
    clipBtn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold;");
    clipBtn.setToggleGroup(modeGroup);
    clipBtn.setSelected(true);

    ToggleButton songBtn = new ToggleButton("SONG");
    songBtn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold;");
    songBtn.setToggleGroup(modeGroup);

    modeGroup
        .selectedToggleProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal == null) {
                oldVal.setSelected(true); // Prevent unselecting both
                return;
              }
              if (newVal == clipBtn) {
                inSongMode = false;
                setCenter(matrixPanel);
                ribbonPanel.setVisible(true); // Parameter ribbon is for clip editing
                ribbonPanel.setManaged(true);
              } else if (newVal == songBtn) {
                inSongMode = true;
                setCenter(songPanel);
                ribbonPanel.setVisible(false); // Hide ribbon in song mode to save space
                ribbonPanel.setManaged(false);
              }
            });

    modeToggleBox.getChildren().addAll(clipBtn, songBtn);

    // Top: Transport and Ribbon
    HBox transportWithMode = new HBox(20);
    transportWithMode.setAlignment(Pos.CENTER_LEFT);
    transportWithMode.getChildren().addAll(modeToggleBox, transportPanel);

    VBox topBox = new VBox(10);
    topBox.getChildren().addAll(transportWithMode, ribbonPanel);
    setTop(topBox);

    // Center: The Grid Matrix (Default to Clip mode)
    setCenter(matrixPanel);

    // Bottom: Status Bar
    setBottom(statusPanel);
  }

  /** Called every frame by the AnimationTimer in DelugeApp. */
  public void updateFromVM() {
    int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);

    if (inSongMode) {
      songPanel.update(step);
    } else {
      matrixPanel.updateStep(step);
    }

    statusPanel.update(step);
  }
}
