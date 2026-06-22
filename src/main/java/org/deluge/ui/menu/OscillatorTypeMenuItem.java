package org.deluge.ui.menu;

import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.hid.FirmwareDisplay;

/** A menu item for selecting the oscillator type. */
public class OscillatorTypeMenuItem extends MenuItem {
  private final FirmwareSound sound;
  private final int oscSlot; // 0 or 1

  public OscillatorTypeMenuItem(String name, FirmwareSound sound, int oscSlot) {
    super(name);
    this.sound = sound;
    this.oscSlot = oscSlot;
  }

  @Override
  public void onFocus() {
    OscType current = sound.oscTypes[oscSlot];
    FirmwareDisplay.get().setText(name + ": " + current.name());
  }

  @Override
  public void selectEncoderAction(int offset) {
    OscType current = sound.oscTypes[oscSlot];
    OscType[] types = OscType.values();
    int idx = current.ordinal();
    idx = (idx + offset + types.length) % types.length;
    sound.oscTypes[oscSlot] = types[idx];
    onFocus();
  }
}
