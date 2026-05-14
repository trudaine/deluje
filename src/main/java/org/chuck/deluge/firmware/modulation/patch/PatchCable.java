package org.chuck.deluge.firmware.modulation.patch;

import org.chuck.deluge.firmware.modulation.automation.AutoParam;

public class PatchCable {
  public PatchSource from = PatchSource.NONE;
  public int amount; // Q31
  public AutoParam param = new AutoParam(0); // Automated amount

  public int getAmount() {
    // In full firmware, this would combine static amount + automation
    return amount;
  }
}
