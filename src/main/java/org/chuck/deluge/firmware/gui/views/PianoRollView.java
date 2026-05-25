package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.hid.*;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.note.Note;
import org.chuck.deluge.firmware.model.note.NoteRow;

/** Port of the Deluge's PianoRollView (InstrumentClipView). Bit-accurate note editing logic. */
public class PianoRollView extends FirmwareView {
  private final InstrumentClip clip;
  private int scrollY = 60; // Middle C
  private int scrollX = 0;

  public PianoRollView(InstrumentClip clip) {
    this.clip = clip;
  }

  @Override
  public void selectEncoderAction(int offset) {
    scrollY = Math.max(0, Math.min(127, scrollY - offset));
  }

  @Override
  public ActionResult selectButtonPress(boolean on) {
    if (on && clip.sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
      MatrixDriver.get()
          .pushUI(
              new MenuView(org.chuck.deluge.firmware.gui.menu.SoundEditor.createRootMenu(synth)));
      return ActionResult.DEALT_WITH;
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (x < 16 && y < 8) {
      int stepTicks = clip.tripletMode ? 32 : 24;
      int notePos = scrollX + x * stepTicks;
      int notePitch = scrollY - y;

      if (velocity > 0) {
        // ── Bit-Accurate Audition ──
        if (clip.sound != null) {
          if (clip.sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound) {
            ((org.chuck.deluge.firmware.engine.FirmwareSound) clip.sound)
                .triggerNote(notePitch, velocity);
          }
        }

        // Toggle note
        NoteRow row = null;
        for (NoteRow r : clip.noteRows) {
          if (r.y == notePitch) {
            row = r;
            break;
          }
        }

        if (row == null) {
          row = new NoteRow(notePitch);
          clip.noteRows.add(row);
        }

        boolean found = false;
        for (Note n : row.notes) {
          if (n.pos == notePos) {
            row.notes.remove(n);
            found = true;
            break;
          }
        }

        if (!found) {
          row.attemptNoteAdd(notePos, stepTicks, velocity, 100, null, 0);
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
    int stepTicks = clip.tripletMode ? 32 : 24;
    for (int x = 0; x < 16; x++) {
      int notePos = scrollX + x * stepTicks;
      for (int y = 0; y < 8; y++) {
        int pitch = scrollY - y;
        NoteRow row = null;
        for (NoteRow r : clip.noteRows) {
          if (r.y == pitch) {
            row = r;
            break;
          }
        }
        if (row != null) {
          for (Note n : row.notes) {
            if (n.pos == notePos) {
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
