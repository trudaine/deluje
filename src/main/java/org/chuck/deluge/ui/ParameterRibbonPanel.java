package org.chuck.deluge.ui;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * The horizontal ribbon of 13 parameter buttons above the matrix. Used for selecting which
 * parameter (Velocity, Gate, Probability, etc.) is currently being edited.
 */
public class ParameterRibbonPanel extends HBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private Consumer<EditMode> modeChangeListener;

  public enum EditMode {
    LEVEL,
    PAN,
    PITCH,
    FILTER,
    RESONANCE,
    MOD_FX,
    DELAY,
    REVERB,
    STUTTER,
    PROBABILITY,
    GATE,
    VELOCITY,
    START_END
  }

  private EditMode currentMode = EditMode.VELOCITY;
  private final ToggleGroup group = new ToggleGroup();

  private final String[] PARAM_LABELS = {
    "LEVEL",
    "PAN",
    "PITCH",
    "FILTER",
    "RESONANCE",
    "MOD FX",
    "DELAY",
    "REVERB",
    "STUTTER",
    "PROBABILITY",
    "GATE",
    "VELOCITY",
    "START/END"
  };

  public ParameterRibbonPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setAlignment(Pos.CENTER);
    setSpacing(5);
    setPadding(new Insets(5));
    setStyle("-fx-background-color: #222222;");

    for (int i = 0; i < PARAM_LABELS.length; i++) {
      String label = PARAM_LABELS[i];
      ToggleButton btn = new ToggleButton(label);
      btn.setToggleGroup(group);
      btn.setStyle("-fx-base: #333333; -fx-text-fill: white; -fx-font-size: 10px;");
      btn.setPrefHeight(30);
      btn.setPrefWidth(85);

      final EditMode mode = EditMode.values()[i];
      btn.setOnAction(
          e -> {
            currentMode = mode;
            if (modeChangeListener != null) {
              modeChangeListener.accept(mode);
            }
          });

      if (mode == EditMode.VELOCITY) {
        btn.setSelected(true);
      }

      getChildren().add(btn);
    }
  }

  public void setOnModeChange(Consumer<EditMode> listener) {
    this.modeChangeListener = listener;
  }

  public EditMode getCurrentMode() {
    return currentMode;
  }
}
