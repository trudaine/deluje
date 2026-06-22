package org.deluge.modulation.params;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Param;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.patch.PatchCableSet;

public class ParamManager {
  public List<AutoParam> automatedParams = new ArrayList<>();
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
  }

  public PatchCableSet getPatchCableSet() {
    return patchCableSet;
  }

  public void recordParamValue(int paramId, int value, int currentPos) {
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
    target.addNode(currentPos, value);
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
}
