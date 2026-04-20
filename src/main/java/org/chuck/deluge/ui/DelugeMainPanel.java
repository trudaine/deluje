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
import org.chuck.deluge.midi.MidiInputRouter;
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
  private final MidiInputRouter midiRouter;

  public enum ViewMode {
    CLIP,
    SONG,
    ARRANGER
  }

  private ViewMode currentMode = ViewMode.CLIP;

  public DelugeMainPanel(ChuckVM vm, BridgeContract bridge, MidiInputRouter midiRouter) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiRouter = midiRouter;

    // Dark grey background
    setStyle("-fx-background-color: #1a1a1a;");
    setPadding(new Insets(10));

    // Initialize sub-panels
    transportPanel = new TransportPanel(vm, bridge);
    matrixPanel = new MatrixPanel(vm, bridge);
    songPanel = new SongModePanel(vm, bridge, 8, 8, matrixPanel::refreshAll); 
    arrangerPanel = new ArrangerPanel(vm, bridge);
    ribbonPanel = new ParameterRibbonPanel(vm, bridge, midiRouter);

    statusPanel = new StatusRibbonPanel(vm, bridge);

    // Link Ribbon to Matrix
    ribbonPanel.setOnModeChange(
        newMode -> {
          matrixPanel.setEditMode(newMode);
        });

    // Mode Toggle (CLIP vs SONG vs ARR)
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

    ToggleButton arrBtn = new ToggleButton("ARR");
    arrBtn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold;");
    arrBtn.setToggleGroup(modeGroup);

    modeGroup
        .selectedToggleProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal == null) {
                oldVal.setSelected(true); // Prevent unselecting all
                return;
              }
              if (newVal == clipBtn) {
                currentMode = ViewMode.CLIP;
                setCenter(matrixPanel);
                ribbonPanel.setVisible(true); // Parameter ribbon is for clip editing
                ribbonPanel.setManaged(true);
              } else if (newVal == songBtn) {
                currentMode = ViewMode.SONG;
                setCenter(songPanel);
                ribbonPanel.setVisible(false); // Hide ribbon in song mode
                ribbonPanel.setManaged(false);
              } else if (newVal == arrBtn) {
                currentMode = ViewMode.ARRANGER;
                setCenter(arrangerPanel);
                ribbonPanel.setVisible(false); // Hide ribbon in arranger mode
                ribbonPanel.setManaged(false);
              }
            });

    modeToggleBox.getChildren().addAll(clipBtn, songBtn, arrBtn);

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

    switch (currentMode) {
      case CLIP:
        matrixPanel.updateStep(step);
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
