package org.deluge.playback;

import org.deluge.modulation.params.ParamManager;

public class Arrangement {
  public ParamManager paramManager = new ParamManager(); // Arrangement-level automation
  public int lastProcessedPos = 0;
  public int swungTicksTilNextEvent = Integer.MAX_VALUE;

  public void advance(int numTicks) {
    doTickForward(numTicks);
  }

  public void doTickForward(int posIncrement) {
    lastProcessedPos += posIncrement;
    swungTicksTilNextEvent = Integer.MAX_VALUE;

    // 1. Process arrangement-level automation (C arrangement.cpp:122-128)
    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(posIncrement, lastProcessedPos, 0, false, false, true);
      swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, paramManager.ticksTilNextEvent);
    }
  }

  public void resetPlayPos(int newPos) {
    lastProcessedPos = newPos;
    // C arrangement.cpp:359-365
    if (paramManager.mightContainAutomation()) {
      paramManager.setPlayPos(newPos, 0, false);
    }
  }
}
