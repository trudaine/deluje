package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ClipModel;
import org.deluge.model.ClipType;
import org.deluge.model.NoteRowModel;
import org.deluge.model.StepData;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the "garbage when adding a cell while playing" bug. The live grid rebuild in
 * SwingGridPanel rebuilds the playing clip's {@code noteRows} on every edit. It used to compute the
 * synth pitch inline as the grid row index ({@code (rowCount-1)-r}), ignoring the row's absolute
 * {@code yNote}. For a real song (rows carry their MIDI note) that turned every live-added note
 * into a ~8-12Hz sub-bass note (pitch 0..7) — clean-but-wrong audio that measured fine yet sounded
 * like garbage, fixed only by stop/play (which rebuilds via the factory). Both paths now share
 * {@link ClipModel#setStep} and {@link ClipModel#syncNoteRowsFromGrid} pitch resolution so they
 * cannot diverge again.
 */
class LiveRebuildPitchTest {

  @Test
  void synthRowUsesAbsoluteYNoteNotGridRowIndex() {
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setRowYNote(0, 60); // row carries real MIDI note C4 = 60
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));

    NoteRowModel row = clip.getOrCreateRow(0);

    assertEquals(60, row.getPitch(), "synth row must trigger its yNote (60), not the row index");
    assertFalse(row.getNotes().isEmpty(), "the active step must produce a note");
  }

  @Test
  void synthRowNeverCollapsesToSubBassWhenSongCarriesRealNotes() {
    // Eight melodic rows (C4 upward), one active step each — exactly what a loaded song looks like.
    ClipModel clip = new ClipModel("c", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip.setRowYNote(r, 60 + r);
      clip.setStep(r, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
      NoteRowModel row = clip.getOrCreateRow(r);
      assertEquals(60 + r, row.getPitch());
      assertTrue(
          row.getPitch() >= 36,
          "live-rebuilt synth note must be audible, not sub-bass row-index pitch ("
              + row.getPitch()
              + ")");
    }
  }

  @Test
  void explicitPerStepPitchOverridesRowFallback() {
    ClipModel clip = new ClipModel("c", 8, 16); // no yNote set
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 72)); // explicit pitch C5

    NoteRowModel row = clip.getOrCreateRow(0);

    assertEquals(72, row.getPitch(), "explicit per-step pitch must win over the grid fallback");
  }

  @Test
  void kitRowKeepsDrumIndexAsPitch() {
    ClipModel clip = new ClipModel("c", 4, 16);
    clip.setType(ClipType.INSTRUMENT);
    clip.sound = new FirmwareKit();
    clip.setRowYNote(2, 60); // even if a yNote is present, a kit row maps by drum index
    clip.setStep(2, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));

    NoteRowModel row = clip.getOrCreateRow(2);

    assertEquals(2, row.getPitch(), "kit row pitch is the drum/lane index, not a MIDI note");
  }

  @Test
  void fallbackGridConventionWhenNoYNote() {
    ClipModel clip = new ClipModel("c", 8, 16); // no yNote, no explicit pitch
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));

    NoteRowModel row = clip.getOrCreateRow(0);

    assertEquals(7, row.getPitch(), "with no yNote, synth falls back to (rowCount-1)-r");
  }
}
