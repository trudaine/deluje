package org.deluge.ui.menu;

import org.deluge.hid.ActionResult;
import org.deluge.hid.FirmwareDisplay;

/** A menu item for toggling a boolean setting. */
public class ToggleMenuItem extends MenuItem {
  private boolean value;
  private java.util.function.Consumer<Boolean> listener;

  public ToggleMenuItem(String name, boolean initialValue) {
    super(name);
    this.value = initialValue;
  }

  public ToggleMenuItem(
      String name, boolean initialValue, java.util.function.Consumer<Boolean> listener) {
    super(name);
    this.value = initialValue;
    this.listener = listener;
  }

  @Override
  public void onFocus() {
    FirmwareDisplay.get().setText(name + ": " + (value ? "ON" : "OFF"));
  }

  @Override
  public ActionResult enter() {
    value = !value;
    if (listener != null) {
      listener.accept(value);
    }
    onFocus();
    return ActionResult.DEALT_WITH;
  }
}
