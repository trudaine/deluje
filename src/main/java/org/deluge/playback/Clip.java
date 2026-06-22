package org.deluge.playback;

import org.deluge.modulation.params.ParamManager;

/**
 * Port of the Deluge's Clip class. Base for Instrument and Audio clips. Manages looping, direction,
 * and automation processing.
 */
public abstract class Clip extends TimelineCounter {
  public ClipType type;
  public volatile boolean tripletMode = false;
  public ParamManager paramManager = new ParamManager();
  public volatile int loopLength = 0;

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
    return false; // Hardware check for arrangement-only tracks
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
    // ── Bit-Accurate End Logic ──
    if (loopLength > 0) {
      lastProcessedPos %= loopLength;
    }
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
