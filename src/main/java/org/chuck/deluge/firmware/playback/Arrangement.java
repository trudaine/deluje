package org.chuck.deluge.firmware.playback;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.model.Clip;
import org.chuck.deluge.firmware.model.ClipInstance;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

public class Arrangement {
  public List<ClipInstance> instances = new ArrayList<>();
  public ParamManager paramManager = new ParamManager(); // Arrangement-level automation
  public int lastProcessedPos = 0;
  public int swungTicksTilNextEvent = Integer.MAX_VALUE;

  public void addInstance(ClipInstance instance) {
    instances.add(instance);
  }

  public void advance(int numTicks) {
    doTickForward(numTicks, null); // Simplified: currentSong not always needed for basic advance
  }

  public void doTickForward(int posIncrement, Song currentSong) {
    lastProcessedPos += posIncrement;
    swungTicksTilNextEvent = Integer.MAX_VALUE;

    // 1. Process arrangement-level automation
    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(lastProcessedPos, 0, false, false, true);
      swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, paramManager.ticksTilNextEvent);
    }

    // 2. Process all instances (supporting overlaps)
    for (ClipInstance instance : instances) {
      int clipStart = instance.pos;
      int clipEnd = clipStart + instance.length;

      // If instance is active now
      if (lastProcessedPos >= clipStart && lastProcessedPos < clipEnd) {
        Clip clip = instance.clip;

        // Advance clip position
        // In hardware, Arrangement::doTickForward calls clip->processCurrentPos
        clip.lastProcessedPos += posIncrement;
        clip.processCurrentPos(posIncrement);

        // Update next event distance
        int ticksTilEnd = clipEnd - lastProcessedPos;
        swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, ticksTilEnd);
      }
      // If instance starts in the future
      else if (instance.pos > lastProcessedPos) {
        int ticksTilStart = instance.pos - lastProcessedPos;
        swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, ticksTilStart);
      }
    }
  }

  public void resetPlayPos(int newPos) {
    lastProcessedPos = newPos;
    // In hardware, this would also resync all active clips to the new position
    for (ClipInstance instance : instances) {
      if (newPos >= instance.pos && newPos < instance.pos + instance.length) {
        instance.clip.lastProcessedPos = (newPos - instance.pos) % instance.clip.loopLength;
      }
    }
  }
}
