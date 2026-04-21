package org.chuck.deluge.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
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
    nameLabel.setStyle("-fx-text-fill: #00ffcc; -fx-font-family: 'Monospaced'; -fx-font-weight: bold; -fx-font-size: 12;");

    auditionBtn = new Button(">");
    auditionBtn.setMinWidth(25);
    auditionBtn.setPrefWidth(25);
    auditionBtn.setStyle("-fx-base: #333; -fx-text-fill: #888; -fx-padding: 2 5 2 5;");
    auditionBtn.setOnAction(e -> triggerAudition());

    getChildren().addAll(nameLabel, auditionBtn);

    cells = new StepCellButton[16];
    for (int i = 0; i < 16; i++) {
      cells[i] = new StepCellButton(rowId, i, bridge, editModeSupplier);
      getChildren().add(cells[i]);
    }
  }

  private String padName(String name) {
      if (name == null) name = "EMPTY";
      if (name.length() > 8) return name.substring(0, 8);
      return String.format("%-8s", name);
  }

  public void updateForKit(KitTrackModel.KitSound sound) {
    nameLabel.setText(padName(sound.getName()));
  }

  public void setEditMode(EditMode mode) {
    for (StepCellButton cell : cells) {
      cell.setEditMode(mode);
    }
  }

  public void refreshCells() {
      for (StepCellButton cell : cells) {
          cell.setSelected(bridge.getStep(rowId, cell.getStepId()));
          cell.updateStyle();
      }
  }

  public void highlightStep(int col, boolean active) {
    if (col >= 0 && col < 16) {
      cells[col].setPlayheadActive(active);
    }
  }

  private void triggerAudition() {
    // Manual trigger logic via Bridge
    // For now, we'll implement this by toggling a hidden bridge state or sending a MIDI message
  }
}
