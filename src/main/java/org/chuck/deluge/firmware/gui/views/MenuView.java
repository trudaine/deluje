package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.gui.menu.MenuItem;
import org.chuck.deluge.firmware.gui.menu.Submenu;
import org.chuck.deluge.firmware.hid.ActionResult;
import org.chuck.deluge.firmware.hid.FirmwareView;
import org.chuck.deluge.firmware.hid.PadLEDs;

/**
 * A high-fidelity View for navigating menus.
 * Replicates the Deluge's hardware menu navigation logic.
 */
public class MenuView extends FirmwareView {
  private final Submenu rootMenu;

  public MenuView(Submenu rootMenu) {
    this.rootMenu = rootMenu;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    // Menus usually don't use pads for navigation, 
    // but some special menus might.
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void selectEncoderAction(int offset) {
    rootMenu.selectEncoderAction(offset);
  }

  @Override
  public ActionResult selectButtonPress(boolean on) {
    if (on) {
      return rootMenu.enter();
    }
    return ActionResult.DEALT_WITH;
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();
    // In menu mode, the grid might show shortcuts or be blank
  }
}
