package org.deluge.midi;

import org.deluge.BridgeContract;

/**
 * Sends MIDI feedback (CC messages) to an external hardware controller — e.g., LED rings, motor
 * faders, or parameter displays on devices like the Arturia KeyLab or Novation SL.
 *
 * <p>Uses a MidiOut device opened at startup. All methods are no-ops when no output port is
 * configured or when the port cannot be opened — the service degrades gracefully.
 *
 * <p>Feedback messages are:
 *
 * <ul>
 *   <li>{@link #notifyTrackChanged(int)} — sends the new track number as a CC so the controller can
 *       update its track/group display
 *   <li>{@link #notifyParamChanged(String, float)} — looks up the param name in the current device
 *       definition and sends its current value as a CC
 *   <li>{@link #sendCc(int, int, int)} — raw CC output (channel, CC, value)
 * </ul>
 */
public class MidiFeedbackService {

  private final BridgeContract bridge;

  private org.deluge.shadow.midi.MidiOut midiOut;
  private boolean available = false;

  /** Optional device definition used for paramName → CC lookups. */
  private MidiDeviceDefinition deviceDefinition;

  /** Track-change CC number (default 3 = User-defined controller; many controllers use CC#3). */
  private int trackChangeCc = 3;

  public MidiFeedbackService(final BridgeContract bridge) {
    this.bridge = bridge;
  }

  /**
   * Attempt to open a MIDI output port. Call once during app startup; safe to call again to switch
   * ports.
   *
   * @param portName exact name as returned by {@code MidiOut.list()}, or empty/null to skip
   */
  public void open(String portName) {
    close();
    if (portName == null || portName.isEmpty()) return;
    try {
      midiOut = new org.deluge.shadow.midi.MidiOut();
      String[] ports = org.deluge.shadow.midi.MidiOut.list();
      int portIdx = -1;
      for (int i = 0; i < ports.length; i++) {
        if (ports[i].equals(portName)) {
          portIdx = i;
          break;
        }
      }
      if (portIdx >= 0) {
        midiOut.open(portIdx);
        available = true;
        System.out.println("MIDI FB: Opened output port: " + portName);
      } else {
        System.err.println("MIDI FB: Port not found: " + portName);
      }
    } catch (Exception e) {
      System.err.println("MIDI FB: Failed to open port: " + portName + " - " + e.getMessage());
    }
  }

  /** Close the output port. */
  public void close() {
    if (midiOut != null) {
      try {
        midiOut.close();
      } catch (Exception ignored) {
      }
      midiOut = null;
    }
    available = false;
  }

  /** Set the device definition used for paramName → CC lookups. */
  public void setDeviceDefinition(MidiDeviceDefinition def) {
    this.deviceDefinition = def;
  }

  /** Set the CC number used for track-change notifications (default 3). */
  public void setTrackChangeCc(int cc) {
    this.trackChangeCc = cc;
  }

  /** Returns true when an output port is open and ready. */
  public boolean isAvailable() {
    return available;
  }

  /**
   * Send a raw CC message on the given MIDI channel.
   *
   * @param channel 0-indexed MIDI channel (0-15)
   * @param cc CC number (0-127)
   * @param value 0-127
   */
  public void sendCc(int channel, int cc, int value) {
    if (!available || midiOut == null) return;
    try {
      midiOut.controlChange(channel, cc, value);
    } catch (Exception e) {
      System.err.println("MIDI FB: sendCc failed: " + e.getMessage());
    }
  }

  /**
   * Notify the controller that the active track has changed. Sends the track index on the
   * configured {@code trackChangeCc}.
   */
  public void notifyTrackChanged(int newTrackIndex) {
    sendCc(0, trackChangeCc, Math.max(0, Math.min(127, newTrackIndex)));
    bridge.setFollowTrackChanged(newTrackIndex);
  }

  /**
   * Notify the controller that a parameter value has changed. Looks up the param name in the
   * current device definition and sends the current value as a CC.
   */
  public void notifyParamChanged(String paramName, float normalizedValue) {
    if (deviceDefinition == null) return;
    MidiDeviceDefinition.CcMapping mapping = null;
    for (MidiDeviceDefinition.CcMapping m : deviceDefinition.getCcMappings()) {
      if (m.paramName().equals(paramName)) {
        mapping = m;
        break;
      }
    }
    if (mapping != null) {
      int ccVal = Math.round(normalizedValue * 127);
      sendCc(0, mapping.cc(), ccVal);
    }
  }
}
