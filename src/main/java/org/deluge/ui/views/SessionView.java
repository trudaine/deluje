package org.deluge.ui.views;

import org.deluge.hid.*;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;

/** Port of the Deluge's SessionView class. Handles the "Clip launch" grid logic. */
public class SessionView extends FirmwareView {
  private final ProjectModel project;

  public SessionView(ProjectModel project) {
    this.project = project;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (velocity > 0) {
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
      boolean recording = false; // dummy
      if (recording && !PadLEDs.getFlashFast()) continue;

      RGB trackColor = new RGB(0, 255, 0); // Green for active
      PadLEDs.set(16, y, trackColor);
    }

    // Draw clip launch pads
    int stepTicks = 24;
    int stepCount = 16;
    if (project != null && !project.getTracks().isEmpty()) {
      org.deluge.model.TrackModel firstTrack = project.getTracks().get(0);
      if (firstTrack != null && !firstTrack.getClips().isEmpty()) {
        ClipModel firstClip = firstTrack.getClips().get(0);
        stepTicks = firstClip.isTripletMode() ? 32 : 24;
        stepCount = firstClip.isTripletMode() ? 12 : 16;
      }
    }
    for (int x = 0; x < 16; x++) {
      for (int y = 0; y < 8; y++) {
        boolean isPlayhead =
            (project != null && x == (project.getLastSwungTickActioned() / stepTicks) % stepCount);
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
