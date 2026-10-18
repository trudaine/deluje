package org.deluge.modulation.patch;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Param;

public class PatchCableSet {
  public List<Destination> destinations = new ArrayList<>();

  public PatchCableSet() {
    // Initialization would set up standard destinations based on param counts
  }

  public void addCable(int paramId, PatchCable cable) {
    // C patch_cable_set.cpp:902-921 — ignore NONE, zero-amount unautomated cables,
    // X -> LOCAL_PITCH_ADJUST (briefly allowed in a 3.2.0 alpha), and keep the FIRST duplicate.
    if (cable == null || cable.from == null || cable.from == PatchSource.NONE) return;
    if (cable.amount == 0 && cable.param.nodes.isEmpty()) return;
    if (cable.from == PatchSource.X && paramId == Param.LOCAL_PITCH_ADJUST) return;

    Destination dest = findParamDestination(paramId);
    if (dest == null) {
      dest = new Destination(paramId);
      destinations.add(dest);
    }
    for (PatchCable existing : dest.cables) {
      if (existing.from == cable.from) {
        return; // C :915-921 (goto abandonThisCable) — first duplicate wins
      }
    }
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.from.ordinal());
  }

  /**
   * Updates an existing cable's amount in place or creates one if non-zero (C {@code
   * PatchCableSet::getPatchCableIndex(..., createIfNotFound)}, {@code
   * patch_cable_set.cpp:411-418}).
   */
  public void setCableAmount(PatchSource from, int paramId, int amount) {
    if (from == null || from == PatchSource.NONE) return;
    if (from == PatchSource.X && paramId == Param.LOCAL_PITCH_ADJUST) return;

    Destination dest = findParamDestination(paramId);
    if (dest != null) {
      for (int i = 0; i < dest.cables.size(); i++) {
        PatchCable existing = dest.cables.get(i);
        if (existing.from == from) {
          if (amount == 0 && existing.param.nodes.isEmpty()) {
            dest.cables.remove(i);
            recomputeSourcesMask(dest);
          } else {
            existing.amount = amount;
          }
          return;
        }
      }
    }
    if (amount != 0) {
      PatchCable cable = new PatchCable();
      cable.from = from;
      cable.amount = amount;
      addCable(paramId, cable);
    }
  }

  public void addRangeCable(int targetParamId, PatchSource targetSource, PatchCable cable) {
    // C patch_cable_set.cpp:879-885 — ignore NONE, zero-amount unautomated cables, and keep the
    // FIRST duplicate on (from, targetParam, targetSource).
    if (cable == null
        || cable.from == null
        || cable.from == PatchSource.NONE
        || targetSource == null
        || targetSource == PatchSource.NONE) {
      return;
    }
    if (cable.amount == 0 && cable.param.nodes.isEmpty()) return;

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
    for (PatchCable existing : dest.cables) {
      if (existing.from == cable.from) {
        return; // C :881-885 (goto doneWithThisRangeCable) — first duplicate wins
      }
    }
    dest.cables.add(cable);
    dest.sourcesMask |= (1 << cable.from.ordinal());
  }

  private Destination findParamDestination(int paramId) {
    for (Destination d : destinations) {
      if (d.paramId == paramId && d.targetSource < 0) {
        return d;
      }
    }
    return null;
  }

  private static void recomputeSourcesMask(Destination dest) {
    int mask = 0;
    for (PatchCable c : dest.cables) {
      mask |= (1 << c.from.ordinal());
    }
    dest.sourcesMask = mask;
  }

  /** C {@code PatchCableSet::readPatchCablesFromFile} ({@code patch_cable_set.cpp:739, 938}). */
  public boolean mightContainAutomation() {
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (!c.param.nodes.isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  /** C {@code PatchCableSet::processCurrentPos} ({@code patch_cable_set.cpp:747-749}). */
  public boolean isAnyCableInterpolating() {
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (c.param.hasInterpolationIncrement()) {
          return true;
        }
      }
    }
    return false;
  }

  /** C {@code PatchCableSet::notifyPingpongOccurred} ({@code patch_cable_set.cpp:1181-1189}). */
  public void notifyPingpongOccurred() {
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (c.param.hasInterpolationIncrement()) {
          c.param.notifyPingpongOccurred();
        }
      }
    }
  }

  /** C {@code PatchCableSet::setPlayPos} ({@code patch_cable_set.cpp:594-608}). */
  public void setPlayPos(int pos, int loopLength, boolean reversed) {
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (!c.param.nodes.isEmpty()) {
          c.param.setPlayPos(pos, loopLength, reversed);
        }
      }
    }
  }

  /** C {@code PatchCableSet::processCurrentPos} ({@code patch_cable_set.cpp:727-752}). */
  public int processCurrentPos(
      int currentPos, int loopLength, boolean reversed, boolean didPingpong) {
    int minTicksTilNextEvent = Integer.MAX_VALUE;
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (!c.param.nodes.isEmpty()) {
          int dist = c.param.processCurrentPos(currentPos, loopLength, reversed, didPingpong, true);
          if (dist < minTicksTilNextEvent) {
            minTicksTilNextEvent = dist;
          }
        }
      }
    }
    return minTicksTilNextEvent;
  }

  /** C {@code PatchCableSet::tickSamples} ({@code patch_cable_set.cpp:576-592}). */
  public void tickSamples(int numSamples, int timePerTimerTickInverse) {
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (c.param.hasInterpolationIncrement()) {
          c.param.tickSamples(numSamples, timePerTimerTickInverse);
        }
      }
    }
  }

  /**
   * Advances tick-domain cable slopes when {@code !mayInterpolate} (C {@code patch_cable_set.h:60}
   * is empty only because Sound/Drum always set {@code mayInterpolate = true}).
   */
  public void tickTicks(int numTicks) {
    for (Destination d : destinations) {
      for (PatchCable c : d.cables) {
        if (c.param.hasInterpolationIncrement()) {
          c.param.tickTicks(numTicks);
        }
      }
    }
  }
}
