package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.hid.*;

/** Port of the Deluge's PerformanceView. Bit-accurate 8x8 macro performance grid. */
public class PerformanceView extends FirmwareView {
  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (velocity > 0) {
      FirmwareDisplay.get().displayPopup("MACRO " + x + ":" + y);
      return ActionResult.DEALT_WITH;
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();
    for (int x = 0; x < 8; x++) {
      for (int y = 0; y < 8; y++) {
        PadLEDs.set(x, y, new RGB(255, 0, 255)); // Pink for macros
      }
    }
  }
}
