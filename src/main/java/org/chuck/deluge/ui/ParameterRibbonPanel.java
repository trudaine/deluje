package org.chuck.deluge.ui;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiInputRouter;

/**
 * The horizontal ribbon of 13 parameter buttons above the matrix. Used for selecting which
 * parameter (Velocity, Gate, Probability, etc.) is currently being edited.
 */
public class ParameterRibbonPanel extends HBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final MidiInputRouter midiRouter;
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

  public ParameterRibbonPanel(ChuckVM vm, BridgeContract bridge, MidiInputRouter midiRouter) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiRouter = midiRouter;

    setAlignment(Pos.CENTER);
    setSpacing(5);
    setPadding(new Insets(5));
    setStyle("-fx-background-color: #222222;");

    for (int i = 0; i < PARAM_LABELS.length; i++) {
      String label = PARAM_LABELS[i];
      ToggleButton btn = new ToggleButton(label);
      btn.setToggleGroup(group);
      btn.setStyle(
          "-fx-background-color: #2b2b2b; -fx-text-fill: #888888; -fx-font-family: 'Courier New'; -fx-font-size: 10px; -fx-font-weight: bold;");
      btn.setPrefHeight(30);
      btn.setPrefWidth(85);

      btn.selectedProperty()
          .addListener(
              (obs, old, isNowSelected) -> {
                if (isNowSelected) {
                  btn.setStyle(
                      "-fx-background-color: #00ff41; -fx-text-fill: black; -fx-font-family: 'Courier New'; -fx-font-size: 10px; -fx-font-weight: bold;");
                } else {
                  btn.setStyle(
                      "-fx-background-color: #2b2b2b; -fx-text-fill: #888888; -fx-font-family: 'Courier New'; -fx-font-size: 10px; -fx-font-weight: bold;");
                }
              });

      final EditMode mode = EditMode.values()[i];

      // MIDI LEARN Context Menu
      ContextMenu menu = new ContextMenu();
      MenuItem learnItem = new MenuItem("MIDI Learn");
      learnItem.setOnAction(
          e -> {
            String target = getGlobalForMode(mode);
            if (target != null && midiRouter != null) {
              midiRouter.startLearning(target);
              System.out.println("MIDI LEARN: Waiting for CC for " + target);
            }
          });
      menu.getItems().add(learnItem);
      btn.setContextMenu(menu);

      if (mode == EditMode.STUTTER) {
        btn.setOnMousePressed(
            e -> {
              vm.setGlobalInt("g_stutter_on", 1L);
              vm.setGlobalFloat("g_stutter_div", 2.0); // Default stutter rate
            });
        btn.setOnMouseReleased(
            e -> {
              vm.setGlobalInt("g_stutter_on", 0L);
            });
      } else {
        btn.setOnAction(
            e -> {
              currentMode = mode;
              if (modeChangeListener != null) {
                modeChangeListener.accept(mode);
              }
            });
      }

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

  private String getGlobalForMode(EditMode mode) {
    switch (mode) {
      case FILTER:
        return BridgeContract.G_FILTER;
      case DELAY:
        return BridgeContract.G_DELAY_TIME;
      case REVERB:
        return BridgeContract.G_REVERB_ROOM;
      case LEVEL:
        return BridgeContract.G_MASTER_VOL;
      default:
        return null;
    }
  }
}
