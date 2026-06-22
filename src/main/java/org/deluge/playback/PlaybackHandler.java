package org.deluge.playback;

import org.deluge.hid.FirmwareDisplay;

/**
 * Port of the Deluge's PlaybackHandler class. Manages transport state and high-fidelity timing
 * (Swing, Quantization).
 */
public class PlaybackHandler {
  private volatile boolean playing = false;
  private Song currentSong;
  private final Arrangement arrangement = new Arrangement();
  public volatile int lastSwungTickActioned = 0;
  private int swungTicksTilNextEvent = 0;
  private volatile int syncMode = 0; // 0 = INTERNAL, 1 = EXTERNAL_MIDI

  public int getSyncMode() {
    return syncMode;
  }

  public void setSyncMode(int mode) {
    this.syncMode = mode;
  }

  public synchronized void setSong(Song song) {
    this.currentSong = song;
  }

  public synchronized Song getSong() {
    return currentSong;
  }

  public synchronized boolean isPlaying() {
    return playing;
  }

  public synchronized void start() {
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

  public synchronized void stop() {
    playing = false;
    FirmwareDisplay.get().setText(" STOPPED ");
  }

  /** Advance the sequencer by a number of ticks. Includes high-fidelity Swing logic. */
  public synchronized void advanceTicks(int numTicks) {
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry && playing && Math.random() < 0.05) {
      System.out.println(
          "[DIAG advance] advanceTicks called with numTicks="
              + numTicks
              + " lastSwungTickActioned="
              + lastSwungTickActioned
              + " currentSong="
              + currentSong
              + " swungTicksTilNextEvent="
              + swungTicksTilNextEvent);
    }
    if (!playing || currentSong == null) return;

    int ticksRemaining = numTicks;
    while (ticksRemaining > 0) {
      int toAdvance = Math.min(ticksRemaining, swungTicksTilNextEvent);
      if (toAdvance <= 0) toAdvance = 1;

      // ── Bit-Accurate Swing Math ──
      int effectiveAdvance = toAdvance;
      if (currentSong.swingAmount != 0) {
        int leftShift =
            6 - currentSong.swingInterval; // Offset 6 for 96 PPQN (C++ uses 9/10 for 1536 PPQN)
        int swingTicks = 3 << leftShift;
        if ((lastSwungTickActioned % (swingTicks * 2)) < swingTicks) {
          effectiveAdvance = (toAdvance * (50 + currentSong.swingAmount)) / 50;
        } else {
          effectiveAdvance = (toAdvance * (50 - currentSong.swingAmount)) / 50;
        }
      }

      lastSwungTickActioned += effectiveAdvance;
      arrangement.advance(effectiveAdvance);

      for (Clip clip : currentSong.clips) {
        clip.lastProcessedPos += effectiveAdvance;
        clip.processCurrentPos(effectiveAdvance);
      }

      // Update next event distance
      if (currentSong != null) {
        swungTicksTilNextEvent = currentSong.swungTicksTilNextEvent;
      }

      ticksRemaining -= toAdvance;
    }

    // Update LED with bar:beat:tick
    int stepTicks = 24;
    int stepCount = 16;
    if (currentSong != null && !currentSong.clips.isEmpty()) {
      stepTicks = currentSong.clips.get(0).tripletMode ? 32 : 24;
      stepCount = currentSong.clips.get(0).tripletMode ? 12 : 16;
    }
    int bars = (lastSwungTickActioned / (stepTicks * stepCount)) + 1;
    int beats = ((lastSwungTickActioned / stepTicks) % stepCount) + 1;
    int ticks = (lastSwungTickActioned % stepTicks) + 1;
    FirmwareDisplay.get().setText(String.format(" %02d:%02d:%02d ", bars, beats, ticks));
  }
}
