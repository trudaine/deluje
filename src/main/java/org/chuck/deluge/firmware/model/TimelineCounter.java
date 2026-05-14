package org.chuck.deluge.firmware.model;

public abstract class TimelineCounter {
  public int lastProcessedPos = 0;
  public int repeatCount = 0;
  public boolean armedForRecording = true;
  public boolean currentlyPlayingReversed = false;
  public SequenceDirection sequenceDirectionMode = SequenceDirection.FORWARD;

  public TimelineCounter() {}

  public abstract int getLoopLength();

  public abstract boolean isPlayingAutomationNow();

  public abstract boolean backtrackingCouldLoopBackToEnd();

  // Simplifications of parameter classes
  public void instrumentBeenEdited() {}
}
