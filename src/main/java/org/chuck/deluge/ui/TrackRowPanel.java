package org.chuck.deluge.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import org.chuck.deluge.BridgeContract;

/**
 * Represents a single horizontal row (Track) containing an audition pad, label, and 16 step cells.
 */
public class TrackRowPanel extends HBox {
  private final int rowIndex;
  private final BridgeContract bridge;

  private final Button auditionPad;
  private final Label trackLabel;
  private final StepCellButton[] cells;

  public TrackRowPanel(int rowIndex, String trackName, BridgeContract bridge) {
    this.rowIndex = rowIndex;
    this.bridge = bridge;
    this.cells = new StepCellButton[16];

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(5);

    // Audition Pad (Play the sample/synth manually)
    auditionPad = new Button();
    auditionPad.setPrefSize(40, 40);
    auditionPad.setStyle("-fx-background-color: #444444; -fx-background-radius: 5;");
    auditionPad.setOnMousePressed(
        e -> {
          auditionPad.setStyle("-fx-background-color: #888888; -fx-background-radius: 5;");
          // Trigger the sound via bridge (todo: implement manual trigger in engine.ck)
        });
    auditionPad.setOnMouseReleased(
        e -> {
          auditionPad.setStyle("-fx-background-color: #444444; -fx-background-radius: 5;");
        });

    trackLabel = new Label(trackName);
    trackLabel.setPrefWidth(80);
    trackLabel.setTextFill(Color.web("#cccccc"));
    trackLabel.setAlignment(Pos.CENTER_RIGHT);

    getChildren().addAll(auditionPad, trackLabel);

    // 16 Step Cells
    for (int col = 0; col < 16; col++) {
      cells[col] = new StepCellButton(rowIndex, col, bridge);
      getChildren().add(cells[col]);

      // Visual spacing every 4 steps (1 beat)
      if ((col + 1) % 4 == 0 && col < 15) {
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        spacer.setPrefWidth(10);
        getChildren().add(spacer);
      }
    }
  }

  public void highlightStep(int col, boolean active) {
    if (col >= 0 && col < 16) {
      cells[col].setPlayheadActive(active);
    }
  }
}
