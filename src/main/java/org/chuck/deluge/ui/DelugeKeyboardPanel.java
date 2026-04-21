package org.chuck.deluge.ui;

import java.util.concurrent.ConcurrentHashMap;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.chuck.midi.MidiMsg;

/**
 * A JavaFX component that visualizes a piano keyboard.
 * Optimized for the Deluge Emulator UI.
 */
public class DelugeKeyboardPanel extends Pane {
  private static final int NUM_KEYS = 88;
  private static final int START_NOTE = 21; // A0

  private final Rectangle[] keys = new Rectangle[NUM_KEYS];
  private final boolean[] isBlack = new boolean[NUM_KEYS];
  private final ConcurrentHashMap<Integer, Color> activeNotes = new ConcurrentHashMap<>();

  private final Label midiInfoLabel = new Label("MIDI: Ready");

  public DelugeKeyboardPanel() {
    setPrefHeight(100);
    setMinHeight(100);
    setStyle("-fx-background-color: #1a1a1a;");

    double whiteKeyWidth = 18;
    double blackKeyWidth = 12;
    double whiteKeyHeight = 70;
    double blackKeyHeight = 45;

    double x = 0;

    // Pass 1: White keys
    for (int i = 0; i < NUM_KEYS; i++) {
      int note = START_NOTE + i;
      int noteInOctave = note % 12;
      isBlack[i] =
          (noteInOctave == 1
              || noteInOctave == 3
              || noteInOctave == 6
              || noteInOctave == 8
              || noteInOctave == 10);

      if (!isBlack[i]) {
        Rectangle rect = new Rectangle(x, 0, whiteKeyWidth, whiteKeyHeight);
        rect.setFill(Color.WHITE);
        rect.setStroke(Color.BLACK);
        keys[i] = rect;
        getChildren().add(rect);
        x += whiteKeyWidth;
      }
    }

    // Pass 2: Black keys (overlay)
    x = 0;
    for (int i = 0; i < NUM_KEYS; i++) {
      int note = START_NOTE + i;
      if (!isBlack[i]) {
        x += whiteKeyWidth;
      } else {
        double bx = x - (blackKeyWidth / 2.0);
        Rectangle rect = new Rectangle(bx, 0, blackKeyWidth, blackKeyHeight);
        rect.setFill(Color.BLACK);
        rect.setStroke(Color.BLACK);
        keys[i] = rect;
        getChildren().add(rect);
      }
    }

    midiInfoLabel.setStyle(
        "-fx-font-family: 'Monospaced'; -fx-font-size: 10; -fx-text-fill: #888;");
    midiInfoLabel.setLayoutY(75);
    midiInfoLabel.setLayoutX(10);

    getChildren().add(midiInfoLabel);
  }

  public void noteOn(int note, Color color) {
    int idx = note - START_NOTE;
    if (idx >= 0 && idx < NUM_KEYS) {
      activeNotes.put(note, color);
      updateKeyColor(idx, color);
    }
  }

  public void noteOff(int note) {
    int idx = note - START_NOTE;
    if (idx >= 0 && idx < NUM_KEYS) {
      activeNotes.remove(note);
      updateKeyColor(idx, null);
    }
  }

  private void updateKeyColor(int idx, Color color) {
    Platform.runLater(
        () -> {
          if (color != null) {
            keys[idx].setFill(color);
          } else {
            keys[idx].setFill(isBlack[idx] ? Color.BLACK : Color.WHITE);
          }
        });
  }
}
