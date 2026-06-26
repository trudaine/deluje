package org.deluge.modulation.patch;

import java.util.ArrayList;
import java.util.List;

public class PatchCableSet {
  public List<Destination> destinations = new ArrayList<>();

  public PatchCableSet() {
    // Initialization would set up standard destinations based on param counts
  }

  public void addCable(int paramId, PatchCable cable) {
    Destination dest = null;
    for (Destination d : destinations) {
      if (d.paramId == paramId) {
        dest = d;
        break;
      }
    }
    if (dest == null) {
      dest = new Destination(paramId);
      destinations.add(dest);
    }
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.from.ordinal());
  }

  public void addRangeCable(int targetParamId, PatchSource targetSource, PatchCable cable) {
    Destination dest = null;
    for (Destination d : destinations) {
      if (d.targetParamId == targetParamId && d.targetSource == targetSource.ordinal()) {
        dest = d;
        break;
      }
    }
    if (dest == null) {
      dest = new Destination(targetParamId);
      dest.targetSource = targetSource.ordinal();
      dest.targetParamId = targetParamId;
      destinations.add(dest);
    }
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.from.ordinal());
  }
}
