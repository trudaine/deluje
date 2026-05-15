package org.chuck.deluge.midi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.chuck.midi.MidiMsg;

/**
 * Port of the C++ Deluge MidiEngine. Handles all MIDI I/O protocol logic: message construction,
 * routing, dispatching, loop detection, and cable management.
 *
 * <p>This is the central hub. Incoming messages flow through {@link #midiMessageReceived} which
 * dispatches by status type. Outgoing messages use the send* methods.
 *
 * <p>Transport is abstracted via {@link MidiTransport} — no javax.sound.midi dependency.
 */
public class MidiEngine {

  // --- Constants ---
  private static final int MAX_CABLES = 8;
  private static final int EVENT_STACK_SIZE = 16;

  // --- Callbacks (set by MidiService/other consumers) ---

  /** Called when a Note On is received. */
  private Consumer<MIDIMessage> onNoteOn;

  /** Called when a Note Off is received. */
  private Consumer<MIDIMessage> onNoteOff;

  /** Called when a CC is received. */
  private Consumer<MIDIMessage> onControlChange;

  /** Called when a Pitch Bend is received. */
  private Consumer<MIDIMessage> onPitchBend;

  /** Called when a Channel Aftertouch is received. */
  private Consumer<MIDIMessage> onChannelAftertouch;

  /** Called when a Polyphonic Aftertouch is received. */
  private Consumer<MIDIMessage> onPolyAftertouch;

  /** Called when a Program Change is received. */
  private Consumer<MIDIMessage> onProgramChange;

  /** Called for System Real-Time messages (clock, start, stop). */
  private Consumer<MIDIMessage> onSystemRealtime;

  // --- Cables ---
  private final MidiCable[] cables = new MidiCable[MAX_CABLES];

  // --- Event stack (loop detection) ---
  private static class EventStackEntry {
    final Object source;

    EventStackEntry(Object source) {
      this.source = source;
    }
  }

  private final EventStackEntry[] eventStack = new EventStackEntry[EVENT_STACK_SIZE];
  private int eventStackDepth = 0;

  // --- MIDI Follow ---
  private MidiFollow midiFollow;

  // --- MIDI Takeover ---
  private MidiTakeover midiTakeover;

  // --- Transport ---
  private final List<MidiTransport> outputTransports = new ArrayList<>();

  // --- Log level ---
  private int logLevel = 0;

  public MidiEngine() {
    for (int i = 0; i < MAX_CABLES; i++) {
      cables[i] = new MidiCable("Cable " + i);
    }
  }

  // ===================== Transport Management =====================

  public void addOutputTransport(MidiTransport transport) {
    outputTransports.add(transport);
  }

  public void removeOutputTransport(MidiTransport transport) {
    outputTransports.remove(transport);
  }

  public List<MidiTransport> getOutputTransports() {
    return outputTransports;
  }

  // ===================== Callback Registration =====================

  public void setOnNoteOn(Consumer<MIDIMessage> callback) {
    this.onNoteOn = callback;
  }

  public void setOnNoteOff(Consumer<MIDIMessage> callback) {
    this.onNoteOff = callback;
  }

  public void setOnControlChange(Consumer<MIDIMessage> callback) {
    this.onControlChange = callback;
  }

  public void setOnPitchBend(Consumer<MIDIMessage> callback) {
    this.onPitchBend = callback;
  }

  public void setOnChannelAftertouch(Consumer<MIDIMessage> callback) {
    this.onChannelAftertouch = callback;
  }

  public void setOnPolyAftertouch(Consumer<MIDIMessage> callback) {
    this.onPolyAftertouch = callback;
  }

  public void setOnProgramChange(Consumer<MIDIMessage> callback) {
    this.onProgramChange = callback;
  }

  public void setOnSystemRealtime(Consumer<MIDIMessage> callback) {
    this.onSystemRealtime = callback;
  }

  // ===================== Event Stack (Loop Detection) =====================

  /**
   * Check if a source is already on the event stack (would cause a loop). Returns true if the
   * source is present.
   */
  public boolean containsSource(Object source) {
    for (int i = 0; i < eventStackDepth; i++) {
      if (eventStack[i].source == source) return true;
    }
    return false;
  }

  /** Push a source onto the event stack. */
  public void pushSource(Object source) {
    if (eventStackDepth < EVENT_STACK_SIZE) {
      eventStack[eventStackDepth++] = new EventStackEntry(source);
    }
  }

  /** Pop the top source from the event stack. */
  public void popSource() {
    if (eventStackDepth > 0) {
      eventStack[--eventStackDepth] = null;
    }
  }

  public int getEventStackDepth() {
    return eventStackDepth;
  }

  // ===================== Cable Management =====================

  public MidiCable getCable(int index) {
    if (index < 0 || index >= MAX_CABLES) return null;
    return cables[index];
  }

  public MidiCable[] getCables() {
    return cables;
  }

  public int getCableCount() {
    return MAX_CABLES;
  }

  /** Resolve which cable to use for a given MIDI channel (zone-aware). */
  public int getCableForChannel(int channel) {
    // Check each cable's MPE zones
    for (int i = 0; i < MAX_CABLES; i++) {
      if (cables[i].channelToZone(channel) != 0 || cables[i].isMasterChannel(channel)) {
        return i;
      }
    }
    // Default to cable 0 for standard MIDI channels
    return 0;
  }

  // ===================== Send Methods =====================

  public void sendNoteOn(int channel, int pitch, int velocity) {
    MIDIMessage msg = MIDIMessage.noteOn(channel, pitch, velocity);
    sendToAll(msg);
  }

  public void sendNoteOff(int channel, int pitch, int velocity) {
    MIDIMessage msg = MIDIMessage.noteOff(channel, pitch, velocity);
    sendToAll(msg);
  }

  public void sendCC(int channel, int ccNum, int value) {
    MIDIMessage msg = MIDIMessage.cc(channel, ccNum, value);
    sendToAll(msg);
  }

  public void sendPitchBend(int channel, int lsb, int msb) {
    MIDIMessage msg = MIDIMessage.pitchBend(channel, lsb, msb);
    sendToAll(msg);
  }

  public void sendPitchBend14(int channel, int value) {
    MIDIMessage msg = MIDIMessage.pitchBend14(channel, value);
    sendToAll(msg);
  }

  public void sendProgramChange(int channel, int program) {
    MIDIMessage msg = MIDIMessage.programChange(channel, program);
    sendToAll(msg);
  }

  public void sendClock() {
    sendToAll(MIDIMessage.clock());
  }

  public void sendStart() {
    sendToAll(MIDIMessage.start());
  }

  public void sendStop() {
    sendToAll(MIDIMessage.stop());
  }

  public void sendContinue() {
    sendToAll(MIDIMessage.continueMsg());
  }

  /** Send a raw MIDIMessage to all output transports. */
  private void sendToAll(MIDIMessage msg) {
    MidiMsg transportMsg = msg.toMidiMsg();
    for (MidiTransport transport : outputTransports) {
      transport.sendMessage(transportMsg);
    }
  }

  // ===================== Receive / Dispatch =====================

  /**
   * Called when a MIDI message is received from a transport. Handles loop detection and dispatches
   * by status type.
   *
   * @param msg The received MIDI message
   * @param source The source object (e.g. MidiIn instance), used for loop detection
   */
  public void midiMessageReceived(MIDIMessage msg, Object source) {
    if (containsSource(source)) {
      if (logLevel >= 1) {
        System.out.println("[MidiEngine] Loop detected, ignoring message from " + source);
      }
      return;
    }

    pushSource(source);

    try {
      switch (msg.statusType()) {
        case 0x08 -> // Note Off
            handleNoteOff(msg);
        case 0x09 -> // Note On
            handleNoteOn(msg);
        case 0x0A -> // Polyphonic Aftertouch
            handlePolyAftertouch(msg);
        case 0x0B -> // Control Change
            handleControlChange(msg);
        case 0x0C -> // Program Change
            handleProgramChange(msg);
        case 0x0D -> // Channel Aftertouch
            handleChannelAftertouch(msg);
        case 0x0E -> // Pitch Bend
            handlePitchBend(msg);
        case 0x0F -> // System
            handleSystem(msg);
        default -> {
          if (logLevel >= 1) {
            System.out.println("[MidiEngine] Unknown status type: " + msg.statusType());
          }
        }
      }
    } finally {
      popSource();
    }
  }

  private void handleNoteOn(MIDIMessage msg) {
    if (midiFollow != null) midiFollow.handleNote(msg);
    if (onNoteOn != null) onNoteOn.accept(msg);
  }

  private void handleNoteOff(MIDIMessage msg) {
    if (midiFollow != null) midiFollow.handleNote(msg);
    if (onNoteOff != null) onNoteOff.accept(msg);
  }

  private void handleControlChange(MIDIMessage msg) {
    if (midiTakeover != null) {
      // TODO: midiTakeover.process(msg) when implemented
    }
    if (midiFollow != null) midiFollow.handleCC(msg);
    if (onControlChange != null) onControlChange.accept(msg);
  }

  private void handlePitchBend(MIDIMessage msg) {
    if (midiFollow != null) midiFollow.handlePitchBend(msg);
    if (onPitchBend != null) onPitchBend.accept(msg);
  }

  private void handleChannelAftertouch(MIDIMessage msg) {
    if (midiFollow != null) midiFollow.handleChannelAftertouch(msg);
    if (onChannelAftertouch != null) onChannelAftertouch.accept(msg);
  }

  private void handlePolyAftertouch(MIDIMessage msg) {
    if (midiFollow != null) midiFollow.handlePolyAftertouch(msg);
    if (onPolyAftertouch != null) onPolyAftertouch.accept(msg);
  }

  private void handleProgramChange(MIDIMessage msg) {
    if (onProgramChange != null) onProgramChange.accept(msg);
  }

  private void handleSystem(MIDIMessage msg) {
    if (onSystemRealtime != null) onSystemRealtime.accept(msg);
    // MIDI Clock / Start / Stop handled by Transport via callback
    // SysEx handled separately through raw byte path
  }

  // ===================== SysEx =====================

  /** Send a SysEx message. */
  public void sendSysex(byte[] data) {
    MidiMsg msg = new MidiMsg();
    msg.setData(data);
    for (MidiTransport transport : outputTransports) {
      transport.sendMessage(msg);
    }
  }

  // ===================== MIDI Follow / Takeover =====================

  public void setMidiFollow(MidiFollow follow) {
    this.midiFollow = follow;
  }

  public MidiFollow getMidiFollow() {
    return midiFollow;
  }

  public void setMidiTakeover(MidiTakeover takeover) {
    this.midiTakeover = takeover;
  }

  public MidiTakeover getMidiTakeover() {
    return midiTakeover;
  }

  // ===================== Logging =====================

  public void setLogLevel(int level) {
    this.logLevel = level;
  }

  public int getLogLevel() {
    return logLevel;
  }

  // ===================== Shutdown =====================

  public void close() {
    for (MidiTransport transport : outputTransports) {
      transport.close();
    }
    outputTransports.clear();
  }
}
