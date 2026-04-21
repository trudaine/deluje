package org.chuck.deluge.ui;

import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.ui.ParameterRibbonPanel.EditMode;

/**
 * A specialized ToggleButton for the Deluge grid. Handles left-click (toggle) and right-click (drag
 * edit).
 */
public class StepCellButton extends ToggleButton {
  private int baseTrack = 0;
  private final int rowId;
  private final int stepId;
  private final BridgeContract bridge;
  private final java.util.function.Supplier<EditMode> editModeSupplier;

  private boolean playheadActive = false;
  private boolean isSynthMode = false;

  public StepCellButton(
      int rowId,
      int stepId,
      BridgeContract bridge,
      java.util.function.Supplier<EditMode> editModeSupplier) {
    this.rowId = rowId;
    this.stepId = stepId;
    this.bridge = bridge;
    this.editModeSupplier = editModeSupplier;

    setPrefSize(40, 40);
    updateStyle();

    // Sync with Bridge
    setSelected(bridge.getStep(baseTrack + rowId, stepId));

    setOnAction(
        e -> {
          if (isSynthMode) {
              bridge.setStep(baseTrack, stepId, isSelected());
              if (isSelected()) {
                  // Invert pitch mapping: top row (0) has highest pitch (23)
                  bridge.setPitch(baseTrack, stepId, (24 - 1) - rowId);
              }
          } else {
            bridge.setStep(baseTrack + rowId, stepId, isSelected());
          }
          updateStyle();
        });

    addEventHandler(MouseEvent.MOUSE_PRESSED, this::handleMousePressed);
    addEventHandler(MouseEvent.MOUSE_DRAGGED, this::handleMouseDragged);
  }

  public void setPlayheadActive(boolean active) {
    this.playheadActive = active;
    updateStyle();
  }

  private void handleMousePressed(MouseEvent e) {
    if (e.getButton() == MouseButton.SECONDARY) {
      updateValueFromMouse(e.getY());
    }
  }

  private void handleMouseDragged(MouseEvent e) {
    if (e.getButton() == MouseButton.SECONDARY) {
      updateValueFromMouse(e.getY());
    }
  }

  private void updateValueFromMouse(double mouseY) {
    double val = 1.0 - (mouseY / getHeight());
    val = Math.max(0, Math.min(1, val));

    EditMode mode = editModeSupplier.get();
    int currentTrackId = isSynthMode ? baseTrack : baseTrack + rowId;
    switch (mode) {
      case VELOCITY -> bridge.setVelocity(currentTrackId, stepId, val);
      case GATE -> bridge.setGate(currentTrackId, stepId, val);
      case PITCH -> {
        int pitch = (int) (val * 24) - 12; // +/- 1 octave
        bridge.setPitch(currentTrackId, stepId, pitch);
      }
    }
    updateStyle();
  }

  public void updateStyle() {
    setGraphic(null); // Clear previous hint
    
    String baseColor = isSelected() ? "#00ffcc" : "#444";
    if (playheadActive) {
      baseColor = isSelected() ? "#ffffff" : "#666";
    }

    String borderStyle = "-fx-border-color: #222; -fx-border-width: 1;";
    String style = "-fx-base: " + baseColor + "; " + borderStyle;

    if (isSelected()) {
      EditMode mode = editModeSupplier.get();
      double val = 0.8;
      int currentTrackId = isSynthMode ? baseTrack : baseTrack + rowId;
      switch (mode) {
        case VELOCITY -> val = bridge.getVelocity(currentTrackId, stepId);
        case GATE -> val = bridge.getGate(currentTrackId, stepId);
      }
      style += " -fx-opacity: " + (0.4 + (val * 0.6)) + ";";
      
      // Add visual hint bar
      String colorStr = switch (mode) {
        case VELOCITY -> "#00ffcc";
        case GATE -> "#ff9800";
        case PITCH -> "#e91e63";
        default -> "#00ffcc";
      };
      
      javafx.scene.shape.Rectangle bar = new javafx.scene.shape.Rectangle(30 * val, 4);
      bar.setFill(javafx.scene.paint.Color.web(colorStr));
      javafx.scene.layout.StackPane pane = new javafx.scene.layout.StackPane(bar);
      pane.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
      setGraphic(pane);
    }

    setStyle(style);
  }

  public void setEditMode(EditMode mode) {
    updateStyle();
  }

  public int getStepId() {
    return stepId;
  }

  public void setBaseTrack(int baseTrack) {
    this.baseTrack = baseTrack;
    setSelected(bridge.getStep(isSynthMode ? baseTrack : baseTrack + rowId, stepId));
    updateStyle();
  }

  public void setSynthMode(boolean isSynthMode) {
    this.isSynthMode = isSynthMode;
    setSelected(bridge.getStep(isSynthMode ? baseTrack : baseTrack + rowId, stepId));
    updateStyle();
  }
}
