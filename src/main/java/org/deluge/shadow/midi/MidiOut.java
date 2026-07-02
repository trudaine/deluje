package org.deluge.shadow.midi;

import java.util.ArrayList;
import java.util.List;
import org.rtmidijava.RtMidiFactory;
import org.rtmidijava.RtMidiOut;

/**
 * MIDI output port backed by RtMidi (rtmidijava) — the counterpart to {@link MidiIn}. Uses the ALSA
 * sequencer on Linux so messages (notes, CC, and Deluge SysEx) actually reach the hardware; the old
 * javax stub enumerated blindly and dropped everything on the floor.
 */
public class MidiOut {

  private RtMidiOut rt;

  /** Live output port names from RtMidi, e.g. {@code "Deluge:Deluge MIDI 1"}. */
  public static String[] getPortNames() {
    RtMidiOut probe = null;
    try {
      probe = RtMidiFactory.createDefaultOut();
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
      rt = RtMidiFactory.createDefaultOut();
      rt.openPort(port, "DelugeOut");
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

  public void send(MidiMsg msg) {
    if (rt == null || msg == null) {
      return;
    }
    try {
      byte[] data = msg.getData();
      if (data == null || data.length == 0) {
        // Build a channel-voice message from the packed bytes.
        data = new byte[] {(byte) msg.data1, (byte) msg.data2, (byte) msg.data3};
      }
      rt.sendMessage(data);
    } catch (Throwable ignore) {
      // best effort
    }
  }

  public void controlChange(int chan, int cc, int val) {
    if (rt == null) {
      return;
    }
    try {
      rt.sendMessage(
          new byte[] {(byte) (0xB0 | (chan & 0x0F)), (byte) (cc & 0x7F), (byte) (val & 0x7F)});
    } catch (Throwable ignore) {
      // best effort
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
}
