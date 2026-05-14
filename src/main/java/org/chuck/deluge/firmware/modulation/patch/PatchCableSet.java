package org.chuck.deluge.firmware.modulation.patch;

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
}
