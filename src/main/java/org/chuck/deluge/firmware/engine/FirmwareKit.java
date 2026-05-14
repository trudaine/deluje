package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

public class FirmwareKit extends GlobalEffectable {
  public final List<FirmwareSynth> drumSounds = new ArrayList<>();

  public FirmwareKit() {
    // A Kit usually has a fixed number of rows (drums)
    for (int i = 0; i < 16; i++) {
      drumSounds.add(new FirmwareSynth());
    }
  }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager paramManager) {
    for (FirmwareSynth drum : drumSounds) {
      drum.renderInternal(buffer, numSamples, paramManager); // Sums into buffer
    }
  }

  public void triggerDrum(int row, int vel) {
    if (row >= 0 && row < drumSounds.size()) {
      drumSounds.get(row).triggerNote(60, vel);
    }
  }
}
