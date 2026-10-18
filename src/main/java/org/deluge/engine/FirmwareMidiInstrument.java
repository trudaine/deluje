package org.deluge.engine;

import org.deluge.firmware2.GlobalEffectable;
import org.deluge.midi.MIDIMessage;
import org.deluge.midi.MidiEngine;
import org.deluge.midi.MidiTransport;
import org.deluge.shadow.midi.MidiMsg;

/**
 * Port of MIDIInstrument audio-thread engine in Java. Dispatches real-time note triggers and
 * releases to physical MIDI OUT ports via MidiEngine.
 */
public class FirmwareMidiInstrument extends GlobalEffectable {
  /** C: definitions_cxx.hpp:758 — outgoing MIDI CC 1 for monophonic Y-axis / Mod Wheel. */
  public static final int CC_EXTERNAL_MOD_WHEEL = 1;

  /** C: definitions_cxx.hpp:760 — sentinel CC number for Pitch Bend. */
  public static final int CC_NUMBER_PITCH_BEND = 120;

  /** C: definitions_cxx.hpp:761 — sentinel CC number for Channel Aftertouch. */
  public static final int CC_NUMBER_AFTERTOUCH = 121;

  /** C: definitions_cxx.hpp:762 — sentinel CC number for Y-axis / Mod Wheel. */
  public static final int CC_NUMBER_Y_AXIS = 122;

  /** C: definitions_cxx.hpp:763 — sentinel CC number for "no CC assigned". */
  public static final int CC_NUMBER_NONE = 123;

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

  /**
   * C: {@code MIDIInstrument::getOutputMasterChannel} (midi_instrument.cpp:267-279) — MPE lower
   * zone outputs on master channel 0 (ch 1), MPE upper zone outputs on master channel 15 (ch 16),
   * and standard MIDI channels map 1-16 to 0-15.
   */
  public int getOutputMasterChannel() {
    if (isMpe) {
      return "upper".equalsIgnoreCase(mpeZone) ? 15 : 0;
    }
    return Math.max(0, Math.min(15, midiChannel - 1));
  }

  public void triggerNote(int note, int vel) {
    MidiEngine engine = MidiEngine.instance;
    if (engine != null) {
      int zeroIndexedChannel = getOutputMasterChannel();
      engine.sendNoteOn(zeroIndexedChannel, note, vel);
    }
  }

  public void releaseNote(int note) {
    MidiEngine engine = MidiEngine.instance;
    if (engine != null) {
      int zeroIndexedChannel = getOutputMasterChannel();
      engine.sendNoteOff(zeroIndexedChannel, note, 0);
    }
  }

  /**
   * C: {@code MIDIParamCollection::notifyParamModifiedInSomeWay}
   * (midi_param_collection.cpp:222-228) and {@code MIDIInstrument::sendMonophonicExpressionEvent}
   * (midi_instrument.cpp:286-322). Dispatches real CCs (0..119) as Control Change, and maps the C++
   * fake CC sentinels (120=PitchBend, 121=Aftertouch, 122=Y-Axis/CC1) to their real MIDI status
   * messages instead of accidentally sending MIDI Channel Mode messages (CC 120-123).
   */
  public void sendCc(int cc, int value) {
    if (cc < 0 || cc >= CC_NUMBER_NONE) {
      return;
    }
    MidiEngine engine = MidiEngine.instance;
    if (engine == null) {
      return;
    }
    int zeroIndexedChannel = getOutputMasterChannel();
    int clampedValue = Math.max(0, Math.min(127, value));
    switch (cc) {
      case CC_NUMBER_PITCH_BEND ->
          // C: midi_instrument.cpp:262, 294 — ((value - 64) << 7) + 8192 == value << 7
          engine.sendPitchBend14(zeroIndexedChannel, clampedValue << 7);
      case CC_NUMBER_AFTERTOUCH -> {
        MidiMsg msg = MIDIMessage.channelAftertouch(zeroIndexedChannel, clampedValue).toMidiMsg();
        for (MidiTransport transport : engine.getOutputTransports()) {
          transport.sendMessage(msg);
        }
      }
      case CC_NUMBER_Y_AXIS ->
          // C: midi_instrument.cpp:309 — monophonic Y-axis sends CC_EXTERNAL_MOD_WHEEL (CC 1)
          engine.sendCC(zeroIndexedChannel, CC_EXTERNAL_MOD_WHEEL, clampedValue);
      default -> engine.sendCC(zeroIndexedChannel, cc, clampedValue);
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    // MIDI instruments do not render raw digital audio signals.
  }
}
