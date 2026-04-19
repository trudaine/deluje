package org.chuck.deluge.ui.song;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * Handles launch quantization for Song Mode clips. Ensures queued clips wait until the next musical
 * boundary (e.g., next bar) to start playing.
 */
public class LaunchQuantController {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final ClipCell[][] grid; // [tracks][slots]
  private final int tracks;
  private final int slots;

  private int lastStep = -1;

  public LaunchQuantController(
      ChuckVM vm, BridgeContract bridge, ClipCell[][] grid, int tracks, int slots) {
    this.vm = vm;
    this.bridge = bridge;
    this.grid = grid;
    this.tracks = tracks;
    this.slots = slots;
  }

  /** Called every frame by the UI AnimationTimer. */
  public void update(int currentStep) {
    if (currentStep == lastStep) return;

    // Check if we crossed a bar boundary (step % 16 == 0)
    // In a full implementation, quantization length (1 bar, 1 beat, 1/16th) would be configurable.
    // For this MVP, we quantize clip launches to the start of the next 16-step bar.
    if (currentStep % 16 == 0 && lastStep != -1) {
      launchQueuedClips();
    }

    lastStep = currentStep;
  }

  private void launchQueuedClips() {
    boolean anyLaunched = false;

    for (int t = 0; t < tracks; t++) {
      ClipCell queuedCell = null;
      ClipCell playingCell = null;

      // Find queued and currently playing cells for this track
      for (int s = 0; s < slots; s++) {
        if (grid[t][s].getCurrentState() == ClipCell.State.QUEUED) {
          queuedCell = grid[t][s];
        } else if (grid[t][s].getCurrentState() == ClipCell.State.PLAYING) {
          playingCell = grid[t][s];
        }
      }

      if (queuedCell != null) {
        // Stop the currently playing cell if there is one
        if (playingCell != null) {
          playingCell.setFilled(playingCell.getPatternId());
        }

        // Launch the queued cell
        queuedCell.setPlaying();
        anyLaunched = true;

        // TODO: In a full implementation, this is where we would load the pattern data
        // into the BridgeContract's g_pattern array for the ChucK engine to play.
        System.out.println(
            "LaunchQuantController: Launched " + queuedCell.getPatternId() + " on track " + t);
      }
    }
  }

  /**
   * Manually force a launch immediately (bypassing quantization, e.g. when sequencer is stopped).
   */
  public void forceLaunchQueued() {
    launchQueuedClips();
  }
}
