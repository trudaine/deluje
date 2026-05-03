package org.chuck.deluge.engine.dsp;

import javax.sound.midi.*;
import java.io.File;

/**
 * Pure Java implementation of MIDI export.
 */
public class NativeMidiExporter {
    private Sequence sequence;
    private Track track;
    private long currentTick = 0;
    private final int resolution = 480; // PPQ

    public NativeMidiExporter() {
        try {
            sequence = new Sequence(Sequence.PPQ, resolution);
            track = sequence.createTrack();
        } catch (InvalidMidiDataException e) {
            e.printStackTrace();
        }
    }

    public void addNote(int channel, int pitch, int velocity, long durationTicks) {
        try {
            ShortMessage on = new ShortMessage();
            on.setMessage(ShortMessage.NOTE_ON, channel, pitch, velocity);
            track.add(new MidiEvent(on, currentTick));

            ShortMessage off = new ShortMessage();
            off.setMessage(ShortMessage.NOTE_OFF, channel, pitch, 0);
            track.add(new MidiEvent(off, currentTick + durationTicks));
        } catch (InvalidMidiDataException e) {
            e.printStackTrace();
        }
    }

    public void advance(long ticks) {
        currentTick += ticks;
    }

    public void save(String path) {
        try {
            MidiSystem.write(sequence, 1, new File(path));
            System.out.println("[NativeMidiExporter] Saved MIDI to: " + path);
        } catch (Exception e) {
            System.err.println("[NativeMidiExporter] Error saving MIDI: " + e.getMessage());
        }
    }
}
