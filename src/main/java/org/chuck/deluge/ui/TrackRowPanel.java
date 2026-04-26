package org.chuck.deluge.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;

/**
 * A single row in the Matrix sequencer grid. Represents one track (Synth) or one drum sound (Kit).
 */
public class TrackRowPanel extends HBox {
  private final int rowId;
  private final Label nameLabel;
  private final StepCellButton[] cells;
  private final Button auditionBtn;
  private int baseTrack = 0;
  private int stepOffset = 0;

  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final java.util.function.Supplier<EditMode> editModeSupplier;

  public TrackRowPanel(
      int rowId,
      String name,
      ChuckVM vm,
      BridgeContract bridge,
      java.util.function.Supplier<EditMode> editModeSupplier) {
    this.rowId = rowId;
    this.vm = vm;
    this.bridge = bridge;
    this.editModeSupplier = editModeSupplier;

    setAlignment(Pos.CENTER_LEFT);
    setSpacing(5);

    // Padding for names: 8 characters width, monospaced
    nameLabel = new Label(padName(name));
    nameLabel.setPrefWidth(75); // Roughly 8 chars at 12px mono
    nameLabel.setStyle(
        "-fx-text-fill: #00ffcc; -fx-font-family: 'Monospaced'; -fx-font-weight: bold; -fx-font-size: 12;");

    auditionBtn = new Button(">");
    auditionBtn.setMinWidth(25);
    auditionBtn.setPrefWidth(25);
    auditionBtn.setStyle("-fx-base: #333; -fx-text-fill: #888; -fx-padding: 2 5 2 5;");
    auditionBtn.setOnAction(e -> triggerAudition());

    getChildren().addAll(nameLabel, auditionBtn);

    cells = new StepCellButton[18];
    for (int i = 0; i < 16; i++) {
      cells[i] = new StepCellButton(rowId, i, vm, bridge, editModeSupplier);
      getChildren().add(cells[i]);
    }

    javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
    spacer.setPrefWidth(20);
    getChildren().add(spacer);

    cells[16] = new StepCellButton(rowId, 16, vm, bridge, editModeSupplier);
    cells[16].setText("MUTE");
    cells[16].setStyle("-fx-base: #333; -fx-text-fill: #888; -fx-font-size: 10px;");
    cells[16].addEventFilter(
        javafx.scene.input.MouseEvent.MOUSE_PRESSED,
        ev -> {
          if (ev.isShiftDown()) {
            for (int s = 0; s < 16; s++) {
              bridge.setStep(baseTrack + rowId, s, false);
            }
            refreshCells();
            ev.consume(); // prevent fire
          }
        });
    cells[16].setOnAction(
        e -> {
          boolean isMuted = bridge.getMute(baseTrack + rowId);
          bridge.setMute(baseTrack + rowId, !isMuted);
          cells[16].setStyle(
              "-fx-base: "
                  + (!isMuted ? "#ff3333" : "#333")
                  + "; -fx-text-fill: white; -fx-font-size: 10px;");
        });
    getChildren().add(cells[16]);

    cells[17] = new StepCellButton(rowId, 17, vm, bridge, editModeSupplier);
    cells[17].setText("SOLO");
    cells[17].setStyle("-fx-base: #333; -fx-text-fill: #888; -fx-font-size: 10px;");
    getChildren().add(cells[17]);
  }

  private String padName(String name) {
    if (name == null) name = "EMPTY";
    if (name.length() > 8) return name.substring(0, 8);
    return String.format("%-8s", name);
  }

  public void updateForKit(KitTrackModel.KitSound sound) {
    nameLabel.setText(padName(sound.getName()));
  }

  public void setNoteName(String name) {
    nameLabel.setText(padName(name));
  }

  public void setEditMode(EditMode mode) {
    for (StepCellButton cell : cells) {
      if (cell != null) {
        cell.setEditMode(mode);
      }
    }
  }

  public void setStepOffset(int offset) {
    this.stepOffset = offset;
    refreshCells();
  }

  public void refreshCells() {
    for (StepCellButton cell : cells) {
      if (cell != null) {
        cell.setSelected(bridge.getStep(baseTrack + rowId, stepOffset + cell.getStepId()));
        cell.updateStyle();
      }
    }
  }

  public void setBaseTrack(int baseTrack) {
    this.baseTrack = baseTrack;
    for (StepCellButton cell : cells) {
      if (cell != null) {
        cell.setBaseTrack(baseTrack);
      }
    }
  }

  public void setSynthMode(boolean isSynthMode) {
    for (StepCellButton cell : cells) {
      if (cell != null) {
        cell.setSynthMode(isSynthMode);
      }
    }
  }

  public void highlightStep(int col, boolean active) {
    int visibleCol = col - stepOffset;
    if (visibleCol >= 0 && visibleCol < 16) {
      cells[visibleCol].setPlayheadActive(active);
    }
  }

  private void triggerAudition() {
    // Manual trigger logic via Bridge
    // For now, we'll implement this by toggling a hidden bridge state or sending a MIDI message
  }
}
