package org.chuck.deluge.ui;

import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.popover.NoteEntryPopover;
import org.chuck.deluge.ui.popover.StepEditorPopover;

/** Represents a single interactive cell in the 16-step sequence. */
public class StepCellButton extends ToggleButton {
  private final int row;
  private final int col;
  private final BridgeContract bridge;

  private boolean hasPlayhead = false;
  private final String colorHex; // Default track color

  public StepCellButton(int row, int col, BridgeContract bridge) {
    this.row = row;
    this.col = col;
    this.bridge = bridge;

    setPrefSize(40, 40);

    // Give different tracks different RGB colors to mimic Deluge
    String[] colors = {
      "#FF5555", "#FFaa55", "#FFFF55", "#aaFF55", "#55FF55", "#55FFaa", "#55FFFF", "#55aaFF"
    };
    this.colorHex = colors[row % colors.length];

    // Initial state
    setSelected(false);
    updateStyle();

    // Toggle logic (Left Click)
    setOnAction(
        e -> {
          bridge.setStep(row, col, isSelected());
          updateStyle();
        });

    // Right-Click Context Actions
    addEventHandler(MouseEvent.MOUSE_PRESSED, this::handleMousePressed);
  }

  private void handleMousePressed(MouseEvent event) {
    if (event.getButton() == MouseButton.SECONDARY) {
      if (event.isShiftDown() && row >= 4) {
        // Shift + Right Click: Note Entry (Synth only)
        NoteEntryPopover pop = new NoteEntryPopover(bridge, row, col);
        pop.show(this, event.getScreenX(), event.getScreenY());
      } else {
        // Normal Right Click: Step Editor (Velocity/Gate/Prob)
        StepEditorPopover pop = new StepEditorPopover(bridge, row, col);
        pop.show(this, event.getScreenX(), event.getScreenY());
      }
      event.consume();
    }
  }

  public void setPlayheadActive(boolean active) {
    this.hasPlayhead = active;
    updateStyle();
  }

  private void updateStyle() {
    String baseColor = "#333333"; // off

    if (isSelected()) {
      baseColor = colorHex; // on
    }

    if (hasPlayhead) {
      // White overlay for playhead
      setStyle(
          String.format(
              "-fx-background-color: white; -fx-border-color: %s; -fx-border-width: 2; -fx-background-radius: 5; -fx-border-radius: 5;",
              baseColor));
    } else {
      setStyle(String.format("-fx-background-color: %s; -fx-background-radius: 5;", baseColor));
    }
  }
}
