package org.chuck.deluge.ui.arranger;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

/** Draws the bar timeline and playhead at the top of the Arranger view. */
public class ArrangerRuler extends Canvas {
  private final ArrangerViewModel viewModel;
  private double playheadBar = 1.0;

  public ArrangerRuler(ArrangerViewModel viewModel, double width, double height) {
    super(width, height);
    this.viewModel = viewModel;

    // Default mock behavior - a full implementation would bind width to parent width
    draw();
  }

  public void updatePlayhead(double bar) {
    this.playheadBar = bar;
    draw();
  }

  public void draw() {
    GraphicsContext gc = getGraphicsContext2D();
    double w = getWidth();
    double h = getHeight();

    // Clear background
    gc.setFill(Color.web("#2a2a2a"));
    gc.fillRect(0, 0, w, h);

    // Draw bar lines
    double ppb = viewModel.getPixelsPerBar();
    gc.setStroke(Color.web("#555555"));
    gc.setLineWidth(1);

    int startBar = Math.max(1, (int) viewModel.pixelToBar(0));
    int endBar = (int) viewModel.pixelToBar(w);

    for (int bar = startBar; bar <= endBar + 1; bar++) {
      double x = viewModel.barToPixel(bar);

      // Draw major bar line
      gc.strokeLine(x, 0, x, h);

      // Draw bar number
      gc.setFill(Color.web("#888888"));
      gc.fillText(String.valueOf(bar), x + 2, 12);

      // Draw beat ticks (quarter notes)
      for (int i = 1; i < 4; i++) {
        double tickX = x + (i * ppb / 4.0);
        gc.strokeLine(tickX, h - 5, tickX, h);
      }
    }

    // Draw Playhead
    gc.setStroke(Color.web("#FF3333"));
    gc.setLineWidth(2);
    double playheadX = viewModel.barToPixel(playheadBar);
    gc.strokeLine(playheadX, 0, playheadX, h);

    // Playhead arrow/triangle
    gc.setFill(Color.web("#FF3333"));
    gc.fillPolygon(
        new double[] {playheadX - 4, playheadX + 4, playheadX}, new double[] {0, 0, 5}, 3);
  }
}
