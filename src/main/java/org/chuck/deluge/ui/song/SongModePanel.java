package org.chuck.deluge.ui.song;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * The main container for Song Mode. Contains the A-Z SectionBar and a grid of ClipCells for
 * launching patterns.
 */
public class SongModePanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private org.chuck.deluge.model.ProjectModel projectModel;

  private final SectionBar sectionBar;
  private final ClipCell[][] clipGrid; // [clips][slots]
  private final javafx.scene.control.Button[] launchButtons = new javafx.scene.control.Button[64];
  private final LaunchQuantController quantController;

  public SongModePanel(
      ChuckVM vm,
      BridgeContract bridge,
      org.chuck.deluge.model.ProjectModel projectModel,
      int numSlots) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = projectModel;
    int maxClips = 64;
    this.clipGrid = new ClipCell[maxClips][numSlots];

    setAlignment(Pos.TOP_LEFT);
    setSpacing(5);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #1a1a1a;");

    // 1. The Section Bar (A, B, C...)
    sectionBar = new SectionBar(numSlots);
    sectionBar.setOnSectionLaunched(this::armSection);
    getChildren().add(sectionBar);

    // 2. The Clip Grid and Quantization Controller
    refresh();
    quantController = new LaunchQuantController(vm, bridge, clipGrid, maxClips, numSlots);
  }

  public void setProjectModel(org.chuck.deluge.model.ProjectModel projectModel) {
    this.projectModel = projectModel;
  }

  private java.util.function.BiConsumer<
          org.chuck.deluge.model.TrackModel, org.chuck.deluge.model.ClipModel>
      onClipSelected;

  public void setOnClipSelected(
      java.util.function.BiConsumer<
              org.chuck.deluge.model.TrackModel, org.chuck.deluge.model.ClipModel>
          callback) {
    this.onClipSelected = callback;
  }

  private java.util.function.BiConsumer<String, String> onCreateTrack;

  public void setOnCreateTrack(java.util.function.BiConsumer<String, String> callback) {
    this.onCreateTrack = callback;
  }

  private java.util.function.BiConsumer<
          org.chuck.deluge.model.TrackModel, org.chuck.deluge.model.ClipModel>
      onClipLaunched;

  public void setOnClipLaunched(
      java.util.function.BiConsumer<
              org.chuck.deluge.model.TrackModel, org.chuck.deluge.model.ClipModel>
          callback) {
    this.onClipLaunched = callback;
  }

  public void setClipActive(int rowIdx, boolean active) {
    if (rowIdx >= 0 && rowIdx < launchButtons.length) {
      javafx.scene.control.Button btn = launchButtons[rowIdx];
      if (btn != null) {
        if (active) {
          btn.setStyle("-fx-background-color: #00ff00; -fx-text-fill: white;");
        } else {
          btn.setStyle("-fx-background-color: #444; -fx-text-fill: white;");
        }
      }
    }
  }

  public void refresh() {
    if (getChildren().size() > 1) {
      getChildren().remove(1);
    }

    GridPane grid = new GridPane();
    grid.setHgap(5);
    grid.setVgap(5);

    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();

    int rowIdx = 0;
    int numSlots = clipGrid[0].length;

    // 1. Populate rows with existing clips
    for (org.chuck.deluge.model.TrackModel track : tracks) {
      for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {

        Label label = new Label(clip.getName() + " (" + track.getName() + ")");
        label.setPrefWidth(120);
        label.setAlignment(Pos.CENTER_RIGHT);
        label.setTextFill(javafx.scene.paint.Color.web("#cccccc"));

        javafx.scene.layout.HBox headerBox = new javafx.scene.layout.HBox(5);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        headerBox.getChildren().add(label); // Only label!

        final org.chuck.deluge.model.ClipModel currentClip = clip;
        headerBox.setOnMouseClicked(
            e -> {
              if (e.getClickCount() == 2) {
                if (onClipSelected != null) {
                  onClipSelected.accept(track, currentClip);
                }
              }
            });

        grid.add(headerBox, 0, rowIdx);

        // Right side controls
        javafx.scene.control.Button launchBtn = new javafx.scene.control.Button("L");
        launchBtn.setPrefWidth(35);
        launchBtn.setStyle("-fx-background-color: #444; -fx-text-fill: white;");

        int currentClipRow = rowIdx;
        launchBtn.setOnAction(
            e -> {
              boolean wasPlaying = launchBtn.getStyle().contains("#00ff00");
              if (wasPlaying) {
                launchBtn.setStyle("-fx-background-color: #444; -fx-text-fill: white;");
              } else {
                launchBtn.setStyle("-fx-background-color: #00ff00; -fx-text-fill: white;");
                stopOtherClipsOfTrack(track, currentClipRow);
                if (onClipLaunched != null) {
                  onClipLaunched.accept(track, currentClip);
                }
              }
            });

        grid.add(launchBtn, numSlots + 1, rowIdx); // Column numSlots + 1

        javafx.scene.control.Button colorBtn = new javafx.scene.control.Button("C");
        colorBtn.setPrefWidth(35);
        colorBtn.setStyle("-fx-background-color: " + clip.getColor() + "; -fx-text-fill: black;");

        String[] colors = {"#00ffcc", "#ff0055", "#ffee00", "#00ff00"};
        colorBtn.setOnAction(
            e -> {
              String currentColor = currentClip.getColor();
              int idx = java.util.Arrays.asList(colors).indexOf(currentColor);
              int nextIdx = (idx + 1) % colors.length;
              String newColor = colors[nextIdx];
              currentClip.setColor(newColor);
              colorBtn.setStyle("-fx-background-color: " + newColor + "; -fx-text-fill: black;");

              // Update pad colors in the row
              for (int s = 0; s < numSlots; s++) {
                ClipCell cell = clipGrid[currentClipRow][s];
                if (cell.getCurrentState() == ClipCell.State.FILLED) {
                  cell.setPadColor(newColor);
                }
              }
            });

        grid.add(colorBtn, numSlots + 2, rowIdx); // Column numSlots + 2

        javafx.scene.control.Button muteBtn = new javafx.scene.control.Button("M");
        muteBtn.setPrefWidth(35);
        muteBtn.setStyle("-fx-background-color: #444; -fx-text-fill: white;");

        int trackIdx = projectModel.getTracks().indexOf(track);
        int baseTrack = trackIdx * 8;

        muteBtn.setOnAction(
            e -> {
              boolean wasMuted = muteBtn.getStyle().contains("#ffff00");
              if (wasMuted) {
                muteBtn.setStyle("-fx-background-color: #444; -fx-text-fill: white;");
                for (int i = 0; i < 8; i++) {
                  bridge.setMute(baseTrack + i, false);
                }
              } else {
                muteBtn.setStyle("-fx-background-color: #ffff00; -fx-text-fill: black;");
                for (int i = 0; i < 8; i++) {
                  bridge.setMute(baseTrack + i, true);
                }
              }
            });

        grid.add(muteBtn, numSlots + 3, rowIdx); // Column numSlots + 3

        for (int s = 0; s < numSlots; s++) {
          ClipCell cell = new ClipCell(rowIdx, s);
          cell.setFilled(clip.getName());
          cell.setPadColor(clip.getColor());

          clipGrid[rowIdx][s] = cell;
          grid.add(cell, s + 1, rowIdx);
        }

        rowIdx++;
        if (rowIdx >= clipGrid.length) break;
      }
      if (rowIdx >= clipGrid.length) break;
    }

    // 2. Fill remaining rows up to maxClips (64) to avoid NullPointerException in controller
    while (rowIdx < clipGrid.length) {
      Label label = new Label("EMPTY");
      label.setPrefWidth(120);
      label.setAlignment(Pos.CENTER_RIGHT);
      label.setTextFill(javafx.scene.paint.Color.web("#666666"));

      javafx.scene.layout.HBox headerBox = new javafx.scene.layout.HBox(5);
      headerBox.setAlignment(Pos.CENTER_LEFT);
      headerBox.getChildren().addAll(label);

      grid.add(headerBox, 0, rowIdx);

      for (int s = 0; s < numSlots; s++) {
        ClipCell cell = new ClipCell(rowIdx, s);
        cell.setEmpty();

        int currentEmptyRow = rowIdx;
        int currentSlot = s;
        cell.setOnAction(
            e -> {
              handleEmptyCellClick(currentEmptyRow, currentSlot);
            });

        clipGrid[rowIdx][s] = cell;
        grid.add(cell, s + 1, rowIdx);
      }
      rowIdx++;
    }

    javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane(grid);
    scrollPane.setFitToWidth(true);
    scrollPane.setStyle("-fx-background: #1a1a1a; -fx-border-color: transparent;");
    getChildren().add(scrollPane);
  }

  private void stopOtherClipsOfTrack(org.chuck.deluge.model.TrackModel track, int activeRow) {
    // Simple implementation: turn off green light for other buttons
    // A full implementation would need to know which rows belong to which track.
    // For now, we just simulate it by turning off all OTHER buttons!
    // This enforces "one playing clip total" which is even stricter but safe for MVP.
    for (int i = 0; i < launchButtons.length; i++) {
      if (i != activeRow && launchButtons[i] != null) {
        launchButtons[i].setStyle("-fx-background-color: #444; -fx-text-fill: white;");
      }
    }
  }

  /** Arms an entire column (Section) of clips to be launched. */
  private void armSection(int sectionIndex) {
    for (int t = 0; t < clipGrid.length; t++) {
      ClipCell cell = clipGrid[t][sectionIndex];
      if (cell.getCurrentState() == ClipCell.State.FILLED
          || cell.getCurrentState() == ClipCell.State.PLAYING) {
        cell.setQueued();
      }
    }

    // If sequencer is stopped, launch immediately
    if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0L) {
      quantController.forceLaunchQueued();
    }
  }

  /** Called every frame by the animation timer to handle launch quantization. */
  public void update(int currentStep) {
    quantController.update(currentStep);
  }

  private void handleEmptyCellClick(int rowIdx, int slotIdx) {
    java.util.List<String> choices = java.util.Arrays.asList("Kit", "Synth");
    javafx.scene.control.ChoiceDialog<String> dialog =
        new javafx.scene.control.ChoiceDialog<>("Kit", choices);
    dialog.setTitle("Create Track");
    dialog.setHeaderText("Choose track type:");
    dialog.setContentText("Type:");

    java.util.Optional<String> result = dialog.showAndWait();
    if (result.isPresent()) {
      String type = result.get();
      if (type.equals("Kit")) {
        String preset = promptForPreset("KITS");
        if (preset != null && onCreateTrack != null) {
          onCreateTrack.accept("KIT", "/KITS/" + preset);
        }
      } else if (type.equals("Synth")) {
        String preset = promptForPreset("SYNTHS");
        if (preset != null && onCreateTrack != null) {
          onCreateTrack.accept("SYNTH", "/SYNTHS/" + preset);
        }
      }
    }
  }

  private String promptForPreset(String folder) {
    java.util.List<String> choices =
        org.chuck.deluge.ui.ProjectSidebarPanel.getPresets("/" + folder);
    if (choices.isEmpty()) {
      if (folder.equals("KITS")) {
        choices = java.util.Arrays.asList("000 TR-808.XML", "001 DDD-1.XML", "002 SDS-5.XML");
      } else {
        choices =
            java.util.Arrays.asList(
                "000 Rich Saw Bass.XML", "017 Impact Saw Lead.XML", "073 Piano.XML");
      }
    }

    javafx.scene.control.ChoiceDialog<String> dialog =
        new javafx.scene.control.ChoiceDialog<>(choices.get(0), choices);
    dialog.setTitle("Pick Preset");
    dialog.setHeaderText("Choose a preset to load:");
    dialog.setContentText("Preset:");

    java.util.Optional<String> result = dialog.showAndWait();
    return result.orElse(null);
  }
}
