package org.chuck.deluge.ui.arranger;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ArrangerClip;

/** Main panel for the Arranger Mode (Linear Timeline). */
public class ArrangerPanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final ArrangerViewModel viewModel;
  private final ArrangerPlaybackController playbackController;

  private final ArrangerRuler ruler;
  private final Canvas trackCanvas;
  private final int numTracks = 8;
  private final double trackHeight = 40.0;

  private final String[] colors = {
    "#FF5555", "#FFaa55", "#FFFF55", "#aaFF55", "#55FF55", "#55FFaa", "#55FFFF", "#55aaFF"
  };

  public ArrangerPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.viewModel = new ArrangerViewModel();
    this.playbackController = new ArrangerPlaybackController(vm, bridge, viewModel);

    // Mock data
    viewModel.addClip(new ArrangerClip(0, "KICK_INTRO", 1, 4));
    viewModel.addClip(new ArrangerClip(1, "SNARE_MAIN", 3, 2));
    viewModel.addClip(new ArrangerClip(4, "SYNTH_BASS", 1, 8));
    viewModel.addClip(new ArrangerClip(5, "SYNTH_LEAD", 5, 4));

    setAlignment(Pos.TOP_LEFT);
    setSpacing(0);
    setPadding(new Insets(10));
    setStyle("-fx-background-color: #1a1a1a;");

    // Track Headers (Left) + Timeline (Right)
    HBox mainBox = new HBox(5);

    // Headers
    GridPane headers = new GridPane();
    headers.setVgap(5);
    String[] trackNames = {
      "KICK", "SNARE", "HIHAT", "OPEN HAT", "SYNTH 1", "SYNTH 2", "SYNTH 3", "SYNTH 4"
    };

    // Empty spot for ruler alignment
    Label empty = new Label("");
    empty.setPrefHeight(20);
    headers.add(empty, 0, 0);

    for (int t = 0; t < numTracks; t++) {
      Label label = new Label(trackNames[t]);
      label.setPrefWidth(80);
      label.setPrefHeight(trackHeight - 5);
      label.setAlignment(Pos.CENTER_RIGHT);
      label.setTextFill(Color.web("#cccccc"));
      headers.add(label, 0, t + 1);
    }

    // Timeline Canvas
    VBox timelineBox = new VBox();
    HBox.setHgrow(timelineBox, Priority.ALWAYS);

    double canvasWidth = 1000;
    ruler = new ArrangerRuler(viewModel, canvasWidth, 20);

    trackCanvas = new Canvas(canvasWidth, numTracks * trackHeight);

    timelineBox.getChildren().addAll(ruler, trackCanvas);

    // Zooming
    trackCanvas.setOnScroll(
        (ScrollEvent event) -> {
          if (event.isControlDown()) {
            double delta = event.getDeltaY();
            double current = viewModel.getPixelsPerBar();
            if (delta > 0) {
              viewModel.setPixelsPerBar(current * 1.5);
            } else {
              viewModel.setPixelsPerBar(current / 1.5);
            }
            redraw();
            event.consume();
          }
        });

    trackCanvas.setOnMouseClicked(
        event -> {
          double x = event.getX();
          double y = event.getY();

          int trackIdx = (int) (y / trackHeight);
          int barIdx = (int) viewModel.pixelToBar(x);

          if (trackIdx >= 0 && trackIdx < numTracks && barIdx >= 1) {
            String clipName = "CLIP_" + (trackIdx + 1);
            viewModel.addClip(
                new org.chuck.deluge.model.ArrangerClip(trackIdx, clipName, barIdx, 2));
            redraw();
          }
        });

    ScrollPane scrollPane = new ScrollPane(timelineBox);
    scrollPane.setStyle("-fx-background: #222222; -fx-border-color: #444;");
    scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
    scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

    HBox.setHgrow(scrollPane, Priority.ALWAYS);

    mainBox.getChildren().addAll(headers, scrollPane);
    getChildren().add(mainBox);

    redraw();
  }

  public void update(int currentStep) {
    playbackController.update(currentStep);

    // Update playhead on ruler
    if (vm.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
      double bar = (currentStep / 16.0) + 1.0;
      ruler.updatePlayhead(bar);
      // Optional: redraw if playhead crosses a clip boundary to show state,
      // but usually the ruler handles just the line.
    }
  }

  private void redraw() {
    ruler.draw();

    GraphicsContext gc = trackCanvas.getGraphicsContext2D();
    double w = trackCanvas.getWidth();
    double h = trackCanvas.getHeight();

    // Clear
    gc.setFill(Color.web("#222222"));
    gc.fillRect(0, 0, w, h);

    // Draw grid lines
    double ppb = viewModel.getPixelsPerBar();
    gc.setStroke(Color.web("#333333"));
    gc.setLineWidth(1);

    int startBar = Math.max(1, (int) viewModel.pixelToBar(0));
    int endBar = (int) viewModel.pixelToBar(w);

    for (int bar = startBar; bar <= endBar + 1; bar++) {
      double x = viewModel.barToPixel(bar);
      gc.strokeLine(x, 0, x, h);
    }

    // Draw clips
    for (ArrangerClip clip : viewModel.getClips()) {
      int t = clip.trackIndex();
      String hex = colors[t % colors.length];
      double yOffset = t * trackHeight;
      ClipBlockRenderer.drawClip(gc, clip, viewModel, yOffset, trackHeight, hex);
    }
  }
}
