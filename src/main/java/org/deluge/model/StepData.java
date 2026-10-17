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
    float nudge,
    int playCondition) {
  public StepData {
    // Clamp values
    velocity = Math.max(0.0f, Math.min(1.0f, velocity));
    gate = Math.max(0.0f, Math.min(192.0f, gate));
    probability = Math.max(0.0f, Math.min(1.0f, probability));
    iterance = Math.max(0, Math.min(3, iterance));
    fill = Math.max(0.0f, Math.min(1.0f, fill));
    nudge = Math.max(0.0f, Math.min(0.99f, nudge));
    playCondition = Iterance.fromInt(playCondition).toInt();
  }

  /** Backwards compatible constructor for 8-argument calls. Defaults playCondition to 0. */
  public StepData(
      boolean active,
      float velocity,
      float gate,
      float probability,
      int pitch,
      int iterance,
      float fill,
      float nudge) {
    this(active, velocity, gate, probability, pitch, iterance, fill, nudge, 0);
  }

  /** Backwards compatible constructor for 7-argument calls. Defaults nudge and playCondition. */
  public StepData(
      boolean active,
      float velocity,
      float gate,
      float probability,
      int pitch,
      int iterance,
      float fill) {
    this(active, velocity, gate, probability, pitch, iterance, fill, 0.0f, 0);
  }

  public static final float DEFAULT_CLICK_GATE = 0.9f;

  /** Default empty step. */
  public static StepData empty() {
    return new StepData(false, 0.8f, DEFAULT_CLICK_GATE, 1.0f, 60, 0, 0.0f, 0.0f, 0);
  }

  /**
   * Convenience factory: creates a step with default iterance=0, fill=0.0, nudge=0.0,
   * playCondition=0.
   */
  public static StepData of(
      boolean active, float velocity, float gate, float probability, int pitch) {
    return new StepData(active, velocity, gate, probability, pitch, 0, 0.0f, 0.0f, 0);
  }

  /** Returns a copy of this step with {@code newPitch}, preserving all other step properties. */
  public StepData withPitch(int newPitch) {
    if (this.pitch == newPitch) return this;
    return new StepData(
        active, velocity, gate, probability, newPitch, iterance, fill, nudge, playCondition);
  }

  /**
   * C note_row.cpp:2005 — returns true if probability, iterance, or fill settings differ from their
   * defaults (non-default note parameter edit).
   */
  public boolean hasEditedNoteParameters() {
    return probability < 0.999f
        || iterance != 0
        || fill > 0.001f
        || (playCondition != 0 && playCondition != 0x0101);
  }
}
