package org.chuck.deluge.firmware.model;

import org.chuck.deluge.firmware.modulation.params.ParamManager;

public abstract class Clip extends TimelineCounter {
  public ClipType type;
  public ParamManager paramManager = new ParamManager();
  public int loopLength = 0;

  public Clip(ClipType newType) {
    this.type = newType;
  }

  @Override
  public int getLoopLength() {
    return loopLength;
  }

  public abstract int getMaxLength();

  public abstract void resumePlayback(boolean mayMakeSound);

  public abstract void expectNoFurtherTicks(boolean actuallySoundChange);

  public boolean isArrangementOnlyClip() {
    return false; // stub
  }

  public void processCurrentPos(int ticksSinceLast) {
    if (currentlyPlayingReversed) {
      if (lastProcessedPos < 0) {
        lastProcessedPos += loopLength;
      }
    }

    int endPos = currentlyPlayingReversed ? 0 : loopLength;
    if (lastProcessedPos == endPos && repeatCount >= 0) {
      posReachedEnd();
    }

    int ticksTilEnd;
    boolean didPingpong = false;

    if (currentlyPlayingReversed) {
      if (lastProcessedPos == 0) {
        repeatCount++;
        if (sequenceDirectionMode == SequenceDirection.PINGPONG) {
          lastProcessedPos = -lastProcessedPos;
          currentlyPlayingReversed = !currentlyPlayingReversed;
          pingpongOccurred();
          didPingpong = true;
          // Jump to forward logic
          ticksTilEnd = loopLength - lastProcessedPos;
        } else {
          ticksTilEnd = lastProcessedPos;
        }
      } else {
        ticksTilEnd = lastProcessedPos;
      }
    } else {
      ticksTilEnd = loopLength - lastProcessedPos;
      if (ticksTilEnd <= 0) {
        lastProcessedPos -= loopLength;
        repeatCount++;

        if (sequenceDirectionMode == SequenceDirection.PINGPONG) {
          if (lastProcessedPos > 0) {
            lastProcessedPos = loopLength - lastProcessedPos;
          }
          currentlyPlayingReversed = !currentlyPlayingReversed;
          pingpongOccurred();
          didPingpong = true;
        }
        ticksTilEnd += loopLength;
      }
    }

    if (paramManager.mightContainAutomation()) {
      // ── Bit-Accurate Automation Interpolation ──
      // Interpolate for internal sounds, jump for external MIDI/CV
      boolean mayInterpolate = (type == ClipType.INSTRUMENT || type == ClipType.AUDIO); 
      paramManager.processCurrentPos(
          lastProcessedPos, loopLength, currentlyPlayingReversed, didPingpong, mayInterpolate);
    }
  }

  protected void posReachedEnd() {
    // stub: handle clip extension or truncation
  }

  protected void pingpongOccurred() {
    paramManager.notifyPingpongOccurred();
  }

  public int getActualCurrentPosAsIfPlayingInForwardDirection() {
    if (currentlyPlayingReversed) {
      return loopLength - lastProcessedPos;
    }
    return lastProcessedPos;
  }
}
