package org.chuck.midi;

/**
 * A lightweight shadow representing a raw MIDI message. Implemented in pure Java to completely
 * decouple the Deluge UI from the heavy native ChucK VM.
 */
public class MidiMsg {
  public int data1;
  public int data2;
  public int data3;
  public int channel;
  public int status;
  public double time;

  public byte[] data = new byte[0];

  public void setData(byte[] d) {
    this.data = d;
  }

  public byte[] getData() {
    return this.data;
  }

  public boolean isNoteOn() {
    return (status & 0xF0) == 0x90 && data2 > 0;
  }

  public boolean isNoteOff() {
    return (status & 0xF0) == 0x80 || ((status & 0xF0) == 0x90 && data2 == 0);
  }
}
