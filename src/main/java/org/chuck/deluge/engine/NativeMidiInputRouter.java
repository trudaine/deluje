package org.chuck.deluge.engine;

import java.util.*;
import javax.sound.midi.*;
import org.chuck.deluge.BridgeContract;

/**
 * Pure Java MIDI input router for NativeJavaSequencer.
 *
 * <p>Uses {@link javax.sound.midi.MidiDevice} to enumerate and open MIDI input ports. Receives
 * note-on/off, CC, and pitch bend messages, routing them to voice triggers in the engine via the
 * {@link TickEventQueue}.
 *
 * <p>Matches the functionality of {@code MidiInputRouter} but without ChucK dependency. When the
 * Java engine is active, this replaces the ChucK-based MidiInputRouter.
 */
public class NativeMidiInputRouter {

  private final BridgeContract bridge;
  private final TickEventQueue eventQueue;
  private final VoiceAllocator voiceAllocator;

  private MidiDevice midiInputDevice;
  private volatile boolean running = false;
  private Thread midiThread;

  /** Per-track arpeggiator state. */
  private final List<Integer>[] heldNotes = new List[BridgeContract.TRACKS];

  private final int[] activePitchBend = new int[BridgeContract.TRACKS];
  private final int[] noteToTrackTracked = new int[128];
  private final Map<Integer, Long> noteOnTimes = new HashMap<>();

  private int activeTrack = 0;
  private boolean followMode = true;

  @SuppressWarnings("unchecked")
  public NativeMidiInputRouter(
      BridgeContract bridge, TickEventQueue eventQueue, VoiceAllocator voiceAllocator) {
    this.bridge = bridge;
    this.eventQueue = eventQueue;
    this.voiceAllocator = voiceAllocator;
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
      heldNotes[i] = new ArrayList<>();
      activePitchBend[i] = 64; // center
    }
    java.util.Arrays.fill(noteToTrackTracked, -1);
  }

  public void setActiveTrack(int track) {
    this.activeTrack = track;
  }

  public void setFollowMode(boolean f) {
    this.followMode = f;
  }

  /** Enumerate available MIDI input devices. */
  public static List<String> listMidiInputDevices() {
    List<String> names = new ArrayList<>();
    MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
    for (MidiDevice.Info info : infos) {
      try {
        MidiDevice dev = MidiSystem.getMidiDevice(info);
        if (dev.getMaxTransmitters() != 0) {
          names.add(info.getName() + " (" + info.getDescription() + ")");
        }
      } catch (MidiUnavailableException ignored) {
      }
    }
    return names;
  }

  /**
   * Open the first available MIDI input device.
   *
   * @return true if a device was opened
   */
  public boolean openFirstDevice() {
    MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
    for (MidiDevice.Info info : infos) {
      try {
        MidiDevice dev = MidiSystem.getMidiDevice(info);
        if (dev.getMaxTransmitters() != 0) {
          dev.open();
          midiInputDevice = dev;
          System.out.println("[NativeMidiInputRouter] Opened device: " + info.getName());
          return true;
        }
      } catch (MidiUnavailableException ignored) {
      }
    }
    System.out.println("[NativeMidiInputRouter] No MIDI input devices found.");
    return false;
  }

  /** Open a specific MIDI input device by name (partial match). */
  public boolean openDevice(String nameFragment) {
    MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
    for (MidiDevice.Info info : infos) {
      if (info.getName().toLowerCase().contains(nameFragment.toLowerCase())) {
        try {
          MidiDevice dev = MidiSystem.getMidiDevice(info);
          if (dev.getMaxTransmitters() != 0) {
            dev.open();
            midiInputDevice = dev;
            System.out.println("[NativeMidiInputRouter] Opened device: " + info.getName());
            return true;
          }
        } catch (MidiUnavailableException ignored) {
        }
      }
    }
    return openFirstDevice();
  }

  public void start() {
    if (midiInputDevice == null) return;
    if (running) return;
    running = true;
    midiThread = new Thread(this::midiLoop, "NativeMidiInputRouter");
    midiThread.setDaemon(true);
    midiThread.start();
  }

  public void stop() {
    running = false;
    if (midiInputDevice != null && midiInputDevice.isOpen()) {
      midiInputDevice.close();
    }
  }

  public boolean isRunning() {
    return running;
  }

  private void midiLoop() {
    try {
      Transmitter tx = midiInputDevice.getTransmitter();
      Receiver rx =
          new Receiver() {
            @Override
            public void send(MidiMessage message, long timeStamp) {
              if (message instanceof ShortMessage sm) {
                handleShortMessage(sm);
              }
            }

            @Override
            public void close() {}
          };
      tx.setReceiver(rx);

      // Keep thread alive until stopped
      while (running && midiInputDevice.isOpen()) {
        Thread.sleep(10);
      }
    } catch (Exception e) {
      System.err.println("[NativeMidiInputRouter] Error: " + e.getMessage());
    }
  }

  private int resolveTrack() {
    if (followMode) {
      // Read the currently selected track from bridge
      // (Use preview track as proxy for "selected track" in follow mode)
      int previewTrack = (int) (bridge.getTrackType(activeTrack) >= 0 ? activeTrack : 0);
      return previewTrack;
    }
    return activeTrack;
  }

  private void handleShortMessage(ShortMessage msg) {
    int cmd = msg.getCommand();

    switch (cmd) {
      case ShortMessage.NOTE_ON -> {
        int note = msg.getData1();
        int vel = msg.getData2();
        if (vel == 0) {
          int track = noteToTrackTracked[note];
          if (track == -1) {
            track = resolveTrack();
          }
          handleNoteOff(track, note);
          noteToTrackTracked[note] = -1;
          return;
        }
        int track = resolveTrack();
        noteToTrackTracked[note] = track;
        handleNoteOn(track, note, vel);
      }
      case ShortMessage.NOTE_OFF -> {
        int note = msg.getData1();
        int track = noteToTrackTracked[note];
        if (track == -1) {
          track = resolveTrack();
        }
        handleNoteOff(track, note);
        noteToTrackTracked[note] = -1;
      }
      case ShortMessage.CONTROL_CHANGE -> {
        int track = resolveTrack();
        int cc = msg.getData1();
        int val = msg.getData2();
        handleControlChange(track, cc, val);
      }
      case ShortMessage.PITCH_BEND -> {
        int track = resolveTrack();
        int lsb = msg.getData1();
        int msb = msg.getData2();
        int bend = (msb << 7) | lsb;
        handlePitchBend(track, bend);
      }
    }
  }

  private void handleNoteOn(int track, int note, int velocity) {
    noteOnTimes.put(note, System.nanoTime());
    heldNotes[track].add(note);

    int trackType = bridge.getTrackType(track);

    // Determine command type based on track type
    TickEventQueue.Command cmd;
    if (trackType == 0) {
      cmd = TickEventQueue.Command.SAMPLE_NOTE_ON;
    } else if (bridge.getSynthAlgo(track) > 0) {
      cmd = TickEventQueue.Command.DX7_NOTE_ON;
    } else {
      cmd = TickEventQueue.Command.SYNTH_NOTE_ON;
    }

    // Push to event queue for sample-accurate dispatch
    TickEventQueue.Event template = new TickEventQueue.Event();
    template.set(cmd, track, note, velocity, -1);
    template.gain = velocity / 127.0f * 0.15f;
    template.cutoff = bridge.getTrackFilterFreq(track) * 15000.0 + 20.0;
    template.resonance = bridge.getTrackFilterRes(track);
    if (trackType == 0) {
      template.setSample(bridge.getSamplePath(track));
    }
    // Read env from bridge
    int eb = (track * BridgeContract.ENV_COUNT + 0) * BridgeContract.ENV_PARAMS;
    float[] env = bridge.getEnvRaw();
    if (env != null && eb + 3 < env.length) {
      template.setADSR(env[eb], env[eb + 1], env[eb + 2], env[eb + 3]);
    }
    eventQueue.push(template);
  }

  private void handleNoteOff(int track, int note) {
    heldNotes[track].remove((Integer) note);
    noteOnTimes.remove(note);

    int trackType = bridge.getTrackType(track);
    TickEventQueue.Command cmd;
    if (trackType == 0) {
      cmd = TickEventQueue.Command.SAMPLE_NOTE_OFF;
    } else if (bridge.getSynthAlgo(track) > 0) {
      cmd = TickEventQueue.Command.DX7_NOTE_OFF;
    } else {
      cmd = TickEventQueue.Command.SYNTH_NOTE_OFF;
    }
    eventQueue.pushNoteOff(cmd, track, note);
  }

  private void handleControlChange(int track, int cc, int value) {
    // CC routing: handle common CCs
    double normalized = value / 127.0;

    switch (cc) {
      case 7: // Volume
        bridge.setTrackLevel(track, normalized);
        break;
      case 10: // Pan
        bridge.setTrackLevel(track, normalized * 2.0 - 1.0);
        break;
      case 74: // Filter cutoff (common MIDI controller)
        bridge.setFilterFreq(track, normalized);
        break;
      case 71: // Filter resonance
        bridge.setFilterRes(track, normalized);
        break;
      case 64: // Sustain pedal
        if (value < 64) {
          // Release all held notes
          for (int n : new ArrayList<>(heldNotes[track])) {
            handleNoteOff(track, n);
          }
        }
        break;
      case 123: // All notes off
        eventQueue.pushAllNotesOff();
        heldNotes[track].clear();
        break;
    }
  }

  private void handlePitchBend(int track, int bend) {
    activePitchBend[track] = bend;
    // Pitch bend range: -2 to +2 semitones (normal)
    // 0 = -8192, center = 8192 (0x2000), max = 16383
    double pitchOffset = (bend - 8192.0) / 8192.0 * 2.0; // +/- 2 semitones
    bridge.setStepPitch(track, 0, pitchOffset / 24.0); // normalize to bridge range
  }
}
