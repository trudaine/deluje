package org.deluge.shadow.midi;

/**
 * A lightweight, functional shadow representing a virtual MIDI Input Port. Uses standard JDK
 * javax.sound.midi to scan physical MIDI ports, completely independent of C++ RtMidi.
 */
public class MidiIn {

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

  public void callback(Object cb) {
    // Stub
  }

  public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
    // Stub
  }

  public boolean recv(MidiMsg msg) {
    return false; // No incoming MIDI messages in pure Java mode
  }
}
