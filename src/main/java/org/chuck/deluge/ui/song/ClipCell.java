package org.chuck.deluge.ui.song;

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
            javafx.scene.input.TransferMode mode = e.isAltDown() ? javafx.scene.input.TransferMode.COPY : javafx.scene.input.TransferMode.MOVE;
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
