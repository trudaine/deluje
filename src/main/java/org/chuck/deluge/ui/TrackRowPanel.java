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

    String trackColor = (rowIndex < 4) ? "#884444" : "#444488";

    // Audition Pad (Play the sample/synth manually)
    auditionPad = new Button();
    auditionPad.setPrefSize(40, 40);
    auditionPad.setStyle(
        String.format(
            "-fx-background-color: linear-gradient(to bottom, %s 0%%, #1a1a1a 100%%); -fx-background-radius: 4; -fx-border-color: #444444; -fx-border-width: 1; -fx-border-radius: 4;",
            trackColor));
    auditionPad.setOnMousePressed(
        e -> {
          auditionPad.setStyle(
              String.format(
                  "-fx-background-color: %s; -fx-background-radius: 4; -fx-border-color: white; -fx-border-width: 1; -fx-border-radius: 4;",
                  trackColor));
          // Manual trigger logic would go here
        });
    auditionPad.setOnMouseReleased(
        e -> {
          auditionPad.setStyle(
              String.format(
                  "-fx-background-color: linear-gradient(to bottom, %s 0%%, #1a1a1a 100%%); -fx-background-radius: 4; -fx-border-color: #444444; -fx-border-width: 1; -fx-border-radius: 4;",
                  trackColor));
        });

    trackLabel = new Label(trackName);
    trackLabel.setPrefWidth(80);
    trackLabel.setAlignment(Pos.CENTER_RIGHT);
    trackLabel.setStyle("-fx-font-family: 'Courier New'; -fx-font-weight: bold; -fx-font-size: 11px;");
    updateLabelStyle();

    // Toggle Mute on Click
    trackLabel.setOnMouseClicked(
        e -> {
          boolean wasMuted = bridge.getMute(rowIndex);
          bridge.setMute(rowIndex, !wasMuted);
          updateLabelStyle();
        });

    // Support track-wide parameter editing via vertical drag
    trackLabel.setOnMouseDragged(
        e -> {
          double delta = -e.getY() / 100.0;
          EditMode currentMode = modeSupplier.get();
          
          boolean liveRec = bridge.isRecording() && bridge.getVm().getGlobalInt(BridgeContract.G_PLAY) == 1;
          int targetStep = -1;
          if (liveRec) {
            targetStep = (int) bridge.getVm().getGlobalInt(BridgeContract.G_CURRENT_STEP);
          }

          switch (currentMode) {
            case LEVEL:
              if (liveRec && targetStep >= 0) {
                 double v = bridge.getVelocity(rowIndex, targetStep) + delta;
                 bridge.setVelocity(rowIndex, targetStep, v);
              } else {
                 double level = bridge.getTrackLevel(rowIndex) + delta;
                 bridge.setTrackLevel(rowIndex, level);
              }
              break;
            case FILTER:
              if (liveRec && targetStep >= 0) {
                 double f = bridge.getStepFilter(rowIndex, targetStep) + delta;
                 bridge.setStepFilter(rowIndex, targetStep, f);
              } else {
                 double freq = bridge.getTrackFilterFreq(rowIndex) + delta;
                 bridge.setFilterFreq(rowIndex, freq);
              }
              break;
            case RESONANCE:
              if (liveRec && targetStep >= 0) {
                 double r = bridge.getStepRes(rowIndex, targetStep) + delta;
                 bridge.setStepRes(rowIndex, targetStep, r);
              } else {
                 double res = bridge.getTrackFilterRes(rowIndex) + delta;
                 bridge.setFilterRes(rowIndex, res);
              }
              break;
            default:
              break;
          }
          if (liveRec) bridge.syncActiveClipToLibrary(rowIndex);
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

  public void refreshAll() {
    for (StepCellButton cell : cells) {
      cell.updateStyle();
    }
    updateLabelStyle();
  }

  private void updateLabelStyle() {
    boolean isMuted = bridge.getMute(rowIndex);
    trackLabel.setTextFill(
        isMuted ? javafx.scene.paint.Color.RED : javafx.scene.paint.Color.web("#cccccc"));
  }
}
