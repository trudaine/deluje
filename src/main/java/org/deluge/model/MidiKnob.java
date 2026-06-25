package org.deluge.model;

/**
 * Represents a MIDI controller mapping (a learned MIDI knob or CC input mapped to a parameter).
 * Matches the native C++ Deluge firmware's MIDIKnob structure.
 */
public record MidiKnob(
    int channel, int ccNumber, boolean relative, String controlsParam, String patchSource) {
  public static MidiKnob empty() {
    return new MidiKnob(1, 0, false, "NONE", "NONE");
  }
}
