package org.chuck.deluge.engine;

import org.chuck.deluge.BridgeContract;
import org.rtmidijava.RtMidiFactory;
import org.rtmidijava.RtMidiIn;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;

/**
 * Pure Java MIDI input router using rtmidijava (native).
 *
 * <p>Replaces NativeMidiInputRouter (which used javax.sound.midi) with
 * {@link org.rtmidijava.RtMidiIn} via the project's existing rtmidijava
 * dependency. Uses the fast callback API for low-latency message delivery.
 *
 * <p>Receives note-on/off, CC, and pitch bend messages, routing them to
 * voice triggers in the engine via the {@link TickEventQueue}.
 */
public class RtMidiInputRouter {

    private final BridgeContract bridge;
    private final TickEventQueue eventQueue;
    private final VoiceAllocator voiceAllocator;

    private RtMidiIn midiIn;
    private volatile boolean running = false;

    /** Per-track arpeggiator state. */
    private final List<Integer>[] heldNotes = new List[BridgeContract.TRACKS];
    private final int[] activePitchBend = new int[BridgeContract.TRACKS];
    private final Map<Integer, Long> noteOnTimes = new HashMap<>();

    private int activeTrack = 0;
    private boolean followMode = true;

    @SuppressWarnings("unchecked")
    public RtMidiInputRouter(BridgeContract bridge, TickEventQueue eventQueue, VoiceAllocator voiceAllocator) {
        this.bridge = bridge;
        this.eventQueue = eventQueue;
        this.voiceAllocator = voiceAllocator;
        for (int i = 0; i < BridgeContract.TRACKS; i++) {
            heldNotes[i] = new ArrayList<>();
            activePitchBend[i] = 64; // center
        }
    }

    public void setActiveTrack(int track) { this.activeTrack = track; }
    public void setFollowMode(boolean f) { this.followMode = f; }

    /** Enumerate available MIDI input devices. */
    public static List<String> listMidiInputDevices() {
        List<String> names = new ArrayList<>();
        try {
            RtMidiIn in = RtMidiFactory.createDefaultIn();
            int count = in.getPortCount();
            for (int i = 0; i < count; i++) {
                names.add(in.getPortName(i));
            }
            in.closePort();
        } catch (Exception ignored) {
        }
        return names;
    }

    /**
     * Open the first available MIDI input device.
     * @return true if a device was opened
     */
    public boolean openFirstDevice() {
        try {
            RtMidiIn in = RtMidiFactory.createDefaultIn();
            int count = in.getPortCount();
            if (count > 0) {
                String name = in.getPortName(0);
                in.setFastCallback(this::onMidiMessage);
                in.openPort(0, "Deluge-Java");
                midiIn = in;
                System.out.println("[RtMidiInputRouter] Opened device: " + name);
                return true;
            }
        } catch (Exception ignored) {
        }
        System.out.println("[RtMidiInputRouter] No MIDI input devices found.");
        return false;
    }

    /**
     * Open a specific MIDI input device by name (partial match).
     */
    public boolean openDevice(String nameFragment) {
        try {
            RtMidiIn in = RtMidiFactory.createDefaultIn();
            int count = in.getPortCount();
            for (int i = 0; i < count; i++) {
                String portName = in.getPortName(i);
                if (portName.toLowerCase().contains(nameFragment.toLowerCase())) {
                    in.setFastCallback(this::onMidiMessage);
                    in.openPort(i, "Deluge-Java");
                    midiIn = in;
                    System.out.println("[RtMidiInputRouter] Opened device: " + portName);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return openFirstDevice();
    }

    public void start() {
        if (midiIn == null) return;
        if (running) return;
        running = true;
    }

    public void stop() {
        running = false;
        if (midiIn != null) {
            try {
                midiIn.closePort();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isRunning() { return running; }

    /**
     * Fast callback from rtmidijava. Called on the native MIDI thread.
     */
    private void onMidiMessage(double timestamp, MemorySegment message) {
        if (!running) return;
        long byteLen = message.byteSize();
        if (byteLen < 1) return;

        byte status = message.get(ValueLayout.JAVA_BYTE, 0);
        int cmd = status & 0xF0;
        int channel = status & 0x0F;

        if (byteLen < 2) return;
        int data1 = message.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
        int data2 = byteLen >= 3 ? (message.get(ValueLayout.JAVA_BYTE, 2) & 0xFF) : 0;

        switch (cmd) {
            case 0x90 -> { // NOTE_ON
                if (data2 == 0) {
                    handleNoteOff(data1);
                } else {
                    handleNoteOn(data1, data2);
                }
            }
            case 0x80 -> handleNoteOff(data1); // NOTE_OFF
            case 0xB0 -> handleControlChange(data1, data2); // CC
            case 0xE0 -> handlePitchBend(data1, data2); // PITCH_BEND (lsb, msb)
        }
    }

    private int resolveTrack() {
        if (followMode) {
            return activeTrack;
        }
        return activeTrack;
    }

    private void handleNoteOn(int note, int velocity) {
        int track = resolveTrack();
        noteOnTimes.put(note, System.nanoTime());
        heldNotes[track].add(note);

        int trackType = bridge.getTrackType(track);

        TickEventQueue.Command cmd;
        if (trackType == 0) {
            cmd = TickEventQueue.Command.SAMPLE_NOTE_ON;
        } else if (bridge.getSynthAlgo(track) > 0) {
            cmd = TickEventQueue.Command.DX7_NOTE_ON;
        } else {
            cmd = TickEventQueue.Command.SYNTH_NOTE_ON;
        }

        TickEventQueue.Event template = new TickEventQueue.Event();
        template.set(cmd, track, note, velocity, -1);
        template.gain = velocity / 127.0f * 0.15f;
        template.cutoff = bridge.getTrackFilterFreq(track) * 15000.0 + 20.0;
        template.resonance = bridge.getTrackFilterRes(track);

        if (trackType == 0) {
            template.setSample(bridge.getSamplePath(track));
        }

        int eb = (track * BridgeContract.ENV_COUNT + 0) * BridgeContract.ENV_PARAMS;
        float[] env = bridge.getEnvRaw();
        if (env != null && eb + 3 < env.length) {
            template.setADSR(env[eb], env[eb + 1], env[eb + 2], env[eb + 3]);
        }
        eventQueue.push(template);
    }

    private void handleNoteOff(int note) {
        int track = resolveTrack();
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

    private void handleControlChange(int cc, int value) {
        int track = resolveTrack();
        double normalized = value / 127.0;

        switch (cc) {
            case 7:  // Volume
                bridge.setTrackLevel(track, normalized);
                break;
            case 10: // Pan
                bridge.setTrackLevel(track, normalized * 2.0 - 1.0);
                break;
            case 74: // Filter cutoff
                bridge.setFilterFreq(track, normalized);
                break;
            case 71: // Filter resonance
                bridge.setFilterRes(track, normalized);
                break;
            case 64: // Sustain pedal
                if (value < 64) {
                    for (int n : new ArrayList<>(heldNotes[track])) {
                        handleNoteOff(n);
                    }
                }
                break;
            case 123: // All notes off
                eventQueue.pushAllNotesOff();
                heldNotes[track].clear();
                break;
        }
    }

    private void handlePitchBend(int lsb, int msb) {
        int track = resolveTrack();
        int bend = (msb << 7) | lsb;
        activePitchBend[track] = bend;
        double pitchOffset = (bend - 8192.0) / 8192.0 * 2.0; // +/- 2 semitones
        bridge.setStepPitch(track, 0, pitchOffset / 24.0);
    }
}
