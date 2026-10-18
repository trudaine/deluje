package org.deluge.modulation.params;

import java.util.List;
import org.deluge.firmware2.Param;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.patch.PatchCableSet;

public class ParamManager {
  public final List<AutoParam> automatedParams = new java.util.concurrent.CopyOnWriteArrayList<>();
  public int ticksTilNextEvent = Integer.MAX_VALUE;
  private PatchCableSet patchCableSet = new PatchCableSet();
  private final int[] unpatchedValues = new int[Param.UNPATCHED_PITCH_ADJUST + 1];

  public int getUnpatchedValue(int paramId) {
    if (paramId < 0 || paramId >= unpatchedValues.length) return 0;
    return unpatchedValues[paramId];
  }

  public void setUnpatchedValue(int paramId, int value) {
    if (paramId >= 0 && paramId < unpatchedValues.length) {
      unpatchedValues[paramId] = value;
    }
  }

  public boolean mightContainAutomation() {
    return !automatedParams.isEmpty();
  }

  public void notifyPingpongOccurred() {
    for (AutoParam param : automatedParams) {
      param.notifyPingpongOccurred();
    }
    // C param_manager.cpp:605 — wake on the very next tick after a ping-pong bounce.
    ticksTilNextEvent = 0;
  }

  public PatchCableSet getPatchCableSet() {
    return patchCableSet;
  }

  public void recordParamValue(int paramId, int value, int currentPos) {
    recordParamValue(paramId, value, currentPos, false);
  }

  public void recordParamValue(int paramId, int value, int currentPos, boolean interpolated) {
    AutoParam target = null;
    for (AutoParam p : automatedParams) {
      if (p.paramId == paramId) {
        target = p;
        break;
      }
    }
    if (target == null) {
      target = new AutoParam(paramId);
      automatedParams.add(target);
    }
    target.addNode(currentPos, value, interpolated);
  }

  public AutoParam getAutomatedParam(int paramId) {
    for (AutoParam p : automatedParams) {
      if (p.paramId == paramId) {
        return p;
      }
    }
    return null;
  }

  public void processCurrentPos(
      int currentPos,
      int loopLength,
      boolean reversed,
      boolean didPingpong,
      boolean mayInterpolate) {
    ticksTilNextEvent = Integer.MAX_VALUE;
    for (AutoParam param : automatedParams) {
      int dist =
          param.processCurrentPos(currentPos, loopLength, reversed, didPingpong, mayInterpolate);
      if (dist < ticksTilNextEvent) {
        ticksTilNextEvent = dist;
      }
    }
  }

  public void tickSamples(int numSamples, int timePerTimerTickInverse) {
    for (AutoParam param : automatedParams) {
      param.tickSamples(numSamples, timePerTimerTickInverse);
    }
  }

  /** C param_set.cpp:150-164 (ParamSet::tickTicks). */
  public void tickTicks(int numTicks) {
    for (AutoParam param : automatedParams) {
      param.tickTicks(numTicks);
    }
  }
}
