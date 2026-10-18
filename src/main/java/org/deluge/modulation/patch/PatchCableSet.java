package org.deluge.modulation.patch;

import java.util.ArrayList;
import java.util.List;

public class PatchCableSet {
  public List<Destination> destinations = new ArrayList<>();

  public PatchCableSet() {
    // Initialization would set up standard destinations based on param counts
  }

  public void addCable(int paramId, PatchCable cable) {
    // C patch_cable_set.cpp:414-418, 902, 915-921 — ignore NONE and deduplicate (from, paramId).
    if (cable == null || cable.from == null || cable.from == PatchSource.NONE) return;
    Destination dest = null;
    for (Destination d : destinations) {
      if (d.paramId == paramId && d.targetSource < 0) {
        dest = d;
        break;
      }
    }
    if (dest == null) {
      dest = new Destination(paramId);
      destinations.add(dest);
    }
    dest.cables.removeIf(existing -> existing.from == cable.from);
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.from.ordinal());
  }

  public void addRangeCable(int targetParamId, PatchSource targetSource, PatchCable cable) {
    // C patch_cable_set.cpp:879-885 — ignore NONE and deduplicate (from, targetParam,
    // targetSource).
    if (cable == null
        || cable.from == null
        || cable.from == PatchSource.NONE
        || targetSource == null
        || targetSource == PatchSource.NONE) {
      return;
    }
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
    dest.cables.removeIf(existing -> existing.from == cable.from);
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.from.ordinal());
  }
}
