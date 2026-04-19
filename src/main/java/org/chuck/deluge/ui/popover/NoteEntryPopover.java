package org.chuck.deluge.ui.popover;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.chuck.deluge.BridgeContract;

/**
 * A popup for entering melodic notes on a Synth track. Features a chromatic selector, octave
 * controls, and scale filtering.
 */
public class NoteEntryPopover extends Popup {
  private final BridgeContract bridge;
  private final int track;
  private final int step;

  private int octave = 3; // Default C3-B3
  private ScaleFilter.Scale currentScale = ScaleFilter.Scale.CHROMATIC;
  private int rootNote = 0; // C

  private final String[] NOTE_NAMES = {
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
  };
  private final ToggleButton[] noteButtons = new ToggleButton[12];
  private final Label octaveLabel = new Label("OCT: 3");

  public NoteEntryPopover(BridgeContract bridge, int track, int step) {
    this.bridge = bridge;
    this.track = track;
    this.step = step;

    setAutoHide(true);

    VBox root = new VBox(10);
    root.setPadding(new Insets(10));
    root.setStyle(
        "-fx-background-color: #333333; -fx-border-color: #555555; -fx-border-width: 2; -fx-background-radius: 5; -fx-border-radius: 5;");

    Label title = new Label(String.format("Note Entry: TR %d, ST %d", track + 1, step + 1));
    title.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
    root.getChildren().add(title);

    // ── Scale & Root ──
    HBox scaleRow = new HBox(10);
    scaleRow.setAlignment(Pos.CENTER_LEFT);

    ComboBox<ScaleFilter.Scale> scaleCombo = new ComboBox<>();
    scaleCombo.getItems().addAll(ScaleFilter.Scale.values());
    scaleCombo.setValue(currentScale);
    scaleCombo.setOnAction(
        e -> {
          currentScale = scaleCombo.getValue();
          updateNoteButtons();
        });

    ComboBox<String> rootCombo = new ComboBox<>();
    rootCombo.getItems().addAll(NOTE_NAMES);
    rootCombo.setValue(NOTE_NAMES[rootNote]);
    rootCombo.setOnAction(
        e -> {
          rootNote = rootCombo.getSelectionModel().getSelectedIndex();
          updateNoteButtons();
        });

    scaleRow.getChildren().addAll(new Label("Scale:"), scaleCombo, new Label("Root:"), rootCombo);
    root.getChildren().add(scaleRow);

    // ── Octave ──
    HBox octRow = new HBox(10);
    octRow.setAlignment(Pos.CENTER);
    Button octDown = new Button("◄");
    Button octUp = new Button("►");
    octDown.setOnAction(
        e -> {
          octave = Math.max(0, octave - 1);
          octaveLabel.setText("OCT: " + octave);
        });
    octUp.setOnAction(
        e -> {
          octave = Math.min(8, octave + 1);
          octaveLabel.setText("OCT: " + octave);
        });
    octRow.getChildren().addAll(octDown, octaveLabel, octUp);
    root.getChildren().add(octRow);

    // ── Chromatic Grid ──
    GridPane grid = new GridPane();
    grid.setHgap(5);
    grid.setVgap(5);

    for (int i = 0; i < 12; i++) {
      int noteIdx = i;
      ToggleButton btn = new ToggleButton(NOTE_NAMES[i]);
      btn.setPrefSize(45, 45);
      btn.setOnAction(
          e -> {
            // Deselect others (monophonic entry for now)
            for (ToggleButton other : noteButtons) if (other != btn) other.setSelected(false);

            if (btn.isSelected()) {
              int midi = (octave + 1) * 12 + noteIdx;
              bridge.setPitch(track, step, midi - 60); // Offset from middle C
            }
          });
      noteButtons[i] = btn;
      grid.add(btn, i % 6, i / 6);
    }
    updateNoteButtons();
    root.getChildren().add(grid);

    // Dark theme labels
    root.lookupAll(".label")
        .forEach(n -> ((Label) n).setTextFill(javafx.scene.paint.Color.web("#e0e0e0")));

    getContent().add(root);
  }

  private void updateNoteButtons() {
    for (int i = 0; i < 12; i++) {
      boolean inScale = ScaleFilter.isNoteInScale(i, rootNote, currentScale);
      noteButtons[i].setDisable(!inScale);
      if (!inScale) {
        noteButtons[i].setStyle("-fx-base: #222; -fx-text-fill: #444;");
      } else {
        boolean isBlack = (i == 1 || i == 3 || i == 6 || i == 8 || i == 10);
        String color = isBlack ? "#111" : "#eee";
        String text = isBlack ? "white" : "black";
        noteButtons[i].setStyle(String.format("-fx-base: %s; -fx-text-fill: %s;", color, text));
      }
    }
  }
}
