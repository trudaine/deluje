package org.chuck.deluge.model;

/** Represents a single step in a track's sequence pattern. */
public record StepData(boolean active, float velocity, float gate, float probability, int pitch) {
  public StepData {
    // Clamp values
    velocity = Math.max(0.0f, Math.min(1.0f, velocity));
    gate = Math.max(0.0f, Math.min(1.0f, gate));
    probability = Math.max(0.0f, Math.min(1.0f, probability));
  }

  /** Default empty step. */
  public static StepData empty() {
    return new StepData(false, 0.8f, 0.5f, 1.0f, 60);
  }
}
