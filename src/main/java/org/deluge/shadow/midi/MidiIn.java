package org.deluge.shadow.midi;

import java.util.ArrayList;
import java.util.List;
import org.rtmidijava.RtMidiFactory;
import org.rtmidijava.RtMidiIn;

/**
 * MIDI input port backed by RtMidi (rtmidijava). On Linux this uses the ALSA sequencer, so USB
 * devices such as the Synthstrom Deluge enumerate and receive correctly — {@code javax.sound.midi}
 * cannot see ALSA sequencer ports, which is why the previous javax-based stub left the Deluge
 * invisible and its {@code recv} never returned any messages. Polled via {@link #recv} from the
 * MidiService receive loop.
 */
public class MidiIn {

  private RtMidiIn rt;

  /** Live input port names from RtMidi, e.g. {@code "Deluge:Deluge MIDI 1"}. */
  public static String[] getPortNames() {
    RtMidiIn probe = null;
    try {
      probe = RtMidiFactory.createDefaultIn();
      int n = probe.getPortCount();
      List<String> names = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        names.add(probe.getPortName(i));
      }
      return names.toArray(new String[0]);
    } catch (Throwable e) {
      return new String[0];
    } finally {
      if (probe != null) {
        try {
          probe.closePort();
        } catch (Throwable ignore) {
          // best effort
        }
      }
    }
  }

  public static String[] list() {
    return getPortNames();
  }

  public boolean open(int port) {
    try {
      close();
      rt = RtMidiFactory.createDefaultIn();
      // Pass SysEx through (false = don't ignore) — the Deluge SysEx protocol needs it.
      rt.ignoreTypes(false, false, false);
      rt.openPort(port, "DelugeIn");
      return true;
    } catch (Throwable e) {
      rt = null;
      return false;
    }
  }

  public boolean open(String name) {
    String[] ports = getPortNames();
    for (int i = 0; i < ports.length; i++) {
      if (ports[i].equals(name)) {
        return open(i);
      }
    }
    return false;
  }

  /**
   * Polls one buffered MIDI message into {@code msg}. Returns false when nothing is queued. Fills
   * the fields the MidiService loop / {@code MIDIMessage.fromMidiMsg} expect: data1 = status byte,
   * data2 / data3 = data bytes, and {@code data} = the full raw message (for SysEx reassembly).
   */
  public boolean recv(MidiMsg msg) {
    if (rt == null) {
      return false;
    }
    try {
      byte[] data = rt.getMessage();
      if (data == null || data.length == 0) {
        return false;
      }
      msg.data = data;
      msg.data1 = data[0] & 0xFF; // status byte
      msg.status = msg.data1;
      msg.channel = data[0] & 0x0F;
      msg.data2 = data.length > 1 ? (data[1] & 0xFF) : 0;
      msg.data3 = data.length > 2 ? (data[2] & 0xFF) : 0;
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
    if (rt != null) {
      try {
        rt.ignoreTypes(midiSysex, midiTime, midiSense);
      } catch (Throwable ignore) {
        // best effort
      }
    }
  }

  public void close() {
    if (rt != null) {
      try {
        rt.closePort();
      } catch (Throwable ignore) {
        // best effort
      }
      rt = null;
    }
  }

  public void callback(Object cb) {
    // Polling model (recv); RtMidi callback not used.
  }
}
