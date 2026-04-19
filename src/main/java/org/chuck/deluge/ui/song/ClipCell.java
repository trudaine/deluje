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
    setText(patternId);
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

  private void updateStyle() {
    String baseColor = "#333333";
    String textColor = "#888888";
    String borderColor = "#555555";

    switch (currentState) {
      case EMPTY:
        baseColor = "#222222";
        break;
      case FILLED:
        baseColor = "#444444";
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
