package org.chuck.deluge.midi;

/**
 * Port of the C++ midi_takeover.cpp (~200 lines). Handles MIDI knob takeover modes: JUMP, PICKUP,
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

  /** Tracked last known physical hardware position history per CC (0-127). */
  private final int[] previousHardwareValues = new int[128];

  /** Tracked virtual value overrides. */
  private final int[] trackedValues = new int[128];

  public MidiTakeover() {
    reset();
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
   * Process a CC message through the takeover algorithm.
   *
   * @param cc CC number (0-127)
   * @param hardwareValue Incoming CC value from hardware (0-127)
   * @param currentVirtualValue Current virtual parameter level (0-127)
   * @return The effective CC value to apply (0-127)
   */
  public int process(int cc, int hardwareValue, int currentVirtualValue) {
    if (cc < 0 || cc > 127) return currentVirtualValue;

    return switch (mode) {
      case JUMP -> processJump(cc, hardwareValue);
      case PICKUP -> processPickup(cc, hardwareValue, currentVirtualValue);
      case SCALE, VALUE_SCALE -> processScale(cc, hardwareValue, currentVirtualValue);
    };
  }

  private int processJump(int cc, int hardwareValue) {
    previousHardwareValues[cc] = hardwareValue;
    trackedValues[cc] = hardwareValue;
    return hardwareValue;
  }

  private int processPickup(int cc, int hardwareValue, int currentVirtualValue) {
    int previous = previousHardwareValues[cc];
    if (previous < 0) {
      previous = hardwareValue;
      previousHardwareValues[cc] = previous;
    }

    // Check if hardware path has crossed/picked up the current virtual value
    if ((previous <= currentVirtualValue && hardwareValue >= currentVirtualValue)
        || (previous >= currentVirtualValue && hardwareValue <= currentVirtualValue)) {
      previousHardwareValues[cc] = hardwareValue;
      trackedValues[cc] = hardwareValue;
      return hardwareValue;
    }

    previousHardwareValues[cc] = hardwareValue;
    // Return -1 to indicate the value is blocked/unchanged
    return -1;
  }

  private int processScale(int cc, int hardwareValue, int currentVirtualValue) {
    int previous = previousHardwareValues[cc];
    if (previous < 0) {
      previous = hardwareValue;
      previousHardwareValues[cc] = previous;
      return -1;
    }

    // Check pick-up first: if we cross the virtual value, switch back to absolute values!
    if ((previous <= currentVirtualValue && hardwareValue >= currentVirtualValue)
        || (previous >= currentVirtualValue && hardwareValue <= currentVirtualValue)) {
      previousHardwareValues[cc] = hardwareValue;
      trackedValues[cc] = hardwareValue;
      return hardwareValue;
    }

    int hardwareChange = hardwareValue - previous;
    if (hardwareChange == 0) {
      previousHardwareValues[cc] = hardwareValue;
      return -1;
    }

    double newVirtual = currentVirtualValue;

    // Turning right/increasing: scale relative to remaining positive runway
    if (hardwareChange > 0) {
      int hardwareMaxDelta = 127 - hardwareValue;
      int virtualMaxDelta = 127 - currentVirtualValue;
      if (hardwareMaxDelta > 0) {
        double changePercentage = (double) hardwareChange / (hardwareMaxDelta + hardwareChange);
        newVirtual = currentVirtualValue + (virtualMaxDelta * changePercentage);
        if (newVirtual < currentVirtualValue) {
          newVirtual = currentVirtualValue;
        }
      } else {
        newVirtual = 127;
      }
    }
    // Turning left/decreasing: scale relative to remaining negative runway
    else {
      int hardwareMinDelta = hardwareValue;
      int virtualMinDelta = currentVirtualValue;
      if (hardwareMinDelta > 0) {
        double changePercentage = (double) hardwareChange / (hardwareMinDelta - hardwareChange);
        newVirtual = currentVirtualValue + (virtualMinDelta * changePercentage);
        if (newVirtual > currentVirtualValue) {
          newVirtual = currentVirtualValue;
        }
      } else {
        newVirtual = 0;
      }
    }

    int finalValue = (int) Math.round(newVirtual);
    finalValue = Math.clamp(finalValue, 0, 127);

    if (finalValue == currentVirtualValue) {
      previousHardwareValues[cc] = hardwareValue;
      return -1;
    }

    previousHardwareValues[cc] = hardwareValue;
    trackedValues[cc] = finalValue;
    return finalValue;
  }

  // ===================== State Management =====================

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
      previousHardwareValues[i] = -1;
      trackedValues[i] = -1;
    }
  }
}
