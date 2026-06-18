package org.deluge.firmware.gui.menu;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware.hid.ActionResult;
import org.deluge.firmware.hid.Button;
import org.deluge.firmware.hid.FirmwareDisplay;

/** A menu item that opens a submenu when selected. Navigable via the select encoder. */
public class Submenu extends MenuItem {
  private final List<MenuItem> items = new ArrayList<>();
  private int focusIndex = 0;

  public Submenu(String name) {
    super(name);
  }

  public void addItem(MenuItem item) {
    items.add(item);
  }

  public MenuItem getFocusedItem() {
    if (items.isEmpty()) return null;
    return items.get(focusIndex);
  }

  @Override
  public void selectEncoderAction(int offset) {
    if (items.isEmpty()) return;
    focusIndex = (focusIndex + offset) % items.size();
    if (focusIndex < 0) focusIndex += items.size();

    MenuItem focused = items.get(focusIndex);
    focused.onFocus();
    FirmwareDisplay.get().setText(focused.title);
  }

  @Override
  public ActionResult enter() {
    if (items.isEmpty()) return ActionResult.DEALT_WITH;
    return items.get(focusIndex).enter();
  }

  @Override
  public ActionResult buttonAction(Button b, boolean on) {
    if (items.isEmpty()) return ActionResult.NOT_DEALT_WITH;
    return items.get(focusIndex).buttonAction(b, on);
  }
}
