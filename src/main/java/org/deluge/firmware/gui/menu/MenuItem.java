package org.deluge.firmware.gui.menu;

import org.deluge.firmware.hid.ActionResult;
import org.deluge.firmware.hid.Button;

/**
 * Base class for all high-fidelity menu items. Replicates the behavior and interaction model of the
 * Deluge's MenuItem C++ class.
 */
public abstract class MenuItem {
  public final String name;
  public final String title;

  public MenuItem(String name) {
    this(name, name);
  }

  public MenuItem(String name, String title) {
    this.name = name;
    this.title = title;
  }

  /** Handle a button press. */
  public ActionResult buttonAction(Button b, boolean on) {
    return ActionResult.NOT_DEALT_WITH;
  }

  /** Handle horizontal encoder movement. */
  public void horizontalEncoderAction(int offset) {}

  /** Handle vertical encoder movement. */
  public void verticalEncoderAction(int offset) {}

  /** Handle select encoder movement. */
  public void selectEncoderAction(int offset) {}

  /** Returns true if the select encoder action edits the instrument. */
  public boolean selectEncoderActionEditsInstrument() {
    return false;
  }

  /** Called when the item is focused in a menu. */
  public void onFocus() {}

  /** Called when the item is entered (e.g. clicking the encoder). */
  public ActionResult enter() {
    return ActionResult.DEALT_WITH;
  }
}
