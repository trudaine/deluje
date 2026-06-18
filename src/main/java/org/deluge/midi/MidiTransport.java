package org.deluge.midi;

import org.chuck.midi.MidiMsg;

/**
 * Abstraction over a MIDI transport connection. The MidiEngine uses this to send messages without
 * depending on a specific transport implementation (rtmidijava, virtual loopback, etc.).
 */
public interface MidiTransport {

  /** Human-readable port name (e.g. "USB MIDI Interface"). */
  String getPortName();

  /** Send a MIDI message through this transport. Returns true on success. */
  boolean sendMessage(MidiMsg msg);

  /** Whether this transport can send MIDI messages. */
  boolean isOutput();

  /** Whether this transport can receive MIDI messages. */
  boolean isInput();

  /** Close the transport and release resources. */
  void close();
}
