package org.chuck.deluge.firmware.model;

public abstract class TimelineCounter {
  public volatile int lastProcessedPos = 0;
  public volatile int repeatCount = 0;
  public boolean armedForRecording = true;
  public volatile boolean currentlyPlayingReversed = false;
  public volatile SequenceDirection sequenceDirectionMode = SequenceDirection.FORWARD;

  public TimelineCounter() {}

  public abstract int getLoopLength();

  public abstract boolean isPlayingAutomationNow();

  public abstract boolean backtrackingCouldLoopBackToEnd();

  // Simplifications of parameter classes
  public void instrumentBeenEdited() {}
}
