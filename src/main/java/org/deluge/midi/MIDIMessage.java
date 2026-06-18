package org.deluge.midi;

import org.chuck.midi.MidiMsg;

/**
 * Port of the C++ Deluge MIDIMessage: a structured MIDI message with separate statusType (high
 * nibble), channel, data1, data2. Uses the same encoding as the C++ firmware.
 *
 * <p>Status types match the C++ MIDI convention (high nibble values):
 *
 * <ul>
 *   <li>0x08 — Note Off
 *   <li>0x09 — Note On
 *   <li>0x0A — Polyphonic Aftertouch
 *   <li>0x0B — Control Change
 *   <li>0x0C — Program Change
 *   <li>0x0D — Channel Aftertouch
 *   <li>0x0E — Pitch Bend
 *   <li>0x0F — System (Clock, Start, Stop, Continue, SysEx, etc.)
 * </ul>
 */
public record MIDIMessage(int statusType, int channel, int data1, int data2) {

  // --- Static factories ---

  public static MIDIMessage noteOn(int channel, int pitch, int velocity) {
    return new MIDIMessage(0x09, channel & 0x0F, pitch & 0x7F, velocity & 0x7F);
  }

  public static MIDIMessage noteOff(int channel, int pitch, int velocity) {
    return new MIDIMessage(0x08, channel & 0x0F, pitch & 0x7F, velocity & 0x7F);
  }

  public static MIDIMessage cc(int channel, int ccNum, int value) {
    return new MIDIMessage(0x0B, channel & 0x0F, ccNum & 0x7F, value & 0x7F);
  }

  public static MIDIMessage pitchBend(int channel, int lsb, int msb) {
    return new MIDIMessage(0x0E, channel & 0x0F, lsb & 0x7F, msb & 0x7F);
  }

  public static MIDIMessage pitchBend14(int channel, int value) {
    return pitchBend(channel, value & 0x7F, (value >> 7) & 0x7F);
  }

  public static MIDIMessage programChange(int channel, int program) {
    return new MIDIMessage(0x0C, channel & 0x0F, program & 0x7F, 0);
  }

  public static MIDIMessage polyAftertouch(int channel, int note, int value) {
    return new MIDIMessage(0x0A, channel & 0x0F, note & 0x7F, value & 0x7F);
  }

  public static MIDIMessage channelAftertouch(int channel, int value) {
    return new MIDIMessage(0x0D, channel & 0x0F, value & 0x7F, 0);
  }

  // --- System Real-Time messages (statusType=0x0F, channel encodes sub-type) ---

  /** MIDI Clock (F8). */
  public static MIDIMessage clock() {
    return new MIDIMessage(0x0F, 0x08, 0, 0);
  }

  /** MIDI Start (FA). */
  public static MIDIMessage start() {
    return new MIDIMessage(0x0F, 0x0A, 0, 0);
  }

  /** MIDI Stop (FC). */
  public static MIDIMessage stop() {
    return new MIDIMessage(0x0F, 0x0C, 0, 0);
  }

  /** MIDI Continue (FB). */
  public static MIDIMessage continueMsg() {
    return new MIDIMessage(0x0F, 0x0B, 0, 0);
  }

  /** Active Sensing (FE). */
  public static MIDIMessage activeSensing() {
    return new MIDIMessage(0x0F, 0x0E, 0, 0);
  }

  // --- Conversion to/from MidiMsg ---

  /** Reconstruct the full status byte: (statusType << 4) | channel. */
  public int getStatusByte() {
    return (statusType << 4) | (channel & 0x0F);
  }

  /**
   * Convert this MIDIMessage to a chuck-core MidiMsg for transport send.
   *
   * <p>System messages (statusType=0x0F) use the channel field as sub-type to reconstruct the full
   * status byte (e.g. clock → 0xF8).
   */
  public MidiMsg toMidiMsg() {
    MidiMsg msg = new MidiMsg();
    msg.data1 = getStatusByte();
    msg.data2 = data1;
    msg.data3 = data2;
    return msg;
  }

  /**
   * Convert a chuck-core MidiMsg to a MIDIMessage. Parses the packed status byte into statusType
   * and channel.
   */
  public static MIDIMessage fromMidiMsg(MidiMsg msg) {
    int status = msg.data1;
    int statusType = (status >> 4) & 0x0F;
    int channel = status & 0x0F;
    return new MIDIMessage(statusType, channel, msg.data2, msg.data3);
  }

  // --- Query helpers ---

  public boolean isNoteOn() {
    return statusType == 0x09 && data2 > 0;
  }

  public boolean isNoteOff() {
    return statusType == 0x08 || (statusType == 0x09 && data2 == 0);
  }

  public boolean isCC() {
    return statusType == 0x0B;
  }

  public boolean isPitchBend() {
    return statusType == 0x0E;
  }

  public boolean isSystem() {
    return statusType == 0x0F;
  }

  public boolean isClock() {
    return statusType == 0x0F && channel == 0x08;
  }

  public boolean isMidiStart() {
    return statusType == 0x0F && channel == 0x0A;
  }

  public boolean isMidiStop() {
    return statusType == 0x0F && channel == 0x0C;
  }

  public boolean isMidiContinue() {
    return statusType == 0x0F && channel == 0x0B;
  }

  /** Returns the pitch bend 14-bit value (0-16383, 8192 = center). */
  public int pitchBendValue() {
    return (data2 << 7) | data1;
  }

  /** Returns the channel aftertouch value (for statusType=0x0D). */
  public int aftertouchValue() {
    return data1;
  }
}
