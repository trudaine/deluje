package org.chuck.deluge.model;

/**
 * Model class representing an external MIDI track (Hardware synth MIDI-out). Direct port of
 * MIDIInstrument parameters states, supporting custom CC labels and zones.
 */
public class MidiTrackModel extends TrackModel {
  private int midiChannel = 1; // 1-16, 0 = none/MPE
  private String deviceName = "";
  private String deviceDefinitionFile = "";
  private boolean isMpe = false;
  private String mpeZone = "lower"; // lower or upper
  private final String[] ccLabels = new String[120]; // CC 0 to 119

  public MidiTrackModel(String name) {
    super(name, TrackType.MIDI);
    // Initialize standard MIDI CC labels as fallback defaults
    for (int i = 0; i < 120; i++) {
      ccLabels[i] = "CC " + i;
    }
    ccLabels[1] = "Mod Wheel";
    ccLabels[7] = "Volume";
    ccLabels[10] = "Pan";
    ccLabels[64] = "Sustain";
  }

  public int getMidiChannel() {
    return midiChannel;
  }

  public void setMidiChannel(int midiChannel) {
    if (midiChannel >= 1 && midiChannel <= 16) {
      this.midiChannel = midiChannel;
      this.isMpe = false;
    }
  }

  public String getDeviceName() {
    return deviceName;
  }

  public void setDeviceName(String deviceName) {
    this.deviceName = deviceName != null ? deviceName : "";
  }

  public String getDeviceDefinitionFile() {
    return deviceDefinitionFile;
  }

  public void setDeviceDefinitionFile(String file) {
    this.deviceDefinitionFile = file != null ? file : "";
  }

  public boolean isMpe() {
    return isMpe;
  }

  public void setMpe(boolean mpe) {
    this.isMpe = mpe;
    if (mpe) {
      this.midiChannel = 0; // MPE flag
    }
  }

  public String getMpeZone() {
    return mpeZone;
  }

  public void setMpeZone(String zone) {
    if ("lower".equalsIgnoreCase(zone) || "upper".equalsIgnoreCase(zone)) {
      this.mpeZone = zone.toLowerCase();
    }
  }

  public void setCcLabel(int cc, String label) {
    if (cc >= 0 && cc < 120) {
      ccLabels[cc] = label != null ? label : "CC " + cc;
    }
  }

  public String getCcLabel(int cc) {
    if (cc >= 0 && cc < 120) {
      return ccLabels[cc];
    }
    return "";
  }

  public String[] getCcLabels() {
    return ccLabels;
  }
}
