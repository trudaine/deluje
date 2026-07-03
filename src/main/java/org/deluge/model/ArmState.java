package org.deluge.model;

/**
 * Whether a session clip is armed to change state at the next launch event, and how — a faithful
 * port of the C {@code ArmState} enum (definitions_cxx.hpp:655). Used by the session launch
 * scheduler: a clip with a non-{@link #OFF} arm state is waiting for {@code
 * launchEventAtSwungTickCount} to fire, at which point it starts/stops/solos and returns to {@link
 * #OFF}.
 */
public enum ArmState {
  /** Not armed. */
  OFF,
  /** Arming to stop or start normally, or to stop soloing. */
  ON_NORMAL,
  /** Arming to start soloing. */
  ON_TO_SOLO,
  /** Arming to start recording. */
  ON_TO_RECORD;
}
