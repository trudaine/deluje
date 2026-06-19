package org.deluge.shadow.midi;

/**
 * A lightweight, functional shadow representing a virtual MIDI Output Port. Uses standard JDK
 * javax.sound.midi to scan physical MIDI ports, completely independent of C++ RtMidi.
 */
public class MidiOut {

  public static String[] getPortNames() {
    try {
      var infos = javax.sound.midi.MidiSystem.getMidiDeviceInfo();
      String[] names = new String[infos.length];
      for (int i = 0; i < infos.length; i++) {
        names[i] = infos[i].getName();
      }
      return names;
    } catch (Exception e) {
      return new String[0];
    }
  }

  public static String[] list() {
    return getPortNames();
  }

  public boolean open(int port) {
    return true;
  }

  public boolean open(String name) {
    return true;
  }

  public void close() {
    // Stub
  }

  public void send(MidiMsg msg) {
    // Stub
  }

  public void controlChange(int chan, int cc, int val) {
    // Stub for sending CC messages to controllers
  }
}
