package org.chuck.deluge.firmware.model;

public class AudioClip extends Clip {
  public int loopLength = 0;
  public int lastProcessedPos = 0;

  public AudioClip() {
    super(ClipType.AUDIO);
  }

  @Override
  public int getMaxLength() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void resumePlayback(boolean mayMakeSound) {
    // stub
  }

  @Override
  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    // stub
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return false;
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return false;
  }
}
