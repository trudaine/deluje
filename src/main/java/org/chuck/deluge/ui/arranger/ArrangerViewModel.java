package org.chuck.deluge.ui.arranger;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.model.ArrangerClip;

/** Maps bars to pixels for the Arranger timeline and holds the list of clips. */
public class ArrangerViewModel {
  private final List<ArrangerClip> clips = new ArrayList<>();
  private double pixelsPerBar = 32.0;

  public ArrangerViewModel() {}

  public List<ArrangerClip> getClips() {
    return clips;
  }

  public void addClip(ArrangerClip clip) {
    clips.add(clip);
  }

  public void removeClip(ArrangerClip clip) {
    clips.remove(clip);
  }

  public double getPixelsPerBar() {
    return pixelsPerBar;
  }

  public void setPixelsPerBar(double pixelsPerBar) {
    this.pixelsPerBar = Math.max(8.0, Math.min(128.0, pixelsPerBar));
  }

  public double barToPixel(double bar) {
    // bar 1 is at pixel 0
    return (bar - 1.0) * pixelsPerBar;
  }

  public double pixelToBar(double pixel) {
    return (pixel / pixelsPerBar) + 1.0;
  }
}
