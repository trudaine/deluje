package org.deluge.ui.views;

import org.deluge.hid.ActionResult;
import org.deluge.hid.Button;
import org.deluge.hid.FirmwareView;
import org.deluge.hid.MatrixDriver;
import org.deluge.hid.PadLEDs;
import org.deluge.ui.menu.MenuItem;
import org.deluge.ui.menu.Submenu;

/**
 * A high-fidelity View for navigating menus. Replicates the Deluge's hardware menu navigation
 * logic.
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
