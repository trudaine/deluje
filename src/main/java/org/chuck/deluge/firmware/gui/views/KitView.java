package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.hid.*;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.note.Note;
import org.chuck.deluge.firmware.model.note.NoteRow;

/** Port of the Deluge's Kit View. Each row (Y) represents a different drum sound in the kit. */
public class KitView extends FirmwareView {
  private final InstrumentClip clip;
  private int scrollX = 0;
  private int currentRow = 0;

  public KitView(InstrumentClip clip) {
    this.clip = clip;
  }

  @Override
  public void selectEncoderAction(int offset) {
    currentRow = Math.clamp(currentRow + offset, 0, 7);
  }

  @Override
  public ActionResult selectButtonPress(boolean on) {
    if (on && clip.sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
      if (currentRow < kit.drumSounds.size()) {
        org.chuck.deluge.firmware.engine.FirmwareSound drum = kit.drumSounds.get(currentRow);
        MatrixDriver.get()
            .pushUI(
                new MenuView(org.chuck.deluge.firmware.gui.menu.SoundEditor.createRootMenu(drum)));
        return ActionResult.DEALT_WITH;
      }
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (x >= 0 && x < 18 && y >= 0 && y < 8) {
      if (velocity > 0) {
        currentRow = y; // Auto-focus row on click
        // ── Bit-Accurate Audition ──
        if (clip.sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
          kit.triggerDrum(y, velocity);
        }

        // ... (Sequence Toggle)
        if (x < 16) {
          int notePos = scrollX + x * 24;
          if (y < clip.noteRows.size()) {
            NoteRow row = clip.noteRows.get(y);
            boolean found = false;
            for (Note n : row.notes) {
              if (n.pos == notePos) {
                row.notes.remove(n);
                found = true;
                break;
              }
            }
            if (!found) {
              row.attemptNoteAdd(notePos, 24, velocity, 100, null, 0);
            }
          }
        }
        return ActionResult.DEALT_WITH;
      }
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();

    // Draw drum row status in sidebar
    for (int y = 0; y < 8; y++) {
      RGB color = (y == currentRow) ? new RGB(255, 255, 255) : new RGB(255, 100, 0);
      PadLEDs.set(16, y, color);
    }

    // Draw notes in the grid
    int stepTicks = clip.tripletMode ? 32 : 24;
    for (int y = 0; y < Math.min(8, clip.noteRows.size()); y++) {
      NoteRow row = clip.noteRows.get(y);
      for (Note n : row.notes) {
        int x = (n.pos - scrollX) / stepTicks;
        if (x >= 0 && x < 16) {
          PadLEDs.set(x, y, new RGB(255, 255, 0)); // Yellow for drum hits
        }
      }
    }
  }
}
