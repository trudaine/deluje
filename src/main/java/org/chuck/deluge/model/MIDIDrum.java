package org.chuck.deluge.model;

/** A MIDI-out drum on a Kit track — sends MIDI notes/CC rather than playing a sample. */
public class MIDIDrum extends Drum {

  public enum MidiMessageType {
    NOTE,
    CC,
    PROGRAM_CHANGE
  }

  private MidiMessageType messageType = MidiMessageType.NOTE;
  private int midiChannel = 1; // 1-16
  private int midiNote = 36; // 0-127, default C2
  private int midiCC = -1; // 0-127, -1 = none
  private int midiVelocityOverride = -1; // 0-127, -1 = use gate velocity

  public MIDIDrum(String name) {
    super(name);
  }

  public MIDIDrum(String name, int midiChannel, int midiNote) {
    super(name);
    this.midiChannel = midiChannel;
    this.midiNote = midiNote;
  }

  public MidiMessageType getMessageType() {
    return messageType;
  }

  public void setMessageType(MidiMessageType v) {
    this.messageType = v;
  }

  public int getMidiChannel() {
    return midiChannel;
  }

  public void setMidiChannel(int v) {
    this.midiChannel = clamp(v, 1, 16);
  }

  public int getMidiNote() {
    return midiNote;
  }

  public void setMidiNote(int v) {
    this.midiNote = clamp(v, 0, 127);
  }

  public int getMidiCC() {
    return midiCC;
  }

  public void setMidiCC(int v) {
    this.midiCC = v < 0 ? -1 : clamp(v, 0, 127);
  }

  public int getMidiVelocityOverride() {
    return midiVelocityOverride;
  }

  public void setMidiVelocityOverride(int v) {
    this.midiVelocityOverride = v < 0 ? -1 : clamp(v, 0, 127);
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }
}
