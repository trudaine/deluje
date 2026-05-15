package org.chuck.deluge.midi;

import org.chuck.midi.ChuckMidiOutNative;
import org.chuck.midi.MidiMsg;

/**
 * MidiTransport implementation wrapping the existing rtmidijava native output. Uses
 * ChuckMidiOutNative (FFM bindings) — no javax.sound.midi.
 */
public class RtMidiTransport implements MidiTransport {

  private final ChuckMidiOutNative midiOut;
  private final String portName;

  public RtMidiTransport(int portNumber) {
    this.midiOut = new ChuckMidiOutNative();
    boolean ok = midiOut.open(portNumber);
    this.portName = ok ? String.valueOf(portNumber) : "unopened";
  }

  public RtMidiTransport(int portNumber, org.rtmidijava.RtMidi.Api api) {
    this.midiOut = new ChuckMidiOutNative();
    boolean ok = midiOut.open(portNumber, api);
    this.portName = ok ? String.valueOf(portNumber) : "unopened";
  }

  /** Create from an already-opened ChuckMidiOutNative instance. */
  public RtMidiTransport(ChuckMidiOutNative midiOut, String portName) {
    this.midiOut = midiOut;
    this.portName = portName;
  }

  @Override
  public String getPortName() {
    return portName;
  }

  @Override
  public boolean sendMessage(MidiMsg msg) {
    if (midiOut == null) return false;
    midiOut.send(msg);
    return true;
  }

  @Override
  public boolean isOutput() {
    return true;
  }

  @Override
  public boolean isInput() {
    return false;
  }

  @Override
  public void close() {
    if (midiOut != null) midiOut.close();
  }
}
