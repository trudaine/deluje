package org.deluge.modulation.patch;

import org.deluge.modulation.automation.AutoParam;

public class PatchCable {
  public enum Polarity {
    UNIPOLAR,
    BIPOLAR
  }

  public PatchSource from = PatchSource.NONE;
  public int amount; // Q31
  public AutoParam param = new AutoParam(0); // Automated amount
  public Polarity polarity = Polarity.BIPOLAR;

  public int getAmount() {
    // In full firmware, this would combine static amount + automation
    return amount;
  }

  public int toPolarity(int value) {
    // aftertouch is stored unipolar, all others are stored bipolar
    if (from == PatchSource.AFTERTOUCH) {
      if (polarity == Polarity.UNIPOLAR) {
        return value;
      }
      return (value - 1073741823) << 1; // Convert from unipolar to bipolar
    }
    // Because unipolar mod wheel and bipolar MPE y share the same mod source we can't convert
    if (from == PatchSource.Y || polarity == Polarity.BIPOLAR) {
      return value;
    }
    return (value / 2) + 1073741823; // Convert from bipolar to unipolar
  }
}
