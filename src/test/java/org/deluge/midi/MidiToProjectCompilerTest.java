package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.*;
import org.deluge.midi.MidiToProjectCompiler.TrackImportConfig;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.TrackModel;
import org.junit.jupiter.api.Test;

public class MidiToProjectCompilerTest {

  @Test
  public void testMidiCompilationAndMetadata() throws Exception {
    // 1. Programmatically build a standard MIDI sequence
    // Resolution = 96 ticks per quarter note (PPQ)
    Sequence sequence = new Sequence(Sequence.PPQ, 96);
    Track track = sequence.createTrack();

    // A. Track Name Meta Event
    MetaMessage nameMessage = new MetaMessage();
    String trackName = "Synth Lead Track";
    nameMessage.setMessage(0x03, trackName.getBytes(), trackName.length());
    track.add(new MidiEvent(nameMessage, 0));

    // B. Tempo Meta Event (120 BPM = 500,000 microseconds per quarter note)
    MetaMessage tempoMessage = new MetaMessage();
    int tempo = 500000;
    byte[] tempoData =
        new byte[] {
          (byte) ((tempo >> 16) & 0xFF), (byte) ((tempo >> 8) & 0xFF), (byte) (tempo & 0xFF)
        };
    tempoMessage.setMessage(0x51, tempoData, 3);
    track.add(new MidiEvent(tempoMessage, 0));

    // C. Add Note 1: C4 (Note 60), velocity 100, start = beat 0 (tick 0), duration = 1 beat (96
    // ticks)
    ShortMessage noteOn1 = new ShortMessage(ShortMessage.NOTE_ON, 0, 60, 100);
    ShortMessage noteOff1 = new ShortMessage(ShortMessage.NOTE_OFF, 0, 60, 0);
    track.add(new MidiEvent(noteOn1, 0));
    track.add(new MidiEvent(noteOff1, 96));

    // D. Add Note 2: E4 (Note 64), velocity 90, start = beat 1 (tick 96), duration = 2 beats (192
    // ticks)
    ShortMessage noteOn2 = new ShortMessage(ShortMessage.NOTE_ON, 0, 64, 90);
    ShortMessage noteOff2 = new ShortMessage(ShortMessage.NOTE_OFF, 0, 64, 0);
    track.add(new MidiEvent(noteOn2, 96));
    track.add(new MidiEvent(noteOff2, 288));

    // E. Add Note 3: Bass Note C2 (Note 36), velocity 110, start = beat 0 (tick 0), duration = 3
    // beats (288 ticks)
    ShortMessage noteOn3 = new ShortMessage(ShortMessage.NOTE_ON, 0, 36, 110);
    ShortMessage noteOff3 = new ShortMessage(ShortMessage.NOTE_OFF, 0, 36, 0);
    track.add(new MidiEvent(noteOn3, 0));
    track.add(new MidiEvent(noteOff3, 288));

    // Write MIDI sequence to a temporary file
    File tempMidi = File.createTempFile("junit_test_midi", ".mid");
    tempMidi.deleteOnExit();
    MidiSystem.write(sequence, 1, tempMidi);

    // 2. Validate Metadata Extraction
    List<TrackImportConfig> metadata = MidiToProjectCompiler.parseMidiMetadata(tempMidi);
    assertEquals(1, metadata.size());
    TrackImportConfig config = metadata.get(0);
    assertTrue(config.trackName.contains("Synth Lead Track"));
    assertTrue(config.trackName.contains("3 notes"));
    assertTrue(config.importEnabled);

    // 3. Test Compilation without splitting
    List<TrackImportConfig> configs = new ArrayList<>();
    config.mappedPresetName = "073 Piano";
    config.colorHex = "#FF0055"; // Red/Pink
    config.splitEnabled = false;
    configs.add(config);

    ProjectModel project = MidiToProjectCompiler.compileMidi(tempMidi, configs);
    assertEquals(120.0f, project.getBpm(), 0.01);
    assertEquals(1, project.getTracks().size());

    TrackModel compiledTrack = project.getTracks().get(0);
    assertEquals("073 Piano", compiledTrack.getName());
    assertEquals("#FF0055", compiledTrack.getColourHex());

    assertEquals(1, compiledTrack.getClips().size());
    ClipModel clip = compiledTrack.getClips().get(0);
    assertEquals("Clip 1", clip.getName());
    assertEquals(3, clip.getRowCount()); // Three unique pitches: 64, 60, 36

    // Verify row pitches are mapped correctly
    assertEquals(64, clip.getRowYNote(0));
    assertEquals(60, clip.getRowYNote(1));
    assertEquals(36, clip.getRowYNote(2));

    // Verify quantization and high-res event counts
    assertEquals(1, clip.getRawNoteEvents(0).size()); // E4
    assertEquals(1, clip.getRawNoteEvents(1).size()); // C4
    assertEquals(1, clip.getRawNoteEvents(2).size()); // C2

    // 4. Test Compilation WITH Pitch/Zone Splitting
    config.splitEnabled = true;
    config.splitPoint = 50; // C2 (36) is below 50 (Bass), C4 (60) & E4 (64) are above 50 (Lead)
    config.mappedPresetName = "073 Piano"; // Lead Preset
    config.splitPresetName = "001 Sync Bass"; // Bass Preset
    config.colorHex = "#FF00AA"; // Lead Color (Magenta)
    config.splitColorHex = "#0055FF"; // Bass Color (Blue)

    ProjectModel splitProject = MidiToProjectCompiler.compileMidi(tempMidi, configs);
    // Should split into 2 separate tracks!
    assertEquals(2, splitProject.getTracks().size());

    TrackModel track1 = splitProject.getTracks().get(0); // Bass Track (created first)
    assertEquals("001 Sync Bass", track1.getName());
    assertEquals("#0055FF", track1.getColourHex());
    assertEquals(1, track1.getClips().get(0).getRowCount()); // Only 1 bass pitch: 36

    TrackModel track2 = splitProject.getTracks().get(1); // Lead Track
    assertEquals("073 Piano", track2.getName());
    assertEquals("#FF00AA", track2.getColourHex());
    assertEquals(2, track2.getClips().get(0).getRowCount()); // 2 lead pitches: 64, 60
  }
}
