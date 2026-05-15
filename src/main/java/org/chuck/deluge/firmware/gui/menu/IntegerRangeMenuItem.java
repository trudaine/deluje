package org.chuck.deluge.firmware.gui.menu;

import org.chuck.deluge.firmware.hid.FirmwareDisplay;

/** A menu item for selecting a value from an integer range. */
public class IntegerRangeMenuItem extends MenuItem {
  private int value;
  private final int min;
  private final int max;

  public IntegerRangeMenuItem(String name, int initial, int min, int max) {
    super(name);
    this.value = initial;
    this.min = min;
    this.max = max;
  }

  @Override
  public void onFocus() {
    FirmwareDisplay.get().setText(name + ": " + value);
  }

  @Override
  public void selectEncoderAction(int offset) {
    value = Math.max(min, Math.min(max, value + offset));
    onFocus();
  }
}
