package org.deluge.playback;

/**
 * Pure launch-quantization math, ported from {@code Session::investigateSyncedLaunch} and {@code
 * Session::armAllClipsToStop} (playback/mode/session.cpp). Given already-playing clip lengths and a
 * position, it computes the quantization window and how many ticks remain until the next launch
 * boundary — with no playback, audio, or song state, so every case is unit-testable.
 *
 * <p>Phase 3 assembles these primitives with the live transport state (bar length, input tick
 * scale, which clip is playing) to decide the {@link LaunchStatus} and schedule the launch tick.
 */
final class LaunchQuantizer {
  private LaunchQuantizer() {}

  /** Faithful port of the C {@code LaunchStatus} used by {@code investigateSyncedLaunch}. */
  enum LaunchStatus {
    /** Internal clock, nothing to sync to — playback can just (re)start immediately. */
    NOTHING_TO_SYNC_TO,
    /** Launch is quantized to a window; fire at the next boundary. */
    LAUNCH_USING_QUANTIZATION,
    /** Another clip is already armed; launch along with it at the same tick. */
    LAUNCH_ALONG_WITH_EXISTING_LAUNCHING
  }

  /**
   * Case where a clip is already playing: quantize to that clip's loop length, or — if a shorter
   * "longest starting" clip divides the loop evenly and subdivision is allowed — to that shorter
   * length instead. Mirrors investigateSyncedLaunch's else-branch.
   *
   * @param waitForClipLoopLength loop length (ticks) of the clip being synced to
   * @param longestStartingClipLength loop length of the longest clip being started now
   * @param allowSubdivided whether sub-loop-length quantization is permitted
   */
  static long syncedQuantization(
      long waitForClipLoopLength, long longestStartingClipLength, boolean allowSubdivided) {
    if (allowSubdivided
        && longestStartingClipLength > 0
        && longestStartingClipLength < waitForClipLoopLength
        && (waitForClipLoopLength % longestStartingClipLength) == 0) {
      return longestStartingClipLength;
    }
    return waitForClipLoopLength;
  }

  /**
   * External-clock quantization: the song's input tick scale, doubled until it is at least three
   * beats long (or one bar, if a bar is shorter than 2 ticks). Mirrors the external-clock branch of
   * investigateSyncedLaunch.
   *
   * @param inputTickScale the song's sync scale in ticks
   * @param oneBar the bar length in ticks
   */
  static long magnifyToThreeBeats(long inputTickScale, long oneBar) {
    long threeBeats = (oneBar >= 2) ? (oneBar * 3) >> 2 : oneBar;
    long q = inputTickScale;
    while (q < threeBeats) {
      q <<= 1;
    }
    return q;
  }

  /**
   * Ticks from {@code posWithinQuantization} until the next launch boundary — {@code quantization -
   * (pos % quantization)} (session.cpp armAllClipsToStop). The result is always in {@code (0,
   * quantization]}: sitting exactly on a boundary schedules a full window ahead, not immediately.
   */
  static long ticksTilLaunch(long posWithinQuantization, long quantization) {
    if (quantization <= 0) {
      return 0;
    }
    return quantization - Math.floorMod(posWithinQuantization, quantization);
  }
}
