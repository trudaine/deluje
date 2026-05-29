package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.midi.MidiEngine;

/**
 * Port of MIDIInstrument audio-thread engine in Java. Dispatches real-time note triggers and
 * releases to physical MIDI OUT ports via MidiEngine.
 */
public class FirmwareMidiInstrument extends GlobalEffectable {
  private int midiChannel = 1;
  private boolean isMpe = false;
  private String mpeZone = "lower";

  public FirmwareMidiInstrument(int midiChannel, boolean isMpe) {
    this.midiChannel = midiChannel;
    this.isMpe = isMpe;
  }

  public int getMidiChannel() {
    return midiChannel;
  }

  public void setMidiChannel(int chan) {
    this.midiChannel = chan;
  }

  public boolean isMpe() {
    return isMpe;
  }

  public void setMpe(boolean mpe) {
    this.isMpe = mpe;
  }

  public String getMpeZone() {
    return mpeZone;
  }

  public void setMpeZone(String zone) {
    this.mpeZone = zone;
  }

  public void triggerNote(int note, int vel) {
    MidiEngine engine = MidiEngine.instance;
    if (engine != null) {
      // MIDI channels in MidiEngine are 0-15 (0-indexed) mapping to physical channels 1-16
      int zeroIndexedChannel = Math.max(0, Math.min(15, midiChannel - 1));
      engine.sendNoteOn(zeroIndexedChannel, note, vel);
    }
  }

  public void releaseNote(int note) {
    MidiEngine engine = MidiEngine.instance;
    if (engine != null) {
      int zeroIndexedChannel = Math.max(0, Math.min(15, midiChannel - 1));
      engine.sendNoteOff(zeroIndexedChannel, note, 0);
    }
  }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager paramManager) {
    // MIDI instruments do not render raw digital audio signals.
  }
}
