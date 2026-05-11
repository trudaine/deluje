package org.chuck.deluge.model;

/** Represents a single step in a track's sequence pattern. */
public record StepData(boolean active, float velocity, float gate, float probability, int pitch,
                       int iterance, float fill) {
  public StepData {
    // Clamp values
    velocity = Math.max(0.0f, Math.min(1.0f, velocity));
    gate = Math.max(0.0f, Math.min(1.0f, gate));
    probability = Math.max(0.0f, Math.min(1.0f, probability));
    iterance = Math.max(0, Math.min(3, iterance));
    fill = Math.max(0.0f, Math.min(1.0f, fill));
  }

  /** Default empty step. */
  public static StepData empty() {
    return new StepData(false, 0.8f, 0.5f, 1.0f, 60, 0, 0.0f);
  }

  /** Convenience factory: creates a step with default iterance=0, fill=0.0. */
  public static StepData of(boolean active, float velocity, float gate, float probability, int pitch) {
    return new StepData(active, velocity, gate, probability, pitch, 0, 0.0f);
  }
}
