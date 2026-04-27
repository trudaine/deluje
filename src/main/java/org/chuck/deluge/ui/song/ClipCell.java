package org.chuck.deluge.ui.song;

import javafx.geometry.Insets;
import javafx.scene.control.Button;

/** A cell in the Song Mode clip launcher grid. Represents a pattern that can be launched. */
public class ClipCell extends Button {

  public enum State {
    EMPTY,
    FILLED,
    QUEUED,
    PLAYING
  }

  private State currentState = State.EMPTY;
  private final int trackIndex;
  private final int slotIndex;
  private String patternId = null;
  private String padColor = "#00ffcc"; // Default color

  public ClipCell(int trackIndex, int slotIndex) {
    this.trackIndex = trackIndex;
    this.slotIndex = slotIndex;

    setPrefSize(80, 40);
    updateStyle();

    setOnAction(
        e -> {
          // Toggle logic for MVP (a real implementation would queue it via LaunchQuantController)
          if (currentState == State.EMPTY) {
            setFilled("PAT_" + slotIndex);
          } else if (currentState == State.FILLED) {
            setQueued();
          } else if (currentState == State.QUEUED) {
            setPlaying();
          } else if (currentState == State.PLAYING) {
            setEmpty();
          }
        });

    setOnDragDetected(
        e -> {
          if (currentState != State.EMPTY) {
            javafx.scene.input.TransferMode mode =
                e.isAltDown()
                    ? javafx.scene.input.TransferMode.COPY
                    : javafx.scene.input.TransferMode.MOVE;
            javafx.scene.input.Dragboard db = startDragAndDrop(mode);
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(patternId);
            db.setContent(content);
            e.consume();
          }
        });

    setOnDragOver(
        e -> {
          if (e.getGestureSource() != this && e.getDragboard().hasString()) {
            e.acceptTransferModes(javafx.scene.input.TransferMode.COPY_OR_MOVE);
          }
          e.consume();
        });

    setOnDragDropped(
        e -> {
          javafx.scene.input.Dragboard db = e.getDragboard();
          boolean success = false;
          if (db.hasString()) {
            String draggedPatternId = db.getString();

            if (e.getTransferMode() == javafx.scene.input.TransferMode.MOVE) {
              if (e.getGestureSource() instanceof ClipCell) {
                ClipCell sourceCell = (ClipCell) e.getGestureSource();
                sourceCell.setEmpty();
              }
            }

            setFilled(draggedPatternId);
            success = true;
          }
          e.setDropCompleted(success);
          e.consume();
        });
    setOnContextMenuRequested(
        e -> {
          javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
          dialog.setTitle("Track Inspector");
          dialog.getDialogPane().setPrefSize(800, 500);

          javafx.scene.control.TabPane tabPane = new javafx.scene.control.TabPane();
          tabPane.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

          javafx.scene.control.Tab t1 = new javafx.scene.control.Tab("PRESETS");
          javafx.scene.layout.VBox p1 = new javafx.scene.layout.VBox(20);
          p1.setPadding(new Insets(20));
          javafx.scene.control.Label lP = new javafx.scene.control.Label("Patch Selection:");
          javafx.scene.control.ComboBox<String> cb = new javafx.scene.control.ComboBox<>();
          cb.getItems().addAll("000 Rich Saw Bass", "017 Impact Saw Lead", "073 Piano");
          p1.getChildren().addAll(lP, cb);
          t1.setContent(p1);

          javafx.scene.control.Tab t2 = new javafx.scene.control.Tab("MIXER");
          javafx.scene.layout.VBox p2 = new javafx.scene.layout.VBox(20);
          p2.setPadding(new Insets(20));
          javafx.scene.control.Label lM = new javafx.scene.control.Label("Volume slider:");
          javafx.scene.control.Slider slider = new javafx.scene.control.Slider(0, 100, 80);
          javafx.scene.control.Label lPan = new javafx.scene.control.Label("Panning slider:");
          javafx.scene.control.Slider panSlider = new javafx.scene.control.Slider(0, 100, 50);
          p2.getChildren().addAll(lM, slider, lPan, panSlider);
          t2.setContent(p2);

          javafx.scene.control.Tab t3 = new javafx.scene.control.Tab("CLIPBOARD");
          javafx.scene.layout.HBox p3 = new javafx.scene.layout.HBox(20);
          p3.setPadding(new Insets(20));
          javafx.scene.control.Button cloneBtn =
              new javafx.scene.control.Button("Clone Clip Variant");
          cloneBtn.setStyle(
              "-fx-font-size: 20px; -fx-font-weight: bold; -fx-padding: 10 20 10 20;");
          p3.getChildren().add(cloneBtn);
          t3.setContent(p3);

          tabPane.getTabs().addAll(t1, t2, t3);

          dialog.getDialogPane().setContent(tabPane);
          dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
          dialog.showAndWait();
        });
  }

  public void setEmpty() {
    currentState = State.EMPTY;
    patternId = null;
    setText("");
    updateStyle();
  }

  public void setFilled(String patternId) {
    currentState = State.FILLED;
    this.patternId = patternId;
    setText(""); // No text in Deluge pads!
    updateStyle();
  }

  public void setQueued() {
    if (currentState != State.EMPTY) {
      currentState = State.QUEUED;
      updateStyle();
    }
  }

  public void setPlaying() {
    if (currentState != State.EMPTY) {
      currentState = State.PLAYING;
      updateStyle();
    }
  }

  public int getSlotIndex() {
    return slotIndex;
  }

  public State getCurrentState() {
    return currentState;
  }

  public String getPatternId() {
    return patternId;
  }

  public void setPadColor(String color) {
    this.padColor = color;
    updateStyle();
  }

  private void updateStyle() {
    String baseColor = "#333333";
    String textColor = "#888888";
    String borderColor = "#555555";

    switch (currentState) {
      case EMPTY:
        baseColor = "#222222";
        break;
      case FILLED:
        baseColor = padColor;
        textColor = "#ffffff";
        break;
      case QUEUED:
        baseColor = "#888844"; // Yellow-ish
        textColor = "#ffffff";
        borderColor = "#ffff00";
        break;
      case PLAYING:
        baseColor = "#448844"; // Green-ish
        textColor = "#ffffff";
        borderColor = "#00ff00";
        break;
    }

    setStyle(
        String.format(
            "-fx-background-color: %s; -fx-text-fill: %s; -fx-border-color: %s; -fx-border-width: 2; -fx-background-radius: 5; -fx-border-radius: 5;",
            baseColor, textColor, borderColor));
  }
}
