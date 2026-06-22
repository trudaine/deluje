package org.deluge.model;

/**
 * Abstract base class representing transport and timeline counters (like Song or Clip). Relocated
 * to org.deluge.model for unified architecture.
 */
public abstract class TimelineCounter {
  public volatile int lastProcessedPos = 0;
  public volatile int repeatCount = 0;
  public boolean armedForRecording = true;
  public volatile boolean currentlyPlayingReversed = false;
  public SequenceDirection sequenceDirectionMode = SequenceDirection.FORWARD;

  public TimelineCounter() {}

  public abstract int getLoopLength();

  public abstract boolean isPlayingAutomationNow();

  public abstract boolean backtrackingCouldLoopBackToEnd();

  public void instrumentBeenEdited() {}
}
