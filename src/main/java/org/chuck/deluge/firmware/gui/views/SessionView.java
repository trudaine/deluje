package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.hid.*;
import org.chuck.deluge.firmware.model.Song;

/** Port of the Deluge's SessionView class. Handles the "Clip launch" grid logic. */
public class SessionView extends FirmwareView {
  private final Song song;

  public SessionView(Song song) {
    this.song = song;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (velocity > 0) {
      // Logic for launching a clip at (x, y) would go here
      FirmwareDisplay.get().displayPopup("LAUNCH " + x + ":" + y);
      return ActionResult.DEALT_WITH;
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();

    // Draw track status in the sidebar
    for (int y = 0; y < 8; y++) {
      // Blink if recording, solid if playing
      boolean recording = false; // dummy
      if (recording && !PadLEDs.getFlashFast()) continue;

      RGB trackColor = new RGB(0, 255, 0); // Green for active
      PadLEDs.set(16, y, trackColor);
    }

    // Draw clip launch pads
    int stepTicks = 24;
    int stepCount = 16;
    if (song != null && !song.clips.isEmpty()) {
      stepTicks = song.clips.get(0).tripletMode ? 32 : 24;
      stepCount = song.clips.get(0).tripletMode ? 12 : 16;
    }
    for (int x = 0; x < 16; x++) {
      for (int y = 0; y < 8; y++) {
        // If this is the current playhead position, blink white
        boolean isPlayhead = (x == (song.lastSwungTickActioned / stepTicks) % stepCount);
        if (isPlayhead) {
          if (PadLEDs.getFlashFast()) {
            PadLEDs.set(x, y, new RGB(255, 255, 255));
          } else {
            PadLEDs.set(x, y, new RGB(64, 64, 64));
          }
        } else {
          PadLEDs.set(x, y, new RGB(32, 32, 32)); // Dim gray for empty
        }
      }
    }
  }
}
