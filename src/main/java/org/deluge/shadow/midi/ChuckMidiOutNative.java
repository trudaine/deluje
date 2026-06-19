package org.deluge.shadow.midi;

/**
 * A lightweight shadow representing the native JNI MIDI Out wrapper. Implemented in pure Java to
 * completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public class ChuckMidiOutNative {

  public boolean open(int port) {
    return true;
  }

  public boolean open(int port, org.rtmidijava.RtMidi.Api api) {
    return true;
  }

  public void send(MidiMsg msg) {
    // Stub
  }

  public void close() {
    // Stub
  }
}
