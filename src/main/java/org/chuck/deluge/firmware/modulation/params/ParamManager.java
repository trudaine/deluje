package org.chuck.deluge.firmware.modulation.params;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.modulation.automation.AutoParam;
import org.chuck.deluge.firmware.modulation.patch.PatchCableSet;

public class ParamManager {
  public List<AutoParam> automatedParams = new ArrayList<>();
  public int ticksTilNextEvent = Integer.MAX_VALUE;
  private PatchCableSet patchCableSet = new PatchCableSet();

  public boolean mightContainAutomation() {
    return !automatedParams.isEmpty();
  }

  public PatchCableSet getPatchCableSet() {
    return patchCableSet;
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
