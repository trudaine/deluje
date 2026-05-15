package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

/**
 * Port of the Deluge's Kit class.
 * Manages multiple drum sounds (FirmwareSound instances) in a single instrument track.
 */
public class FirmwareKit extends GlobalEffectable {
  public final List<FirmwareSound> drumSounds = new ArrayList<>();

  public FirmwareKit() {
    // A Kit usually has 16 primary drum slots
    for (int i = 0; i < 16; i++) {
      drumSounds.add(new FirmwareSound());
    }
  }

  @Override
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager paramManager) {
    for (FirmwareSound drum : drumSounds) {
      drum.renderInternal(buffer, numSamples, paramManager); 
    }
  }

  public void triggerDrum(int row, int vel) {
    if (row >= 0 && row < drumSounds.size()) {
      drumSounds.get(row).triggerNote(60, vel);
    }
  }
}
