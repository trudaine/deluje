package org.chuck.deluge.ui.arranger;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ArrangerClip;

/** Monitors the playhead and schedules clip pattern loads at the correct bar boundaries. */
public class ArrangerPlaybackController {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final ArrangerViewModel viewModel;

  private int lastStep = -1;

  public ArrangerPlaybackController(
      ChuckVM vm, BridgeContract bridge, ArrangerViewModel viewModel) {
    this.vm = vm;
    this.bridge = bridge;
    this.viewModel = viewModel;
  }

  public void update(int currentStep) {
    if (currentStep == lastStep) return;

    // Check for boundary crossing
    if (currentStep % 16 == 0 && lastStep != -1) {
      int currentBar = (currentStep / 16) + 1;
      checkClips(currentBar);
    }

    lastStep = currentStep;
  }

  private void checkClips(int currentBar) {
    // In a full implementation, this maps pattern data to tracks
    // when the playhead reaches a clip's start bar.
    for (ArrangerClip clip : viewModel.getClips()) {
      if (clip.startBar() == currentBar) {
        System.out.println(
            "Arranger: Launching pattern " + clip.patternId() + " on track " + clip.trackIndex());
      }
    }
  }
}
