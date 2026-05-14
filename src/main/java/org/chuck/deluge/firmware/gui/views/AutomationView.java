package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.hid.*;
import org.chuck.deluge.firmware.modulation.automation.AutoParam;

/**
 * Port of the Deluge's AutomationView. Handles drawing and editing parameter curves on the grid.
 */
public class AutomationView extends FirmwareView {
  private final AutoParam param;

  public AutomationView(AutoParam param) {
    this.param = param;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    if (velocity > 0) {
      int val = (7 - y) * (2147483647 / 8);
      param.addNode(x * 24, val);
      return ActionResult.DEALT_WITH;
    }
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();
    // Draw the curve
    for (int x = 0; x < 16; x++) {
      int val = param.getValueAt(x * 24);
      int y = 7 - (val / (2147483647 / 8));
      PadLEDs.set(x, y, new RGB(255, 128, 0)); // Amber for automation
    }
  }
}
