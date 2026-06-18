package org.deluge.firmware.modulation.patch;

import java.util.ArrayList;
import java.util.List;

public class Destination {
  public int paramId;
  public int sourcesMask; // bitmask of PatchSource
  public final List<PatchCable> cables = new ArrayList<>();

  public Destination(int paramId) {
    this.paramId = paramId;
  }
}
