package org.chuck.deluge.firmware.gui.menu;

import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.hid.FirmwareDisplay;
import org.chuck.deluge.firmware2.Oscillator.OscType;

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
