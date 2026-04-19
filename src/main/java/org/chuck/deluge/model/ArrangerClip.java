package org.chuck.deluge.model;

/** Represents a clip placed on the Arranger timeline. */
public record ArrangerClip(int trackIndex, String patternId, int startBar, int durationBars) {
  public ArrangerClip {
    startBar = Math.max(1, startBar);
    durationBars = Math.max(1, durationBars);
  }
}
