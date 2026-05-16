package org.chuck.deluge.midi;

/**
 * Port of the C++ midi_takeover.cpp (~240 lines). Handles MIDI knob takeover modes: JUMP, PICKUP,
 * SCALE, and VALUE_SCALE.
 *
 * <p>Takeover determines what happens when a physical controller knob is moved and the internally
 * tracked parameter value differs from the hardware position.
 *
 * <p>Modes:
 *
 * <ul>
 *   <li>JUMP — parameter jumps immediately to the hardware value
 *   <li>PICKUP — parameter only changes once the hardware value passes the internally tracked value
 *   <li>SCALE — hardware range is scaled to the internal parameter range
 *   <li>VALUE_SCALE — like SCALE but with value-based offset
 * </ul>
 */
public class MidiTakeover {

  public enum Mode {
    JUMP,
    PICKUP,
    SCALE,
    VALUE_SCALE
  }

  private Mode mode = Mode.JUMP;

  /** Tracked internal value per CC (0-127). */
  private final int[] trackedValues = new int[128];

  public MidiTakeover() {
    // Initialize with -1 (no tracked value yet)
    for (int i = 0; i < 128; i++) {
      trackedValues[i] = -1;
    }
  }

  // ===================== Configuration =====================

  public void setMode(Mode mode) {
    this.mode = mode;
  }

  public Mode getMode() {
    return mode;
  }

  // ===================== Processing =====================

  /**
   * Process a CC message through the takeover algorithm. Returns the effective CC value (0-127), or
   * -1 if the message should be ignored (PICKUP mode before hardware passes tracked value).
   *
   * <p>TODO: Full port of midi_takeover.cpp JUMP/PICKUP/SCALE/VALUE_SCALE logic.
   */
  public int process(int cc, int hardwareValue) {
    if (cc < 0 || cc > 127) return -1;

    return switch (mode) {
      case JUMP -> processJump(cc, hardwareValue);
      case PICKUP -> processPickup(cc, hardwareValue);
      case SCALE -> processScale(cc, hardwareValue);
      case VALUE_SCALE -> processValueScale(cc, hardwareValue);
    };
  }

  private int processJump(int cc, int hardwareValue) {
    trackedValues[cc] = hardwareValue;
    return hardwareValue;
  }

  private int processPickup(int cc, int hardwareValue) {
    int tracked = trackedValues[cc];
    if (tracked < 0) {
      // First move: track and pass through
      trackedValues[cc] = hardwareValue;
      return hardwareValue;
    }
    // Only pass through if hardware value passes the tracked value
    if (Math.abs(hardwareValue - tracked) > 2) {
      trackedValues[cc] = hardwareValue;
      return hardwareValue;
    }
    return -1; // Ignore
  }

  private int processScale(int cc, int hardwareValue) {
    // TODO: Scale hardware range to internal range
    trackedValues[cc] = hardwareValue;
    return hardwareValue;
  }

  private int processValueScale(int cc, int hardwareValue) {
    // TODO: Value-scale mapping
    trackedValues[cc] = hardwareValue;
    return hardwareValue;
  }

  // ===================== State Management =====================

  /** Update the tracked value for a CC without processing (set after internal parameter change). */
  public void setTrackedValue(int cc, int value) {
    if (cc >= 0 && cc < 128) {
      trackedValues[cc] = value & 0x7F;
    }
  }

  public int getTrackedValue(int cc) {
    if (cc >= 0 && cc < 128) return trackedValues[cc];
    return -1;
  }

  /** Reset all tracked values (e.g. when switching presets/patches). */
  public void reset() {
    for (int i = 0; i < 128; i++) {
      trackedValues[i] = -1;
    }
  }
}
