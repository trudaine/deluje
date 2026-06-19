package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware.model.InstrumentClip;
import org.deluge.firmware.model.Song;
import org.deluge.firmware.model.note.NoteRow;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies syncFirmwareToModel back-synchronization from the firmware sequencer model back into the
 * persistent Java ProjectModel layer.
 */
public class FirmwareFactorySyncTest {

  @Test
  public void testSyncFirmwareToModel() {
    // 1. Create baseline ProjectModel
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

    SynthTrackModel synth = new SynthTrackModel("Synth 1");
    ClipModel clip = new ClipModel("Clip 1", 8, 16);
    // Initialize steps as empty
    for (int r = 0; r < 8; r++) {
      for (int s = 0; s < 16; s++) {
        clip.setStep(r, s, StepData.empty());
      }
    }
    synth.addClip(clip);
    project.addTrack(synth);

    // 2. Initialize Layer 3 firmware model
    Song song = FirmwareFactory.createSong(project);
    assertEquals(1, song.clips.size());

    InstrumentClip fwClip = (InstrumentClip) song.clips.get(0);
    assertEquals(8, fwClip.noteRows.size());

    // 3. Find the NoteRow for pitch 5 (which exists in the 0-7 range and corresponds to
    // visual/model row index 2)
    NoteRow noteRow5 = null;
    for (NoteRow row : fwClip.noteRows) {
      if (row.y == 5) {
        noteRow5 = row;
        break;
      }
    }
    assertNotNull(noteRow5, "NoteRow for pitch 5 should exist");
    assertTrue(noteRow5.notes.isEmpty(), "NoteRow should initialize with empty notes");

    // 4. Simulate direct firmware-native PianoRoll edits (Layer 3 NoteRow mutations)
    // Add a spanned note: starts at step 4 (96 ticks), length = 2 steps (48 ticks => gate=2.0f)
    noteRow5.attemptNoteAdd(96, 48, 100, 100, new org.deluge.firmware.model.iterance.Iterance(), 0);
    assertEquals(1, noteRow5.notes.size());

    // Execute back-sync
    FirmwareFactory.syncFirmwareToModel(song, project);

    // Row index r for pitch 5 in a RowCount=8 clip is 7 - 5 = 2.
    int targetRow = 2;

    // Verify the start step of note span (step 4)
    StepData startStep = clip.getStep(targetRow, 4);
    assertTrue(startStep.active(), "Start step of note span must be active in model");
    assertEquals(2.0f, startStep.gate(), 0.01f, "Start step gate must sync to 2.0f");
    assertEquals(100 / 127.0f, startStep.velocity(), 0.01f);

    // Verify intermediate step (step 5) got marked empty but spanned (gate=0.0f, active=false)
    StepData intermediateStep = clip.getStep(targetRow, 5);
    assertFalse(intermediateStep.active(), "Intermediate step must not have a separate note-on");
    assertEquals(0.0f, intermediateStep.gate(), 0.01f);

    // Verify other steps remain empty
    StepData cleanStep = clip.getStep(targetRow, 3);
    assertFalse(cleanStep.active());
    assertEquals(0.9f, cleanStep.gate(), 0.01f); // StepData.empty() default click gate
  }
}
