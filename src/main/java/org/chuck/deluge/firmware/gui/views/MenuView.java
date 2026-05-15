package org.chuck.deluge.firmware.gui.views;

import org.chuck.deluge.firmware.gui.menu.MenuItem;
import org.chuck.deluge.firmware.gui.menu.Submenu;
import org.chuck.deluge.firmware.hid.ActionResult;
import org.chuck.deluge.firmware.hid.Button;
import org.chuck.deluge.firmware.hid.FirmwareView;
import org.chuck.deluge.firmware.hid.PadLEDs;
import org.chuck.deluge.firmware.hid.MatrixDriver;
import org.chuck.deluge.firmware.hid.FirmwareDisplay;

/**
 * A high-fidelity View for navigating menus.
 * Replicates the Deluge's hardware menu navigation logic.
 */
public class MenuView extends FirmwareView {
  private final Submenu menu;

  public MenuView(Submenu menu) {
    this.menu = menu;
  }

  @Override
  public ActionResult padAction(int x, int y, int velocity) {
    return ActionResult.NOT_DEALT_WITH;
  }

  @Override
  public void selectEncoderAction(int offset) {
    menu.selectEncoderAction(offset);
  }

  @Override
  public ActionResult selectButtonPress(boolean on) {
    if (on) {
      MenuItem focused = menu.getFocusedItem();
      if (focused instanceof Submenu) {
          MatrixDriver.get().pushUI(new MenuView((Submenu) focused));
          return ActionResult.DEALT_WITH;
      }
      return menu.enter();
    }
    return ActionResult.DEALT_WITH;
  }

  @Override
  public void horizontalEncoderAction(int offset) {
      menu.horizontalEncoderAction(offset);
  }

  @Override
  public ActionResult buttonAction(Button b, boolean on) {
    if (b == Button.BACK && on) {
        MatrixDriver.get().popUI();
        return ActionResult.DEALT_WITH;
    }
    return menu.buttonAction(b, on);
  }

  @Override
  public void setLedStates() {
    PadLEDs.clearAll();
  }
  
  public Submenu getMenu() {
      return menu;
  }
}
