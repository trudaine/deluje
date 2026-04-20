package org.chuck.deluge.ui;

import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;
import org.chuck.deluge.ui.popover.NoteEntryPopover;
import org.chuck.deluge.ui.popover.StepEditorPopover;

/** Represents a single interactive cell in the 16-step sequence. */
public class StepCellButton extends ToggleButton {
  private final int row;
  private final int col;
  private final BridgeContract bridge;

  private boolean hasPlayhead = false;
  private final String colorHex;
  private EditMode editMode = EditMode.VELOCITY;

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

    // Support vertical drag to change parameter value
    addEventHandler(MouseEvent.MOUSE_DRAGGED, this::handleMouseDragged);
  }

  public void setEditMode(EditMode mode) {
    this.editMode = mode;
    updateStyle();
  }

  private void handleMouseDragged(MouseEvent event) {
    if (event.isPrimaryButtonDown() && !isSelected()) return;
    if (!event.isPrimaryButtonDown()) return;

    // Calculate delta and update based on current edit mode
    // This is a simplified drag implementation
    double delta = -event.getY() / 100.0; // Invert and scale

    switch (editMode) {
      case VELOCITY:
        double v = bridge.getVelocity(row, col) + delta;
        bridge.setVelocity(row, col, v);
        break;
      case GATE:
        double g = bridge.getGate(row, col) + delta;
        bridge.setGate(row, col, g);
        break;
      case PROBABILITY:
        double p = bridge.getStepProbability(row, col) + delta;
        bridge.setStepProbability(row, col, p);
        break;
      case PITCH:
        int pitch = bridge.getPitch(row, col) + (int) (delta * 24); // Scale to semitones
        bridge.setPitch(row, col, pitch);
        break;
      default:
        break;
    }
    updateStyle();
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
    double opacity = 1.0;

    if (isSelected()) {
      baseColor = colorHex;

      // Scale brightness/opacity based on parameter
      switch (editMode) {
        case VELOCITY:
          opacity = 0.3 + (bridge.getVelocity(row, col) * 0.7);
          break;
        case GATE:
          // Maybe use a border width?
          break;
        case PROBABILITY:
          opacity = 0.3 + (bridge.getStepProbability(row, col) * 0.7);
          break;
        default:
          break;
      }
    }

    String style;
    if (hasPlayhead) {
      style =
          String.format(
              "-fx-background-color: white; -fx-border-color: %s; -fx-border-width: 2; -fx-background-radius: 5; -fx-border-radius: 5; -fx-opacity: %f;",
              baseColor, opacity);
    } else {
      style =
          String.format(
              "-fx-background-color: %s; -fx-background-radius: 5; -fx-opacity: %f;",
              baseColor, opacity);
    }
    setStyle(style);
  }
}
