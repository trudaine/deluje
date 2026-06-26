package org.deluge.modulation.patch;

import java.util.ArrayList;
import java.util.List;

public class Destination {
  public int paramId;
  public int sourcesMask; // bitmask of PatchSource
  public final List<PatchCable> cables = new ArrayList<>();
  public int targetSource =
      -1; // if >= 0, this modulates the range/depth of targetSource -> targetParamId
  public int targetParamId = -1;

  public Destination(int paramId) {
    this.paramId = paramId;
  }
}
