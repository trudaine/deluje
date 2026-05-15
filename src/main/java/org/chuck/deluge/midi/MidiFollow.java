package org.chuck.deluge.midi;

/**
 * Port of the C++ midi_follow.cpp (~1394 lines). Maps incoming MIDI messages (CC, Note, Pitch Bend,
 * Aftertouch) to Sound/global parameters.
 *
 * <p>Phase A (initial): CC → global float parameter mapping, device definition integration, note
 * → Sound parameter routing.
 *
 * <p>Phase B (extended): Feedback (send current param values back as CC), pitch bend / aftertouch
 * → parameter routing, per-clip MIDI Follow routing.
 */
public class MidiFollow {

  public MidiFollow() {}

  // ===================== CC Handling =====================

  /**
   * Handle an incoming Control Change message. Routes to the appropriate parameter based on the
   * current device definition and follow mode configuration.
   *
   * <p>TODO: Full port of midi_follow.cpp CC→param mapping.
   */
  public void handleCC(MIDIMessage msg) {
    // CC routing is handled by MidiInputRouter for now.
    // This class will own it once Phase A is complete.
  }

  // ===================== Note Handling =====================

  /**
   * Handle an incoming Note On/Off for MIDI Follow param mapping.
   *
   * <p>TODO: Note → Sound parameter routing.
   */
  public void handleNote(MIDIMessage msg) {
    // Reserved for Phase A/B
  }

  // ===================== Pitch Bend =====================

  /**
   * Handle an incoming Pitch Bend for MIDI Follow param mapping.
   *
   * <p>TODO: Pitch bend → parameter routing.
   */
  public void handlePitchBend(MIDIMessage msg) {
    // Reserved for Phase B
  }

  // ===================== Aftertouch =====================

  /**
   * Handle incoming Channel Aftertouch for MIDI Follow param mapping.
   *
   * <p>TODO: Aftertouch → parameter routing.
   */
  public void handleChannelAftertouch(MIDIMessage msg) {
    // Reserved for Phase B
  }

  /**
   * Handle incoming Polyphonic Aftertouch for MIDI Follow param mapping.
   *
   * <p>TODO: Poly aftertouch → parameter routing.
   */
  public void handlePolyAftertouch(MIDIMessage msg) {
    // Reserved for Phase B
  }
}
