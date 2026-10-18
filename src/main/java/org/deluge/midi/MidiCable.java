package org.deluge.midi;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of the C++ MidiCable / midi_device.h port model. Represents one MIDI port connection with
 * associated device list and MPE zone configuration.
 *
 * <p>Each cable corresponds to a physical or virtual MIDI port. Up to 8 cables are managed by
 * MidiEngine.
 */
public class MidiCable {

  /** MPE zone configuration for a cable. */
  public static class MpeZone {
    /** First member channel of this zone (1-indexed in C++ firmware, 0-indexed here). */
    public int memberChannel;

    /** Last member channel (inclusive). */
    public int lastMemberChannel;

    /** Master channel for this zone. -1 means unset / no zone. */
    public int masterChannel = -1;

    public MpeZone(int memberChannel, int lastMemberChannel, int masterChannel) {
      this.memberChannel = memberChannel;
      this.lastMemberChannel = lastMemberChannel;
      this.masterChannel = masterChannel;
    }

    /** Returns true if the given channel (0-indexed) is in this zone's member range. */
    public boolean containsChannel(int channel) {
      return channel >= memberChannel && channel <= lastMemberChannel && channel != masterChannel;
    }

    /** Returns true if the given channel is this zone's master channel. */
    public boolean isMasterChannel(int channel) {
      return channel == masterChannel;
    }

    /** Returns the number of active member channels in this zone (excluding masterChannel). */
    public int getMemberChannelCount() {
      if (lastMemberChannel < memberChannel) {
        return 0;
      }
      int count = lastMemberChannel - memberChannel + 1;
      if (masterChannel >= memberChannel && masterChannel <= lastMemberChannel) {
        count--;
      }
      return Math.max(0, count);
    }
  }

  /** Lower zone (channels 1-14 typically). */
  private MpeZone lowerZone;

  /** Upper zone (channels 1-14 typically). */
  private MpeZone upperZone;

  /** List of device definitions associated with this cable. */
  private final List<MidiDeviceDefinition> devices = new ArrayList<>();

  /** Human-readable port name. */
  private String portName = "";

  public MidiCable() {}

  public MidiCable(String portName) {
    this.portName = portName;
  }

  // --- MPE Zone API ---

  /**
   * Configures the lower MPE zone and moves the upper zone out of the way if they overlap (C
   * MIDIPort::moveUpperZoneOutOfWayOfLowerZone, midi_device.cpp:97-98, 266-275).
   */
  public void setLowerZone(int memberFrom, int memberTo, int masterChannel) {
    if (memberFrom > memberTo) {
      this.lowerZone = null;
      return;
    }
    this.lowerZone = new MpeZone(memberFrom, memberTo, masterChannel);
    if (this.lowerZone.getMemberChannelCount() <= 0) {
      this.lowerZone = null;
      return;
    }
    moveUpperZoneOutOfWayOfLowerZone();
  }

  /**
   * Configures the upper MPE zone and moves the lower zone out of the way if they overlap (C
   * MIDIPort::moveLowerZoneOutOfWayOfUpperZone, midi_device.cpp:128, 277-286).
   */
  public void setUpperZone(int memberFrom, int memberTo, int masterChannel) {
    if (memberFrom > memberTo) {
      this.upperZone = null;
      return;
    }
    this.upperZone = new MpeZone(memberFrom, memberTo, masterChannel);
    if (this.upperZone.getMemberChannelCount() <= 0) {
      this.upperZone = null;
      return;
    }
    moveLowerZoneOutOfWayOfUpperZone();
  }

  private void moveUpperZoneOutOfWayOfLowerZone() {
    if (lowerZone == null || upperZone == null) {
      return;
    }
    if (upperZone.memberChannel <= lowerZone.lastMemberChannel) {
      upperZone.memberChannel = lowerZone.lastMemberChannel + 1;
      if (upperZone.getMemberChannelCount() <= 0) {
        upperZone = null;
      }
    }
  }

  private void moveLowerZoneOutOfWayOfUpperZone() {
    if (upperZone == null || lowerZone == null) {
      return;
    }
    if (lowerZone.lastMemberChannel >= upperZone.memberChannel) {
      lowerZone.lastMemberChannel = upperZone.memberChannel - 1;
      if (lowerZone.getMemberChannelCount() <= 0) {
        lowerZone = null;
      }
    }
  }

  public void clearLowerZone() {
    this.lowerZone = null;
  }

  public void clearUpperZone() {
    this.upperZone = null;
  }

  public MpeZone getLowerZone() {
    return lowerZone;
  }

  public MpeZone getUpperZone() {
    return upperZone;
  }

  /**
   * Returns which MPE zone the given channel belongs to. Returns 1 for lower zone, 2 for upper
   * zone, 0 for no zone (standard MIDI).
   */
  public int channelToZone(int channel) {
    if (lowerZone != null && lowerZone.containsChannel(channel)) return 1;
    if (upperZone != null && upperZone.containsChannel(channel)) return 2;
    return 0;
  }

  /** Returns true if the given channel is a master channel of any zone. */
  public boolean isMasterChannel(int channel) {
    return (lowerZone != null && lowerZone.isMasterChannel(channel))
        || (upperZone != null && upperZone.isMasterChannel(channel));
  }

  /** Returns the total number of member channels across both zones. */
  public int getMemberChannelCount() {
    int count = 0;
    if (lowerZone != null) {
      count += lowerZone.getMemberChannelCount();
    }
    if (upperZone != null) {
      count += upperZone.getMemberChannelCount();
    }
    return count;
  }

  // --- Device list ---

  public void addDevice(MidiDeviceDefinition device) {
    devices.add(device);
  }

  public void removeDevice(MidiDeviceDefinition device) {
    devices.remove(device);
  }

  public List<MidiDeviceDefinition> getDevices() {
    return devices;
  }

  public String getPortName() {
    return portName;
  }

  public void setPortName(String portName) {
    this.portName = portName;
  }

  /** Look up a device by its id string. */
  public MidiDeviceDefinition findDeviceById(String id) {
    for (MidiDeviceDefinition d : devices) {
      if (d.getId().equals(id)) return d;
    }
    return null;
  }
}
