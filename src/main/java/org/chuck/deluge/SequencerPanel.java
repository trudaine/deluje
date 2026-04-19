package org.chuck.deluge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import org.chuck.core.ChuckVM;

/**
 * A TR-808 style visual sequencer for ChucK-Java. Features 8 tracks with real samples, Save/Load,
 * Randomness.
 */
public class SequencerPanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final int ROWS = BridgeContract.TRACKS;
  private final int COLS = BridgeContract.STEPS;
  private final ToggleButton[][] grid = new ToggleButton[ROWS][COLS];
  private final Circle[] cursors = new Circle[COLS];

  private final String[] drumNames = {
    "Kick", "Snare", "HH-Closed", "HH-Open", "Clap", "Cowbell", "Click", "Snare-Hop"
  };

  private int currentStep = -1;

  public SequencerPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    setSpacing(10);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #2b2b2b; -fx-border-color: #555; -fx-border-width: 1;");

    setupUI();
    registerGlobals();
  }

  public void registerGlobals() {
    bridge.register(vm);
  }

  /** Syncs the UI grid buttons with the current state of the patternArray. */
  public void syncUIFromVM() {
    for (int r = 0; r < ROWS; r++) {
      for (int c = 0; c < COLS; c++) {
        int idx = r * COLS + c;
        boolean active = bridge.patternArray().getInt(idx) > 0;
        if (grid[r][c].isSelected() != active) {
          final int row = r;
          final int col = c;
          final boolean sel = active;
          javafx.application.Platform.runLater(
              () -> {
                grid[row][col].setSelected(sel);
                updateButtonStyle(row, col, sel);
              });
        }
      }
    }
  }

  private void setupUI() {
    HBox header = new HBox(10);
    Label title = new Label("GRID SEQUENCER PRO");
    title.setStyle("-fx-text-fill: gold; -fx-font-weight: bold; -fx-font-size: 14;");
    header.getChildren().addAll(title);

    GridPane gridPane = new GridPane();
    gridPane.setHgap(4);
    gridPane.setVgap(4);
    gridPane.setAlignment(Pos.CENTER);

    for (int r = 0; r < ROWS; r++) {
      Label lbl = new Label(drumNames[r]);
      lbl.setStyle("-fx-text-fill: #aaa; -fx-font-size: 10; -fx-font-family: 'Monospaced';");
      lbl.setPrefWidth(80);
      gridPane.add(lbl, 0, r);

      for (int c = 0; c < COLS; c++) {
        ToggleButton btn = new ToggleButton();
        btn.setPrefSize(28, 22);

        int group = c / 4;
        String color = (group % 2 == 0) ? "#444" : "#333";
        btn.setStyle("-fx-background-color: " + color + "; -fx-border-color: #111;");

        final int row = r;
        final int col = c;
        btn.setOnAction(e -> updateValue(row, col, btn.isSelected()));

        grid[r][c] = btn;
        gridPane.add(btn, c + 1, r);
      }
    }

    HBox cursorBox = new HBox(4);
    cursorBox.setAlignment(Pos.CENTER);
    Region cursorSpacer = new Region();
    cursorSpacer.setPrefWidth(84);
    cursorBox.getChildren().add(cursorSpacer);
    for (int c = 0; c < COLS; c++) {
      Circle dot = new Circle(3, Color.TRANSPARENT);
      dot.setStroke(Color.GRAY);
      cursors[c] = dot;
      cursorBox.getChildren().add(dot);
    }

    getChildren().addAll(header, new Separator(), gridPane, cursorBox);
  }

  private void updateValue(int row, int col, boolean selected) {
    bridge.setStep(row, col, selected);
    updateButtonStyle(row, col, selected);
  }

  private void updateButtonStyle(int row, int col, boolean selected) {
    String base = ((col / 4) % 2 == 0) ? "#444" : "#333";
    if (selected) {
      grid[row][col].setStyle("-fx-background-color: #4CAF50; -fx-border-color: #111;");
    } else {
      grid[row][col].setStyle("-fx-background-color: " + base + "; -fx-border-color: #111;");
    }
  }

  public void setStep(int step) {
    if (currentStep >= 0 && currentStep < COLS) {
      cursors[currentStep].setFill(Color.TRANSPARENT);
    }
    currentStep = step % COLS;
    if (currentStep >= 0 && currentStep < COLS) {
      cursors[currentStep].setFill(Color.LIME);
    }
  }

  public void randomizeGrid() {
    Random rand = new Random();
    for (int r = 0; r < ROWS; r++) {
      for (int c = 0; c < COLS; c++) {
        boolean active = rand.nextDouble() < 0.25; // 25% density
        grid[r][c].setSelected(active);
        updateValue(r, c, active);
      }
    }
  }

  public void clearGrid() {
    for (int r = 0; r < ROWS; r++) {
      for (int c = 0; c < COLS; c++) {
        grid[r][c].setSelected(false);
        updateValue(r, c, false);
      }
    }
  }

  public void savePattern() {
    FileChooser fc = new FileChooser();
    fc.setTitle("Save Pattern");
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pattern Files", "*.txt"));
    File f = fc.showSaveDialog(getScene().getWindow());
    if (f != null) {
      StringBuilder sb = new StringBuilder();
      for (int r = 0; r < ROWS; r++) {
        for (int c = 0; c < COLS; c++) {
          sb.append(grid[r][c].isSelected() ? "1" : "0");
        }
        sb.append("\n");
      }
      try {
        Files.writeString(f.toPath(), sb.toString());
      } catch (IOException ignored) {
      }
    }
  }

  public void loadPattern() {
    FileChooser fc = new FileChooser();
    fc.setTitle("Load Pattern");
    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pattern Files", "*.txt"));
    File f = fc.showOpenDialog(getScene().getWindow());
    if (f != null) {
      try {
        List<String> lines = Files.readAllLines(f.toPath());
        for (int r = 0; r < ROWS && r < lines.size(); r++) {
          String line = lines.get(r);
          for (int c = 0; c < COLS && c < line.length(); c++) {
            boolean active = line.charAt(c) == '1';
            grid[r][c].setSelected(active);
            updateValue(r, c, active);
          }
        }
      } catch (IOException ignored) {
      }
    }
  }
}
