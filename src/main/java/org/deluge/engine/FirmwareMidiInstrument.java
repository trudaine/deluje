package org.deluge.engine;

import java.util.logging.Logger;
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
  private static final Logger LOGGER = Logger.getLogger(FirmwareMidiInstrument.class.getName());

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
  // C midi_instrument.h:158 — lastMonoExpression[3].
  private final int[] lastMonoExpression = new int[3];

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

  /**
   * C InstrumentClip::sendMIDIPGM (instrument_clip.cpp:3530-3546) — bank MSB (CC0), bank LSB (CC32)
   * and program change on the output master channel, each only when set (128 = none).
   */
  public void sendMidiPgm(int bank, int sub, int pgm) {
    MidiEngine engine = MidiEngine.instance;
    if (engine == null) return;
    int ch = getOutputMasterChannel();
    if (bank != 128) engine.sendCC(ch, 0, bank); // MidiEngine::sendBank (midi_engine.cpp:472)
    if (sub != 128) engine.sendCC(ch, 32, sub); // MidiEngine::sendSubBank (:476)
    if (pgm != 128) engine.sendProgramChange(ch, pgm); // MidiEngine::sendPGMChange (:480)
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
   * C {@code MIDIInstrument::monophonicExpressionEvent} + {@code sendMonophonicExpressionEvent}
   * ({@code midi_instrument.cpp:281-322}).
   */
  public void monophonicExpressionEvent(int newValue, int expressionDimension) {
    if (expressionDimension < 0
        || expressionDimension >= org.deluge.modulation.params.ExpressionParamSet.NUM_DIMENSIONS) {
      return;
    }
    lastMonoExpression[expressionDimension] = newValue;
    MidiEngine engine = MidiEngine.instance;
    if (engine == null) {
      return;
    }
    int masterChannel = getOutputMasterChannel();
    switch (expressionDimension) {
      case org.deluge.modulation.params.ExpressionParamSet.X_PITCH_BEND ->
          engine.sendPitchBend14(
              masterChannel,
              org.deluge.modulation.params.ExpressionParamSet.toMidiPitchBend14(newValue));
      case org.deluge.modulation.params.ExpressionParamSet.Y_SLIDE_TIMBRE ->
          engine.sendCC(
              masterChannel,
              CC_EXTERNAL_MOD_WHEEL,
              org.deluge.modulation.params.ExpressionParamSet.toMidiModWheel7(newValue));
      case org.deluge.modulation.params.ExpressionParamSet.Z_PRESSURE -> {
        int val7 = org.deluge.modulation.params.ExpressionParamSet.toMidiChannelPressure7(newValue);
        MidiMsg msg = MIDIMessage.channelAftertouch(masterChannel, val7).toMidiMsg();
        for (MidiTransport transport : engine.getOutputTransports()) {
          transport.sendMessage(msg);
        }
      }
      default -> {}
    }
  }

  public int getLastMonoExpression(int expressionDimension) {
    if (expressionDimension < 0 || expressionDimension >= lastMonoExpression.length) {
      return 0;
    }
    return lastMonoExpression[expressionDimension];
  }

  /**
   * C: {@code MIDIParamCollection::notifyParamModifiedInSomeWay}
   * (midi_param_collection.cpp:222-228) and {@code MIDIInstrument::sendMonophonicExpressionEvent}
   * (midi_instrument.cpp:286-322). Dispatches real CCs (0..119) as Control Change, and maps the C++
   * fake CC sentinels (120=PitchBend, 121=Aftertouch, 122=Y-Axis/CC1) to their real MIDI status
   * messages instead of accidentally sending MIDI Channel Mode messages (CC 120-123).
   *
   * <p>For fake CCs 121 and 122, both incoming MIDI ({@code melodic_instrument.cpp:388-395, 431,
   * 462}: {@code value << 24}) and MIDI-clip gold knobs ({@code
   * ExpressionParamSet::knobPosToParamValue}, {@code param_set.cpp:621-631}: {@code (knobPos + 64)
   * << 24} for unipolar {@code Y_SLIDE_TIMBRE} and {@code Z_PRESSURE}) store {@code value << 24} in
   * {@code ExpressionParamSet}, which {@code sendMonophonicExpressionEvent} ({@code
   * midi_instrument.cpp:304-317}) shifts back by {@code >> 24} to yield the 7-bit {@code value}
   * (0..127) directly.
   */
  public void sendCc(int cc, int value) {
    if (cc == CC_NUMBER_NONE) {
      return;
    }
    if (cc < 0 || cc > CC_NUMBER_NONE) {
      LOGGER.warning("FirmwareMidiInstrument.sendCc: invalid CC number " + cc);
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
        // C: param_set.cpp:621-631, midi_instrument.cpp:312-318 — (value << 24) >> 24 == value
        MidiMsg msg = MIDIMessage.channelAftertouch(zeroIndexedChannel, clampedValue).toMidiMsg();
        for (MidiTransport transport : engine.getOutputTransports()) {
          transport.sendMessage(msg);
        }
      }
      case CC_NUMBER_Y_AXIS ->
          // C: param_set.cpp:621-631, midi_instrument.cpp:300-310 — (value << 24) >> 24 -> CC 1
          engine.sendCC(zeroIndexedChannel, CC_EXTERNAL_MOD_WHEEL, clampedValue);
      default -> engine.sendCC(zeroIndexedChannel, cc, clampedValue);
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    // MIDI instruments do not render raw digital audio signals.
  }
}
