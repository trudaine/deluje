package org.chuck.deluge.engine.dsp;

import org.chuck.midi.MidiFileOut;
import org.chuck.midi.MidiMsg;

/**
 * Pure Java MIDI export using chuck-core's MidiFileOut (no javax.sound).
 *
 * <p>Records note-on/off events with tick-based timing, then writes a Standard MIDI File using
 * {@link MidiFileOut}.
 */
public class NativeMidiExporter {
  private MidiFileOut midiOut;
  private long currentTick = 0;
  private final int resolution = 480; // PPQ

  public NativeMidiExporter() {
    this.midiOut = new MidiFileOut();
  }

  public void open(String path) {
    midiOut.open(path);
    midiOut.setBpm(120.0f, 0);
    // Track 0: tempo map. Create track 1 for notes.
    midiOut.addTrack();
  }

  public void addNote(int channel, int pitch, int velocity, long durationTicks) {
    // Note on at currentTick
    MidiMsg on = new MidiMsg();
    on.data1 = 0x90 | (channel & 0x0F);
    on.data2 = pitch & 0x7F;
    on.data3 = velocity & 0x7F;
    // Convert ticks to seconds at 120 BPM, 480 PPQ
    on.when = ticksToSeconds(currentTick);
    midiOut.write(1, on);

    // Note off at currentTick + durationTicks
    MidiMsg off = new MidiMsg();
    off.data1 = 0x80 | (channel & 0x0F);
    off.data2 = pitch & 0x7F;
    off.data3 = 0;
    off.when = ticksToSeconds(currentTick + durationTicks);
    midiOut.write(1, off);
  }

  public void advance(long ticks) {
    currentTick += ticks;
  }

  public void save(String path) {
    midiOut.close();
    System.out.println("[NativeMidiExporter] Saved MIDI to: " + path);
  }

  private double ticksToSeconds(long ticks) {
    // 120 BPM = 2 beats/sec = 0.5 sec/beat
    // 480 ticks/beat → tick = 0.5/480 seconds
    return ticks / (double) (resolution * 2);
  }
}
