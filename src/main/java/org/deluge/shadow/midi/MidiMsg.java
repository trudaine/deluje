package org.deluge.shadow.midi;

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
    int s = (status != 0) ? status : (data1 & 0xF0);
    return (s & 0xF0) == 0x90 && data3 > 0;
  }

  public boolean isNoteOff() {
    int s = (status != 0) ? status : (data1 & 0xF0);
    return (s & 0xF0) == 0x80 || ((s & 0xF0) == 0x90 && data3 == 0);
  }
}
