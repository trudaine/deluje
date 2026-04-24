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
  private final org.chuck.core.ChuckVM vm;
  private final BridgeContract bridge;
  private final java.util.function.Supplier<EditMode> editModeSupplier;

  private boolean playheadActive = false;
  private boolean isSynthMode = false;
  private javafx.animation.Timeline activeStutter;


  public StepCellButton(
      int rowId,
      int stepId,
      org.chuck.core.ChuckVM vm,
      BridgeContract bridge,
      java.util.function.Supplier<EditMode> editModeSupplier) {
    this.rowId = rowId;
    this.stepId = stepId;
    this.vm = vm;
    this.bridge = bridge;
    this.editModeSupplier = editModeSupplier;

    setPrefSize(40, 40);
    updateStyle();

    // Sync with Bridge
    setSelected(bridge.getStep(baseTrack + rowId, stepId));

    addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
        if (e.getButton() == MouseButton.PRIMARY) {
            String sp = (String) vm.getGlobalObject("g_sample_" + (baseTrack + rowId));
            if (sp != null && !sp.isEmpty()) {
                if (activeStutter != null) activeStutter.stop();
                activeStutter = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(
                        javafx.util.Duration.millis(150),
                        ev -> {
                            new Thread(() -> {
                                try {
                                    java.io.File file = new java.io.File(sp);
                                    if (file.exists()) {
                                        javax.sound.sampled.AudioInputStream stream = javax.sound.sampled.AudioSystem.getAudioInputStream(file);
                                        javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
                                        clip.open(stream);
                                        clip.start();
                                    }
                                } catch (Exception ex) {}
                            }).start();
                        }
                    )
                );
                activeStutter.setCycleCount(javafx.animation.Animation.INDEFINITE);
                activeStutter.play();
            }
        }
    });

    addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
        if (activeStutter != null) {
            activeStutter.stop();
            activeStutter = null;
        }
    });

    addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {

        if (e.getButton() == MouseButton.PRIMARY) {
            boolean state = isSelected();
            if (e.isShiftDown() && !isSynthMode) {
                if (baseTrack + rowId + 4 < 64) bridge.setStep(baseTrack + rowId + 4, stepId, state);
                if (baseTrack + rowId + 7 < 64) bridge.setStep(baseTrack + rowId + 7, stepId, state);
            }
            
            // Audition sound
            if (state) {
                int trackType = bridge.getTrackType(baseTrack + rowId);
                if (trackType == 2) return; // Silent for MIDI tracks

                String sp = (String) vm.getGlobalObject("g_sample_" + (baseTrack + rowId));


                if (sp != null && !sp.isEmpty()) {
                    new Thread(() -> {
                        try {
                            java.io.File file = new java.io.File(sp);
                            if (file.exists()) {
                                javax.sound.sampled.AudioInputStream stream = javax.sound.sampled.AudioSystem.getAudioInputStream(file);
                                javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
                                clip.open(stream);
                                clip.start();
                            }
                        } catch (Exception ex) {}
                    }).start();
                }
            }
        }
    });

    setOnAction(
        e -> {
          if (bridge.isRecording()) {
            int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
            if (currentStep >= 0 && currentStep < 16) {
              if (isSynthMode) {
                bridge.setStep(baseTrack, currentStep, true);
                bridge.setPitch(baseTrack, currentStep, (24 - 1) - rowId);
              } else {
                bridge.setStep(baseTrack + rowId, currentStep, true);
              }
              bridge.setVelocity(isSynthMode ? baseTrack : baseTrack + rowId, currentStep, 0.8);
              bridge.setGate(isSynthMode ? baseTrack : baseTrack + rowId, currentStep, 1.0);
            }
            setSelected(false); // Act as trigger
          } else {
            if (isSynthMode) {
              bridge.setStep(baseTrack, stepId, isSelected());
              if (isSelected()) {
                bridge.setPitch(baseTrack, stepId, (24 - 1) - rowId);
              }
            } else {
              bridge.setStep(baseTrack + rowId, stepId, isSelected());
            }
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
      if (isSelected()) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        stage.setTitle("Step Properties");
        
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setPadding(new javafx.geometry.Insets(20));
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setStyle("-fx-background-color: #252525;");
        
        javafx.scene.text.Font labelFont = javafx.scene.text.Font.font("System", javafx.scene.text.FontWeight.BOLD, 18);
        
        // 1. Velocity
        javafx.scene.control.Label l1 = new javafx.scene.control.Label("Velocity:");
        l1.setFont(labelFont); l1.setTextFill(javafx.scene.paint.Color.WHITE);
        grid.add(l1, 0, 0);
        javafx.scene.control.Slider velSlider = new javafx.scene.control.Slider(0, 100, 80);
        velSlider.setPrefSize(1200, 50);
        grid.add(velSlider, 1, 0);
        javafx.scene.control.Spinner<Integer> velSpin = new javafx.scene.control.Spinner<>(0, 100, 80);
        velSpin.setPrefWidth(80);
        velSpin.valueProperty().addListener((obs, o, n) -> velSlider.setValue(n));
        velSlider.valueProperty().addListener((obs, o, n) -> velSpin.getValueFactory().setValue(n.intValue()));
        grid.add(velSpin, 2, 0);

        // 2. Probability
        javafx.scene.control.Label l2 = new javafx.scene.control.Label("Probability:");
        l2.setFont(labelFont); l2.setTextFill(javafx.scene.paint.Color.WHITE);
        grid.add(l2, 0, 1);
        javafx.scene.control.Slider probSlider = new javafx.scene.control.Slider(0, 100, 100);
        probSlider.setPrefSize(1200, 50);
        grid.add(probSlider, 1, 1);
        javafx.scene.control.Spinner<Integer> probSpin = new javafx.scene.control.Spinner<>(0, 100, 100);
        probSpin.setPrefWidth(80);
        probSpin.valueProperty().addListener((obs, o, n) -> probSlider.setValue(n));
        probSlider.valueProperty().addListener((obs, o, n) -> probSpin.getValueFactory().setValue(n.intValue()));
        grid.add(probSpin, 2, 1);

        // 3. Gate Length
        javafx.scene.control.Label l3 = new javafx.scene.control.Label("Gate Length:");
        l3.setFont(labelFont); l3.setTextFill(javafx.scene.paint.Color.WHITE);
        grid.add(l3, 0, 2);
        javafx.scene.control.Slider gateSlider = new javafx.scene.control.Slider(1, 16, 1);
        gateSlider.setPrefSize(1200, 50);
        grid.add(gateSlider, 1, 2);
        javafx.scene.control.Spinner<Integer> gateSpin = new javafx.scene.control.Spinner<>(1, 16, 1);
        gateSpin.setPrefWidth(80);
        gateSpin.valueProperty().addListener((obs, o, n) -> gateSlider.setValue(n));
        gateSlider.valueProperty().addListener((obs, o, n) -> gateSpin.getValueFactory().setValue(n.intValue()));
        grid.add(gateSpin, 2, 2);

        // 4. Pitch Offset
        javafx.scene.control.Label l4 = new javafx.scene.control.Label("Pitch Offset:");
        l4.setFont(labelFont); l4.setTextFill(javafx.scene.paint.Color.WHITE);
        grid.add(l4, 0, 3);
        javafx.scene.control.Slider pitchSlider = new javafx.scene.control.Slider(-24, 24, 0);
        pitchSlider.setPrefSize(1200, 50);
        grid.add(pitchSlider, 1, 3);
        javafx.scene.control.Spinner<Integer> pitchSpin = new javafx.scene.control.Spinner<>(-24, 24, 0);
        pitchSpin.setPrefWidth(80);
        pitchSpin.valueProperty().addListener((obs, o, n) -> pitchSlider.setValue(n));
        pitchSlider.valueProperty().addListener((obs, o, n) -> pitchSpin.getValueFactory().setValue(n.intValue()));
        grid.add(pitchSpin, 2, 3);

        javafx.scene.Scene scene = new javafx.scene.Scene(grid, 1600, 350);
        stage.setScene(scene);
        stage.showAndWait();
      }
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
    if (stepId >= 16) return;
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
      String colorStr =
          switch (mode) {
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
