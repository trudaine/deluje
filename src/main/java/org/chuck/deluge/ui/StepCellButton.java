package org.chuck.deluge.ui;

import javafx.scene.control.ToggleButton;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
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
          bridge.syncActiveClipToLibrary(row);
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

    double delta = -event.getY() / 100.0;
    
    // Determine target step: if recording, use playhead; else use this cell.
    int targetStep = col;
    boolean liveRec = bridge.isRecording() && bridge.getVm().getGlobalInt(BridgeContract.G_PLAY) == 1;
    if (liveRec) {
        targetStep = (int) bridge.getVm().getGlobalInt(BridgeContract.G_CURRENT_STEP);
        if (targetStep < 0) targetStep = col;
    }

    switch (editMode) {
      case LEVEL:
      case VELOCITY:
        double v = bridge.getVelocity(row, targetStep) + delta;
        bridge.setVelocity(row, targetStep, v);
        break;
      case PAN:
        double pan = bridge.getStepPan(row, targetStep) + delta;
        bridge.setStepPan(row, targetStep, pan);
        break;
      case FILTER:
        double f = bridge.getStepFilter(row, targetStep) + delta;
        bridge.setStepFilter(row, targetStep, f);
        break;
      case RESONANCE:
        double res = bridge.getStepRes(row, targetStep) + delta;
        bridge.setStepRes(row, targetStep, res);
        break;
      case DELAY:
        double d = bridge.getStepDelay(row, targetStep) + delta;
        bridge.setStepDelay(row, targetStep, d);
        break;
      case REVERB:
        double r = bridge.getStepReverb(row, targetStep) + delta;
        bridge.setStepReverb(row, targetStep, r);
        break;
      case MOD_FX:
        double m = bridge.getStepMod(row, targetStep) + delta;
        bridge.setStepMod(row, targetStep, m);
        break;
      case START_END:
        double s = bridge.getStepStart(row, targetStep) + delta;
        bridge.setStepStart(row, targetStep, s);
        break;
      case GATE:
        double g = bridge.getGate(row, targetStep) + delta;
        bridge.setGate(row, targetStep, g);
        break;
      case PROBABILITY:
        double p = bridge.getStepProbability(row, targetStep) + delta;
        bridge.setStepProbability(row, targetStep, p);
        break;
      case PITCH:
        int pitch = bridge.getPitch(row, targetStep) + (int) (delta * 24);
        bridge.setPitch(row, targetStep, pitch);
        break;
      default:
        break;
    }
    bridge.syncActiveClipToLibrary(row);
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

  public void updateStyle() {
    String baseColor = "#333333"; // off
    double opacity = 1.0;

    if (isSelected()) {
      baseColor = colorHex;

      // Scale brightness/opacity based on parameter
      switch (editMode) {
        case LEVEL:
        case VELOCITY:
          opacity = 0.3 + (bridge.getVelocity(row, col) * 0.7);
          break;
        case PAN:
          opacity = 0.3 + (Math.abs(bridge.getStepPan(row, col)) * 0.7);
          break;
        case FILTER:
          // Filter offset can be negative, so we normalize for opacity
          opacity = 0.3 + (Math.abs(bridge.getStepFilter(row, col)) * 0.7);
          break;
        case RESONANCE:
          opacity = 0.3 + (Math.abs(bridge.getStepRes(row, col)) * 0.7);
          break;
        case DELAY:
          opacity = 0.3 + (bridge.getStepDelay(row, col) * 0.7);
          break;
        case REVERB:
          opacity = 0.3 + (bridge.getStepReverb(row, col) * 0.7);
          break;
        case MOD_FX:
          opacity = 0.3 + (bridge.getStepMod(row, col) * 0.7);
          break;
        case START_END:
          opacity = 0.3 + (bridge.getStepStart(row, col) * 0.7);
          break;
        case GATE:
          // Gate length is visualized via border dash array (done in string below)
          break;
        case PROBABILITY:
          opacity = 0.3 + (bridge.getStepProbability(row, col) * 0.7);
          break;
        case PITCH:
          int p = bridge.getPitch(row, col);
          if (p != 0) {
            setText(p > 0 ? "+" + p : String.valueOf(p));
            setTextFill(Color.WHITE);
            setStyle("-fx-font-size: 9px; -fx-font-weight: bold;");
          } else {
            setText("");
          }
          break;
        default:
          setText("");
          break;
      }
    } else {
      setText("");
    }

    String style;
    double gateVal = bridge.getGate(row, col);
    String borderStyle =
        (editMode == EditMode.GATE && isSelected())
            ? String.format("-fx-border-style: dashed; -fx-border-dash-array: %f 5;", gateVal * 20.0)
            : "-fx-border-style: solid;";

    String finalBg = isSelected() ? 
        String.format("linear-gradient(to bottom, %s 0%%, #1a1a1a 100%%)", baseColor) : 
        "#222222";

    String borderColor = hasPlayhead ? "#ffffff" : "#444444";
    double borderWidth = hasPlayhead ? 3.0 : 1.0;

    style = String.format(
        "-fx-background-color: %s; " +
        "-fx-border-color: %s; " +
        "-fx-border-width: %.1f; " +
        "-fx-background-radius: 3; " +
        "-fx-border-radius: 3; " +
        "-fx-opacity: %.2f; " +
        "-fx-font-family: 'Courier New'; " +
        "-fx-font-weight: bold; " +
        "%s",
        finalBg, borderColor, borderWidth, opacity, borderStyle
    );

    setStyle(style);
    }
    }

