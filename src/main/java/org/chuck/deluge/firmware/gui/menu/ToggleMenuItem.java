package org.chuck.deluge.firmware.gui.menu;

import org.chuck.deluge.firmware.hid.ActionResult;
import org.chuck.deluge.firmware.hid.FirmwareDisplay;

/** A menu item for toggling a boolean setting. */
public class ToggleMenuItem extends MenuItem {
  private boolean value;

  public ToggleMenuItem(String name, boolean initialValue) {
    super(name);
    this.value = initialValue;
  }

  @Override
  public void onFocus() {
    FirmwareDisplay.get().setText(name + ": " + (value ? "ON" : "OFF"));
  }

  @Override
  public ActionResult enter() {
    value = !value;
    onFocus();
    return ActionResult.DEALT_WITH;
  }
}
