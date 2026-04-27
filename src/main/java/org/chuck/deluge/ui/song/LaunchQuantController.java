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
  private final Runnable refreshCallback;

  private int lastStep = -1;

  public LaunchQuantController(
      ChuckVM vm,
      BridgeContract bridge,
      ClipCell[][] grid,
      int tracks,
      int slots,
      Runnable refreshCallback) {
    this.vm = vm;
    this.bridge = bridge;
    this.grid = grid;
    this.tracks = tracks;
    this.slots = slots;
    this.refreshCallback = refreshCallback;
  }

  /** Called every frame by the UI AnimationTimer. */
  public void update(int currentStep) {
    if (currentStep == lastStep) return;

    // Check if we crossed a bar boundary (step % 16 == 0)
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

      for (int s = 0; s < slots; s++) {
        if (grid[t][s].getCurrentState() == ClipCell.State.QUEUED) {
          queuedCell = grid[t][s];
        } else if (grid[t][s].getCurrentState() == ClipCell.State.PLAYING) {
          playingCell = grid[t][s];
        }
      }

      if (queuedCell != null) {
        if (playingCell != null) {
          playingCell.setFilled(playingCell.getPatternId());
        }

        queuedCell.setPlaying();
        anyLaunched = true;

        // Load the pattern data into the BridgeContract
        bridge.loadClip(t, queuedCell.getSlotIndex());
      }
    }

    if (anyLaunched && refreshCallback != null) {
      javafx.application.Platform.runLater(refreshCallback);
    }
  }

  public void forceLaunchQueued() {
    launchQueuedClips();
  }
}
