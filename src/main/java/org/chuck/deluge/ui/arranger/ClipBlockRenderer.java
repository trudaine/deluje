package org.chuck.deluge.ui.arranger;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import org.chuck.deluge.model.ArrangerClip;

/** Utility for drawing a single clip block on the timeline canvas. */
public class ClipBlockRenderer {

  public static void drawClip(
      GraphicsContext gc,
      ArrangerClip clip,
      ArrangerViewModel viewModel,
      double yOffset,
      double height,
      String colorHex) {
    double x = viewModel.barToPixel(clip.startBar());
    // width is (durationBars) * pixelsPerBar
    double w = clip.durationBars() * viewModel.getPixelsPerBar();

    // Fill block
    gc.setFill(Color.web(colorHex));
    gc.fillRect(x, yOffset + 2, w, height - 4);

    // Border
    gc.setStroke(Color.web("#ffffff"));
    gc.setLineWidth(1);
    gc.strokeRect(x, yOffset + 2, w, height - 4);

    // Label
    gc.setFill(Color.WHITE);
    gc.fillText(clip.patternId(), x + 5, yOffset + (height / 2) + 4);

    // Resize handle (right edge)
    gc.setFill(Color.web("#888888"));
    gc.fillRect(x + w - 6, yOffset + 2, 6, height - 4);
  }
}
