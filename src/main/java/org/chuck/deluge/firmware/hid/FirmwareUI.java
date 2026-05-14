package org.chuck.deluge.firmware.hid;

/** Base class for all UI components in the firmware. Ports the logic from ui.h. */
public abstract class FirmwareUI {
  public abstract ActionResult padAction(int x, int y, int velocity);

  public ActionResult buttonAction(int buttonId, boolean on) {
    return ActionResult.NOT_DEALT_WITH;
  }

  /**
   * Called by the system to allow the UI to paint the LED grid. This is the "Firmware as the Brain"
   * decoupling point.
   */
  public abstract void setLedStates();
}
