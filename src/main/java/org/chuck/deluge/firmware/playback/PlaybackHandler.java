package org.chuck.deluge.firmware.playback;

import org.chuck.deluge.firmware.hid.FirmwareDisplay;
import org.chuck.deluge.firmware.model.Clip;
import org.chuck.deluge.firmware.model.ClipInstance;
import org.chuck.deluge.firmware.model.Song;

public class PlaybackHandler {
  private Song currentSong;
  private Arrangement arrangement = new Arrangement();
  private boolean arrangementMode = false;
  public int lastSwungTickActioned = 0;
  public int swungTicksTilNextEvent = 0;
  private boolean playing = false;

  public void setSong(Song song) {
    this.currentSong = song;
  }

  public void setArrangementMode(boolean enabled) {
    this.arrangementMode = enabled;
  }

  public void addArrangementInstance(ClipInstance instance) {
    arrangement.addInstance(instance);
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
  /** Advance the sequencer by a number of ticks. */
  public void advanceTicks(int numTicks) {
    if (!playing || currentSong == null) return;

    int ticksRemaining = numTicks;
    while (ticksRemaining > 0) {
      int toAdvance =
          Math.min(
              ticksRemaining, swungTicksTilNextEvent > 0 ? swungTicksTilNextEvent : ticksRemaining);

      lastSwungTickActioned += toAdvance;

      if (arrangementMode) {
        arrangement.doTickForward(toAdvance, currentSong);
        swungTicksTilNextEvent = arrangement.swungTicksTilNextEvent;
      } else {
        for (Clip clip : currentSong.clips) {
          if (clip.currentlyPlayingReversed) {
            clip.lastProcessedPos -= toAdvance;
          } else {
            clip.lastProcessedPos += toAdvance;
          }
        }
        currentSong.doTickForward(toAdvance);
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
