package org.chuck.deluge.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;
import org.chuck.deluge.ui.config.KitConfigDialog;
import org.chuck.deluge.ui.config.SynthConfigDialog;

/**
 * Represents a single horizontal row (Track) containing an audition pad, label, and 16 step cells.
 */
public class TrackRowPanel extends HBox {
  private final int rowIndex;
  private final BridgeContract bridge;

  private final Button auditionPad;
  private final Label trackLabel;
  private final Button settingsBtn;
  private final StepCellButton[] cells;
  private final java.util.function.Supplier<EditMode> modeSupplier;

  public TrackRowPanel(
      int rowIndex,
      String trackName,
      ChuckVM vm,
      BridgeContract bridge,
      java.util.function.Supplier<EditMode> modeSupplier) {
    this.rowIndex = rowIndex;
    this.bridge = bridge;
    this.modeSupplier = modeSupplier;
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

    // Toggle Mute on Click
    trackLabel.setOnMouseClicked(
        e -> {
          boolean wasMuted = bridge.getMute(rowIndex);
          bridge.setMute(rowIndex, !wasMuted);
          trackLabel.setTextFill(!wasMuted ? Color.web("#c62828") : Color.web("#cccccc"));
        });

    // Support track-wide parameter editing via vertical drag
    trackLabel.setOnMouseDragged(
        e -> {
          double delta = -e.getY() / 100.0;
          EditMode currentMode = modeSupplier.get();

          switch (currentMode) {
            case LEVEL:
              double level = bridge.getTrackLevel(rowIndex) + delta;
              bridge.setTrackLevel(rowIndex, level);
              break;
            case PAN:
              // For now, no global track pan, but we can implement it here.
              break;
            case FILTER:
              double freq = bridge.getTrackFilterFreq(rowIndex) + delta;
              bridge.setFilterFreq(rowIndex, freq);
              break;
            case RESONANCE:
              double res = bridge.getTrackFilterRes(rowIndex) + delta;
              bridge.setFilterRes(rowIndex, res);
              break;
            default:
              break;
          }
        });

    settingsBtn = new Button("⚙");
    settingsBtn.setStyle(
        "-fx-background-color: transparent; -fx-text-fill: #888888; -fx-font-size: 14px; -fx-padding: 0 5 0 0;");
    settingsBtn.setOnMouseEntered(
        e ->
            settingsBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #ffffff; -fx-font-size: 14px; -fx-padding: 0 5 0 0;"));
    settingsBtn.setOnMouseExited(
        e ->
            settingsBtn.setStyle(
                "-fx-background-color: transparent; -fx-text-fill: #888888; -fx-font-size: 14px; -fx-padding: 0 5 0 0;"));
    settingsBtn.setOnAction(
        e -> {
          if (rowIndex < 4) {
            KitConfigDialog dialog =
                new KitConfigDialog(new KitTrackModel(trackName), vm, bridge, rowIndex);
            dialog.show();
          } else {
            SynthConfigDialog dialog =
                new SynthConfigDialog(new SynthTrackModel(trackName), vm, bridge, rowIndex);
            dialog.show();
          }
        });

    getChildren().addAll(auditionPad, trackLabel, settingsBtn);

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

  public void setEditMode(EditMode mode) {
    for (StepCellButton cell : cells) {
      cell.setEditMode(mode);
    }
  }

  public void highlightStep(int col, boolean active) {
    if (col >= 0 && col < 16) {
      cells[col].setPlayheadActive(active);
    }
  }
}
