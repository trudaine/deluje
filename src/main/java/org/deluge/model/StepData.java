package org.deluge.model;

/** Represents a single step in a track's sequence pattern. */
public record StepData(
    boolean active,
    float velocity,
    float gate,
    float probability,
    int pitch,
    int iterance,
    float fill,
    float nudge) {
  public StepData {
    // Clamp values
    velocity = Math.max(0.0f, Math.min(1.0f, velocity));
    gate = Math.max(0.0f, Math.min(192.0f, gate));
    probability = Math.max(0.0f, Math.min(1.0f, probability));
    iterance = Math.max(0, Math.min(3, iterance));
    fill = Math.max(0.0f, Math.min(1.0f, fill));
    nudge = Math.max(0.0f, Math.min(0.99f, nudge));
  }

  /** Backwards compatible constructor for 7-argument calls. Defaults nudge to 0.0f. */
  public StepData(
      boolean active,
      float velocity,
      float gate,
      float probability,
      int pitch,
      int iterance,
      float fill) {
    this(active, velocity, gate, probability, pitch, iterance, fill, 0.0f);
  }

  public static final float DEFAULT_CLICK_GATE = 0.9f;

  /** Default empty step. */
  public static StepData empty() {
    return new StepData(false, 0.8f, DEFAULT_CLICK_GATE, 1.0f, 60, 0, 0.0f, 0.0f);
  }

  /** Convenience factory: creates a step with default iterance=0, fill=0.0, nudge=0.0. */
  public static StepData of(
      boolean active, float velocity, float gate, float probability, int pitch) {
    return new StepData(active, velocity, gate, probability, pitch, 0, 0.0f, 0.0f);
  }
}
