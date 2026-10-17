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
    int playCondition,
    int fillMode) {
  public StepData {
    // Clamp values
    velocity = Math.max(0.0f, Math.min(1.0f, velocity));
    gate = Math.max(0.0f, Math.min(192.0f, gate));
    probability = Math.max(0.0f, Math.min(1.0f, probability));
    iterance = Math.max(0, Math.min(3, iterance));
    fill = Math.max(0.0f, Math.min(1.0f, fill));
    nudge = Math.max(0.0f, Math.min(0.99f, nudge));
    playCondition = Iterance.fromInt(playCondition).toInt();
    // C definitions_cxx.hpp:727 — FillMode::OFF=0, FillMode::NOT_FILL=1, FillMode::FILL=2.
    fillMode = Math.max(0, Math.min(2, fillMode));
    if (fillMode == 0 && fill > 0.001f) {
      fillMode = 2;
    } else if (fillMode == 1) {
      fill = 0.0f;
    } else if (fillMode == 2 && fill <= 0.001f) {
      fill = 1.0f;
    }
  }

  /** Backwards compatible constructor for 9-argument calls. Derives fillMode from fill. */
  public StepData(
      boolean active,
      float velocity,
      float gate,
      float probability,
      int pitch,
      int iterance,
      float fill,
      float nudge,
      int playCondition) {
    this(
        active,
        velocity,
        gate,
        probability,
        pitch,
        iterance,
        fill,
        nudge,
        playCondition,
        fill > 0.001f ? 2 : 0);
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
    this(
        active,
        velocity,
        gate,
        probability,
        pitch,
        iterance,
        fill,
        nudge,
        0,
        fill > 0.001f ? 2 : 0);
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
    this(
        active, velocity, gate, probability, pitch, iterance, fill, 0.0f, 0, fill > 0.001f ? 2 : 0);
  }

  public static final float DEFAULT_CLICK_GATE = 0.9f;

  /** Default empty step. */
  public static StepData empty() {
    return new StepData(false, 0.8f, DEFAULT_CLICK_GATE, 1.0f, 60, 0, 0.0f, 0.0f, 0, 0);
  }

  /**
   * Convenience factory: creates a step with default iterance=0, fill=0.0, nudge=0.0,
   * playCondition=0, fillMode=0.
   */
  public static StepData of(
      boolean active, float velocity, float gate, float probability, int pitch) {
    return new StepData(active, velocity, gate, probability, pitch, 0, 0.0f, 0.0f, 0, 0);
  }

  /** Returns a copy of this step with {@code newPitch}, preserving all other step properties. */
  public StepData withPitch(int newPitch) {
    if (this.pitch == newPitch) return this;
    return new StepData(
        active,
        velocity,
        gate,
        probability,
        newPitch,
        iterance,
        fill,
        nudge,
        playCondition,
        fillMode);
  }

  /** Returns a copy of this step with {@code newVelocity}, preserving all other step properties. */
  public StepData withVelocity(float newVelocity) {
    return new StepData(
        active,
        newVelocity,
        gate,
        probability,
        pitch,
        iterance,
        fill,
        nudge,
        playCondition,
        fillMode);
  }

  /** Returns a copy of this step with {@code newGate}, preserving all other step properties. */
  public StepData withGate(float newGate) {
    return new StepData(
        active,
        velocity,
        newGate,
        probability,
        pitch,
        iterance,
        fill,
        nudge,
        playCondition,
        fillMode);
  }

  /**
   * Returns a copy of this step with {@code newProbability}, preserving all other step properties.
   */
  public StepData withProbability(float newProbability) {
    return new StepData(
        active,
        velocity,
        gate,
        newProbability,
        pitch,
        iterance,
        fill,
        nudge,
        playCondition,
        fillMode);
  }

  /** Returns a copy of this step with {@code newFill}, updating fillMode (0 or 2) accordingly. */
  public StepData withFill(float newFill) {
    int newFillMode = newFill > 0.001f ? 2 : 0;
    return new StepData(
        active,
        velocity,
        gate,
        probability,
        pitch,
        iterance,
        newFill,
        nudge,
        playCondition,
        newFillMode);
  }

  /** Returns a copy of this step with {@code newFillMode} (0=OFF, 1=NOT_FILL, 2=FILL). */
  public StepData withFillMode(int newFillMode) {
    float newFill = newFillMode == 2 ? (fill > 0.001f ? fill : 1.0f) : 0.0f;
    return new StepData(
        active,
        velocity,
        gate,
        probability,
        pitch,
        iterance,
        newFill,
        nudge,
        playCondition,
        newFillMode);
  }

  /** Returns a copy of this step with {@code newNudge}, preserving all other step properties. */
  public StepData withNudge(float newNudge) {
    return new StepData(
        active,
        velocity,
        gate,
        probability,
        pitch,
        iterance,
        fill,
        newNudge,
        playCondition,
        fillMode);
  }

  /** Returns a copy of this step with {@code newActive}, preserving all other step properties. */
  public StepData withActive(boolean newActive) {
    return new StepData(
        newActive,
        velocity,
        gate,
        probability,
        pitch,
        iterance,
        fill,
        nudge,
        playCondition,
        fillMode);
  }

  /**
   * C note_row.cpp:2005 — returns true if probability, iterance, or fill settings differ from their
   * defaults (non-default note parameter edit).
   */
  public boolean hasEditedNoteParameters() {
    return probability < 0.999f
        || iterance != 0
        || fill > 0.001f
        || fillMode != 0
        || (playCondition != 0 && playCondition != 0x0101);
  }
}
