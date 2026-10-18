package org.deluge.midi;

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

  /** C midi_takeover.cpp:58-61 — CC 0..126 → knob -64..62, CC 127 → +64 (knob max). */
  static int ccToKnobPos(int cc) {
    return cc < 127 ? cc - 64 : 64;
  }

  /** Inverse of {@link #ccToKnobPos}: knob +64 (and +63) map to CC 127. */
  static int knobPosToCc(int knobPos) {
    return knobPos >= 63 ? 127 : Math.max(0, knobPos + 64);
  }

  /** C++ std::round: halves round away from zero (Java Math.round rounds -2.5 to -2). */
  private static int roundHalfAwayFromZero(float v) {
    return (int) (v < 0 ? -Math.floor(-v + 0.5f) : Math.floor(v + 0.5f));
  }

  /**
   * C midi_takeover.cpp:94-160 — pickup check, then value scaling, in Deluge knob positions
   * (-64..+64, knobMaxPos 64, knobMinPos -64) with float math, std::round and clamp(-64, 64). The
   * Java API speaks CC values (0..127), so both ends convert with {@link #ccToKnobPos}: the C maps
   * CC 127 to +64, so the top of the CC range is the knob maximum, not +63.
   */
  private int processScale(int cc, int hardwareValue, int currentVirtualValue) {
    int previousCc = previousHardwareValues[cc];
    if (previousCc < 0) {
      previousCc = hardwareValue; // C getPreviousKnobPos: first turn starts from the current pos
    }
    previousHardwareValues[cc] = hardwareValue; // C savePreviousKnobPos

    int midiKnobPos = ccToKnobPos(hardwareValue);
    int previousKnobPosition = ccToKnobPos(previousCc);
    int knobPos = ccToKnobPos(currentVirtualValue);

    // C :99-103 — met or passed the Deluge knob position in either direction: picked up.
    if ((previousKnobPosition <= knobPos && midiKnobPos >= knobPos)
        || (previousKnobPosition >= knobPos && midiKnobPos <= knobPos)) {
      trackedValues[cc] = hardwareValue;
      return hardwareValue;
    }

    final int knobMaxPos = 64; // C :108
    final int knobMinPos = -64; // C :109
    int delugeKnobMaxPosDelta = knobMaxPos - knobPos; // C :112
    int delugeKnobMinPosDelta = knobPos - knobMinPos; // C :113
    int midiKnobMaxPosDelta = knobMaxPos - midiKnobPos; // C :117
    int midiKnobMinPosDelta = midiKnobPos - knobMinPos; // C :118
    int midiKnobPosChange = midiKnobPos - previousKnobPosition; // C :122

    int newKnobPos = knobPos;
    if (midiKnobPosChange != 0) { // C :125
      float newKnobPosFloat = newKnobPos;
      float midiKnobPosChangeFloat = midiKnobPosChange;
      if (midiKnobPosChange > 0) { // C :132-145
        if (midiKnobMaxPosDelta != 0) {
          float pct = midiKnobPosChangeFloat / midiKnobMaxPosDelta;
          newKnobPosFloat = newKnobPosFloat + (delugeKnobMaxPosDelta * pct);
          if (newKnobPosFloat < newKnobPos) {
            newKnobPosFloat = newKnobPos;
          }
        }
      } else { // C :147-158
        if (midiKnobMinPosDelta != 0) {
          float pct = midiKnobPosChangeFloat / midiKnobMinPosDelta;
          newKnobPosFloat = newKnobPosFloat + (delugeKnobMinPosDelta * pct);
          if (newKnobPosFloat > newKnobPos) {
            newKnobPosFloat = newKnobPos;
          }
        }
      }
      newKnobPos = Math.clamp(roundHalfAwayFromZero(newKnobPosFloat), -64, 64); // C :159-161
    }

    int finalValue = knobPosToCc(newKnobPos);
    if (newKnobPos == knobPos || finalValue == currentVirtualValue) {
      return -1; // unchanged
    }
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
