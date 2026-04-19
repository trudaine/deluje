package org.chuck.deluge.ui.song;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * The main container for Song Mode. Contains the A-Z SectionBar and a grid of ClipCells for
 * launching patterns.
 */
public class SongModePanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private final SectionBar sectionBar;
  private final ClipCell[][] clipGrid; // [tracks][slots]
  private final LaunchQuantController quantController;

  public SongModePanel(ChuckVM vm, BridgeContract bridge, int numTracks, int numSlots) {
    this.vm = vm;
    this.bridge = bridge;
    this.clipGrid = new ClipCell[numTracks][numSlots];

    setAlignment(Pos.TOP_LEFT);
    setSpacing(5);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #1a1a1a;");

    // 1. The Section Bar (A, B, C...)
    sectionBar = new SectionBar(numSlots);
    sectionBar.setOnSectionLaunched(this::armSection);
    getChildren().add(sectionBar);

    // 2. The Clip Grid
    GridPane grid = new GridPane();
    grid.setHgap(5);
    grid.setVgap(5);

    String[] trackNames = {
      "KICK", "SNARE", "HIHAT", "OPEN HAT", "SYNTH 1", "SYNTH 2", "SYNTH 3", "SYNTH 4"
    };

    for (int t = 0; t < numTracks; t++) {
      // Track Label
      Label label = new Label(trackNames[t]);
      label.setPrefWidth(80);
      label.setAlignment(Pos.CENTER_RIGHT);
      label.setTextFill(Color.web("#cccccc"));
      grid.add(label, 0, t);

      // Clip Cells for this track
      for (int s = 0; s < numSlots; s++) {
        ClipCell cell = new ClipCell(t, s);

        // Mock data: populate the first cell of the first few tracks
        if (s == 0 && t < 4) {
          cell.setFilled("PAT_A");
        }
        // Mock data: populate a section B
        if (s == 1 && (t == 0 || t == 1)) {
          cell.setFilled("PAT_B");
        }

        clipGrid[t][s] = cell;
        grid.add(cell, s + 1, t);
      }
    }

    getChildren().add(grid);

    // 3. Initialize the Quantization Controller
    quantController = new LaunchQuantController(vm, bridge, clipGrid, numTracks, numSlots);
  }

  /** Arms an entire column (Section) of clips to be launched. */
  private void armSection(int sectionIndex) {
    for (int t = 0; t < clipGrid.length; t++) {
      ClipCell cell = clipGrid[t][sectionIndex];
      if (cell.getCurrentState() == ClipCell.State.FILLED
          || cell.getCurrentState() == ClipCell.State.PLAYING) {
        cell.setQueued();
      }
    }

    // If sequencer is stopped, launch immediately
    if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0L) {
      quantController.forceLaunchQueued();
    }
  }

  /** Called every frame by the animation timer to handle launch quantization. */
  public void update(int currentStep) {
    quantController.update(currentStep);
  }
}
