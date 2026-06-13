package org.chuck.deluge.ui.controls;

/**
 * Models the Deluge mod-encoder "sticky" turn behaviour: once a turn direction is established, a
 * quick wiggle back in the opposite direction is ignored until a timeout elapses since the last
 * accepted turn. This prevents accidental automation when a finger knocks the encoder.
 *
 * <p>Faithful to DelugeFirmware encoders.cpp:244-292 (modEncoderInitialTurnDirection +
 * timeModEncoderLastTurned, ignoring back-tracking within ~0.5s). Time is injected so the logic is
 * deterministically testable.
 */
public final class StickyTurnFilter {

  /** Default suppression window, matching the firmware's ~half-second back-wiggle guard. */
  public static final long DEFAULT_TIMEOUT_MS = 500;

  private final long timeoutMs;
  private int establishedDir = 0; // 0 = none, +1, -1
  private long lastAcceptedMs = Long.MIN_VALUE;

  public StickyTurnFilter() {
    this(DEFAULT_TIMEOUT_MS);
  }

  public StickyTurnFilter(long timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  /**
   * Filter a raw encoder delta.
   *
   * @param rawDelta the unfiltered detent delta (negative, zero, or positive)
   * @param nowMs current time in millis
   * @return the accepted delta, or 0 if it was suppressed as a back-wiggle
   */
  public int filter(int rawDelta, long nowMs) {
    if (rawDelta == 0) {
      return 0;
    }
    int dir = Integer.signum(rawDelta);
    boolean withinTimeout = (nowMs - lastAcceptedMs) < timeoutMs;

    // A reversal that arrives within the guard window of the last accepted turn is ignored.
    // The guard keeps counting from the last accepted turn, so once it lapses the reversal sticks.
    if (establishedDir != 0 && dir != establishedDir && withinTimeout) {
      return 0;
    }

    establishedDir = dir;
    lastAcceptedMs = nowMs;
    return rawDelta;
  }

  /** Forget the established direction (e.g. on encoder release). */
  public void reset() {
    establishedDir = 0;
    lastAcceptedMs = Long.MIN_VALUE;
  }

  public int establishedDirection() {
    return establishedDir;
  }
}
