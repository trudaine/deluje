package org.deluge.model;

/**
 * High-fidelity representation of a clip placement instance on the Arranger timeline. Maps straight
 * to the standard firmware's ClipInstance bounds and tick positions.
 */
public record ArrangerClip(int trackIndex, ClipModel clip, int startTicks, int durationTicks) {
  public ArrangerClip {
    startTicks = Math.max(0, startTicks);
    durationTicks = Math.max(1, durationTicks);
  }

  /** Convenience getter: returns start position in standard bars (assuming 96 ticks per bar). */
  public double getStartBar() {
    return startTicks / 96.0;
  }

  /** Convenience getter: returns duration in standard bars (assuming 96 ticks per bar). */
  public double getDurationBars() {
    return durationTicks / 96.0;
  }
}
