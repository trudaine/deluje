package org.chuck.deluge.firmware.playback;

import org.chuck.deluge.firmware.hid.FirmwareDisplay;
import org.chuck.deluge.firmware.model.Clip;
import org.chuck.deluge.firmware.model.Song;

/**
 * Port of the Deluge's PlaybackHandler class. Manages transport state and high-fidelity timing
 * (Swing, Quantization).
 */
public class PlaybackHandler {
  private boolean playing = false;
  private Song currentSong;
  private final Arrangement arrangement = new Arrangement();
  public int lastSwungTickActioned = 0;
  private int swungTicksTilNextEvent = 0;

  public void setSong(Song song) {
    this.currentSong = song;
  }

  public Song getSong() {
    return currentSong;
  }

  public void start() {
    playing = true;
    lastSwungTickActioned = 0;
    swungTicksTilNextEvent = 0;
    FirmwareDisplay.get().setText(" PLAYING ");
    if (currentSong != null) {
      for (Clip clip : currentSong.clips) {
        clip.lastProcessedPos = 0;
        clip.repeatCount = 0;
      }
    }
    arrangement.resetPlayPos(0);
  }

  public void stop() {
    playing = false;
    FirmwareDisplay.get().setText(" STOPPED ");
  }

  /** Advance the sequencer by a number of ticks. Includes high-fidelity Swing logic. */
  public void advanceTicks(int numTicks) {
    if (!playing || currentSong == null) return;

    int ticksRemaining = numTicks;
    while (ticksRemaining > 0) {
      int toAdvance = Math.min(ticksRemaining, swungTicksTilNextEvent);
      if (toAdvance <= 0) toAdvance = 1;

      // ── Bit-Accurate Swing Math ──
      int effectiveAdvance = toAdvance;
      if (currentSong.swingAmount != 0) {
        int leftShift = 10 - currentSong.swingInterval;
        int swingTicks = 3 << leftShift;
        if ((lastSwungTickActioned % (swingTicks * 2)) < swingTicks) {
          effectiveAdvance = (toAdvance * (50 + currentSong.swingAmount)) / 50;
        }
      }

      lastSwungTickActioned += effectiveAdvance;
      arrangement.advance(effectiveAdvance);

      for (Clip clip : currentSong.clips) {
        clip.processCurrentPos(effectiveAdvance);
      }

      // Update next event distance
      if (currentSong != null) {
        swungTicksTilNextEvent = currentSong.swungTicksTilNextEvent;
      }

      ticksRemaining -= toAdvance;
    }

    // Update LED with bar:beat:tick
    int bars = (lastSwungTickActioned / (24 * 16)) + 1;
    int beats = ((lastSwungTickActioned / 24) % 16) + 1;
    int ticks = (lastSwungTickActioned % 24) + 1;
    FirmwareDisplay.get().setText(String.format(" %02d:%02d:%02d ", bars, beats, ticks));
  }
}
