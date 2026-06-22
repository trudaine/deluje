package org.deluge.ui.views;

import org.deluge.hid.*;
import org.deluge.model.ClipModel;
import org.deluge.model.NoteModel;
import org.deluge.model.NoteRowModel;

/** Port of the Deluge's Kit View. Each row (Y) represents a different drum sound in the kit. */
public class KitView extends FirmwareView {
  private final ClipModel clip;
  private int scrollX = 0;
  private int currentRow = 0;

  public KitView(ClipModel clip) {
    this.clip = clip;
  }

  @Override
  public void selectEncoderAction(int offset) {
    currentRow = Math.clamp(currentRow + offset, 0, 7);
  }

  @Override
  public ActionResult selectButtonPress(boolean on) {
    if (on && clip.getSound() instanceof org.deluge.engine.FirmwareKit kit) {
      if (currentRow < kit.drumSounds.size()) {
        org.deluge.engine.FirmwareSound drum = kit.drumSounds.get(currentRow);
        MatrixDriver.get()
            .pushUI(new MenuView(org.deluge.ui.menu.SoundEditor.createRootMenu(drum)));
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
        if (clip.getSound() instanceof org.deluge.engine.FirmwareKit kit) {
          kit.triggerDrum(y, velocity);
        }

        if (x < 16) {
          int notePos = scrollX + x * 24;
          if (y < clip.getNoteRowsList().size()) {
            NoteRowModel row = clip.getNoteRowsList().get(y);
            boolean found = false;
            for (NoteModel n : row.getNotes()) {
              if (n.getPos() == notePos) {
                row.getNotes().remove(n);
                found = true;
                break;
              }
            }
            if (!found) {
              row.attemptNoteAdd(notePos, 24, velocity, 100, new org.deluge.model.Iterance(), 0);
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

    for (int y = 0; y < 8; y++) {
      RGB color = (y == currentRow) ? new RGB(255, 255, 255) : new RGB(255, 100, 0);
      PadLEDs.set(16, y, color);
    }

    int stepTicks = clip.isTripletMode() ? 32 : 24;
    for (int y = 0; y < Math.min(8, clip.getNoteRowsList().size()); y++) {
      NoteRowModel row = clip.getNoteRowsList().get(y);
      for (NoteModel n : row.getNotes()) {
        int x = (n.getPos() - scrollX) / stepTicks;
        if (x >= 0 && x < 16) {
          PadLEDs.set(x, y, new RGB(255, 255, 0)); // Yellow for drum hits
        }
      }
    }
  }
}
