package org.chuck.deluge.firmware.model;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

public class Song extends TimelineCounter {
  public List<Clip> clips = new ArrayList<>();
  public int tempoSamples;
  public float tempoBPM;
  public int rootNote;
  public ParamManager paramManager = new ParamManager();
  public int lastSwungTickActioned = 0;
  public int swungTicksTilNextEvent = Integer.MAX_VALUE;
  public boolean inArrangerMode = false;

  public int swingAmount = 0; // -49 to 49
  public int swingInterval = 3; // SyncLevel (e.g. 1/16)

  public Song() {
    tempoBPM = 120.0f;
  }

  public void addClip(Clip clip) {
    clips.add(clip);
  }

  public void removeClip(Clip clip) {
    clips.remove(clip);
  }

  @Override
  public int getLoopLength() {
    return 0; // Song doesn't have a fixed loop length in this sense
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return true;
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return false;
  }

  public void setBPM(float bpm) {
    this.tempoBPM = bpm;
  }

  public void setRootNote(int rootNote) {
    this.rootNote = rootNote;
  }

  public boolean isInArrangerMode() {
    return inArrangerMode;
  }

  public void doTickForward(int posIncrement) {
    swungTicksTilNextEvent = Integer.MAX_VALUE;

    // Process global song automation
    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(lastProcessedPos, 0, false, false, true);
      swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, paramManager.ticksTilNextEvent);
    }

    for (Clip clip : clips) {
      // No incrementing here, that happens in the clock handler
      clip.processCurrentPos(posIncrement);

      if (clip instanceof InstrumentClip) {
        swungTicksTilNextEvent =
            Math.min(swungTicksTilNextEvent, ((InstrumentClip) clip).ticksTilNextEvent);
      }
    }
  }
}
