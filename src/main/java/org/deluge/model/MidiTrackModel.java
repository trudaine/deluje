package org.deluge.model;

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

  /** C: {@code CC_NUMBER_NONE} (midi_instrument.cpp:45) — "no CC assigned" sentinel. */
  public static final int CC_NUMBER_NONE = 123;

  /** C: {@code kNumModButtons} (definitions_cxx.hpp) — 8 gold-knob mode pages. */
  private static final int NUM_MOD_KNOB_MODES = 8;

  private int modKnobMode = 0;

  /**
   * C: {@code MIDIInstrument::modKnobCCAssignments} (midi_instrument.h:113) — which CC each gold
   * knob sends per mode page, indexed {@code mode * 2 + knobIndex}. In-memory only for now (not yet
   * persisted to XML — see {@code docs/ui_action_parity_audit_2026-07-14.md}).
   */
  private final int[] modKnobCcAssignments = new int[NUM_MOD_KNOB_MODES * 2];

  /**
   * The knob's current 0-127 output value per mode page, sent as the CC value on each turn. Not a
   * direct C field — the real firmware tracks this via a full {@code AutoParam} inside a {@code
   * MIDIParamCollection}; this is the minimal state needed to send a sensible absolute CC value
   * from a relative knob turn.
   */
  private final int[] modKnobCcValues = new int[NUM_MOD_KNOB_MODES * 2];

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
    java.util.Arrays.fill(modKnobCcAssignments, CC_NUMBER_NONE);
    java.util.Arrays.fill(modKnobCcValues, 64);
  }

  public int getModKnobMode() {
    return modKnobMode;
  }

  public void setModKnobMode(int v) {
    if (v >= 0 && v < NUM_MOD_KNOB_MODES) {
      modKnobMode = v;
    }
  }

  public int getModKnobCc(int mode, int knobIndex) {
    return modKnobCcAssignments[mode * 2 + knobIndex];
  }

  /** Wraps 0..122 (123 = {@link #CC_NUMBER_NONE}), mirroring the C's mod-124 wrap. */
  public void setModKnobCc(int mode, int knobIndex, int cc) {
    modKnobCcAssignments[mode * 2 + knobIndex] = ((cc % 124) + 124) % 124;
  }

  public int getModKnobCcValue(int mode, int knobIndex) {
    return modKnobCcValues[mode * 2 + knobIndex];
  }

  public void setModKnobCcValue(int mode, int knobIndex, int value) {
    modKnobCcValues[mode * 2 + knobIndex] = Math.max(0, Math.min(127, value));
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
