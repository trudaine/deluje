package org.deluge.ui.views;

import org.deluge.hid.*;
import org.deluge.model.ClipModel;
import org.deluge.model.NoteModel;
import org.deluge.model.NoteRowModel;

/** Port of the Deluge's PianoRollView (InstrumentClipView). Bit-accurate note editing logic. */
public class PianoRollView extends FirmwareView {
  private final ClipModel clip;
  private int scrollY = 60; // Middle C
  private int scrollX = 0;

  public PianoRollView(ClipModel clip) {
    this.clip = clip;
  }

  @Override
  public void selectEncoderAction(int offset) {
    scrollY = Math.max(0, Math.min(127, scrollY - offset));
  }

  @Override
  public ActionResult selectButtonPress(boolean on) {
    if (on && clip.getSound() instanceof org.deluge.engine.FirmwareSound synth) {
      MatrixDriver.get().pushUI(new MenuView(org.deluge.ui.menu.SoundEditor.createRootMenu(synth)));
      return ActionResult.DEALT_WITH;
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (x < 16 && y < 8) {
      int stepTicks = clip.isTripletMode() ? 32 : 24;
      int notePos = scrollX + x * stepTicks;
      int notePitch = scrollY - y;

      if (velocity > 0) {
        // ── Bit-Accurate Audition ──
        if (clip.getSound() != null) {
          if (clip.getSound() instanceof org.deluge.engine.FirmwareSound) {
            ((org.deluge.engine.FirmwareSound) clip.getSound()).triggerNote(notePitch, velocity);
          }
        }

        // Toggle note
        NoteRowModel row = null;
        for (NoteRowModel r : clip.getNoteRowsList()) {
          if (r.getPitch() == notePitch) {
            row = r;
            break;
          }
        }

        if (row == null) {
          row = clip.getOrCreateRow(notePitch);
          row.setPitch(notePitch);
        }

        boolean found = false;
        for (NoteModel n : row.getNotes()) {
          if (n.getPos() == notePos) {
            row.getNotes().remove(n);
            found = true;
            break;
          }
        }

        if (!found) {
          row.attemptNoteAdd(notePos, stepTicks, velocity, 100, new org.deluge.model.Iterance(), 0);
        }
        return ActionResult.DEALT_WITH;
      }
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();

    // Draw piano keys in sidebar
    for (int y = 0; y < 8; y++) {
      int pitch = scrollY - y;
      boolean isBlack = isBlackKey(pitch);
      PadLEDs.set(16, y, isBlack ? new RGB(10, 10, 10) : new RGB(200, 200, 200));
    }

    // Draw notes
    int stepTicks = clip.isTripletMode() ? 32 : 24;
    for (int x = 0; x < 16; x++) {
      int notePos = scrollX + x * stepTicks;
      for (int y = 0; y < 8; y++) {
        int pitch = scrollY - y;
        NoteRowModel row = null;
        for (NoteRowModel r : clip.getNoteRowsList()) {
          if (r.getPitch() == pitch) {
            row = r;
            break;
          }
        }
        if (row != null) {
          for (NoteModel n : row.getNotes()) {
            if (n.getPos() == notePos) {
              PadLEDs.set(x, y, new RGB(0, 255, 255)); // Cyan for notes
            }
          }
        }
      }
    }
  }

  private boolean isBlackKey(int pitch) {
    int note = pitch % 12;
    return note == 1 || note == 3 || note == 6 || note == 8 || note == 10;
  }
}
