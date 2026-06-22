package org.deluge.hid;

/** Base class for all UI components in the firmware. Ports the logic from ui.h. */
public abstract class FirmwareUI {
  public abstract ActionResult padAction(int x, int y, int velocity);

  public ActionResult buttonAction(Button b, boolean on) {
    return ActionResult.NOT_DEALT_WITH;
  }

  public void horizontalEncoderAction(int offset) {}

  public void verticalEncoderAction(int offset) {}

  public void selectEncoderAction(int offset) {}

  public ActionResult selectButtonPress(boolean on) {
    return ActionResult.NOT_DEALT_WITH;
  }

  /**
   * Called by the system to allow the UI to paint the LED grid. This is the "Firmware as the Brain"
   * decoupling point.
   */
  public abstract void setLedStates();
}
