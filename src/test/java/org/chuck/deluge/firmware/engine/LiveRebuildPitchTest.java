package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.StepData;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the "garbage when adding a cell while playing" bug. The live grid rebuild in
 * SwingGridPanel rebuilds the playing clip's {@code noteRows} on every edit. It used to compute the
 * synth pitch inline as the grid row index ({@code (rowCount-1)-r}), ignoring the row's absolute
 * {@code yNote}. For a real song (rows carry their MIDI note) that turned every live-added note into
 * a ~8-12Hz sub-bass note (pitch 0..7) — clean-but-wrong audio that measured fine yet sounded like
 * garbage, fixed only by stop/play (which rebuilds via the factory). Both paths now share
 * {@link FirmwareFactory#buildNoteRow} so they cannot diverge again.
 */
class LiveRebuildPitchTest {

  @Test
  void synthRowUsesAbsoluteYNoteNotGridRowIndex() {
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setRowYNote(0, 60); // row carries real MIDI note C4 = 60
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));

    NoteRow row = FirmwareFactory.buildNoteRow(clip, 0, false, 24);

    assertEquals(60, row.getNoteCode(), "synth row must trigger its yNote (60), not the row index");
    assertFalse(row.notes.isEmpty(), "the active step must produce a note");
  }

  @Test
  void synthRowNeverCollapsesToSubBassWhenSongCarriesRealNotes() {
    // Eight melodic rows (C4 upward), one active step each — exactly what a loaded song looks like.
    ClipModel clip = new ClipModel("c", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip.setRowYNote(r, 60 + r);
      clip.setStep(r, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
      NoteRow row = FirmwareFactory.buildNoteRow(clip, r, false, 24);
      assertEquals(60 + r, row.getNoteCode());
      assertTrue(
          row.getNoteCode() >= 36,
          "live-rebuilt synth note must be audible, not sub-bass row-index pitch ("
              + row.getNoteCode()
              + ")");
    }
  }

  @Test
  void explicitPerStepPitchOverridesRowFallback() {
    ClipModel clip = new ClipModel("c", 8, 16); // no yNote set
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 72)); // explicit pitch C5

    NoteRow row = FirmwareFactory.buildNoteRow(clip, 0, false, 24);

    assertEquals(72, row.getNoteCode(), "explicit per-step pitch must win over the grid fallback");
  }

  @Test
  void kitRowKeepsDrumIndexAsPitch() {
    ClipModel clip = new ClipModel("c", 4, 16);
    clip.setRowYNote(2, 60); // even if a yNote is present, a kit row maps by drum index
    clip.setStep(2, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));

    NoteRow row = FirmwareFactory.buildNoteRow(clip, 2, true, 24);

    assertEquals(2, row.getNoteCode(), "kit row pitch is the drum/lane index, not a MIDI note");
  }

  @Test
  void fallbackGridConventionWhenNoYNote() {
    ClipModel clip = new ClipModel("c", 8, 16); // no yNote, no explicit pitch
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));

    NoteRow row = FirmwareFactory.buildNoteRow(clip, 0, false, 24);

    assertEquals(7, row.getNoteCode(), "with no yNote, synth falls back to (rowCount-1)-r");
  }
}
