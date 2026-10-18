package org.deluge.firmware2;

import java.util.ArrayList;
import java.util.List;

/**
 * Faithful port of the Deluge C++ Kit class logic. Manages a collection of drum rows (Sound
 * objects), rendering them sequentially into the Kit's shared track buffer before applying
 * Kit-level filters and FX.
 */
public class Kit extends GlobalEffectable {
  public final List<Sound> drums = new ArrayList<>();

  @Override
  public boolean isKit() {
    return true;
  }

  public Kit() {
    for (int i = 0; i < 16; i++) {
      Sound drumSound = new Sound();
      drumSound.oscTypes[0] = Oscillator.OscType.SAMPLE;
      // Initialize volumes to keep Operator B and Noise quiet by default
      drumSound.patchedParamValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      drumSound.patchedParamValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
      drums.add(drumSound);
    }
  }

  public void triggerDrum(int row, int vel) {
    triggerDrum(row, vel, 0);
  }

  public void triggerDrum(int row, int vel, int samplesLate) {
    if (muted) {
      return;
    }
    if (row >= 0 && row < drums.size()) {
      if (samplesLate > 0) {
        drums.get(row).triggerNoteLate(60, vel, samplesLate);
      } else {
        drums.get(row).triggerNote(60, vel);
      }
    }
  }

  public void releaseDrum(int row) {
    if (row >= 0 && row < drums.size()) {
      drums.get(row).releaseNote(60);
    }
  }

  public void releaseAllNotes() {
    for (Sound drum : drums) {
      drum.releaseAllNotes();
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    if (muted) {
      return;
    }
    for (Sound drum : drums) {
      // C kit.cpp:582-584 / sound.cpp:2630 — each drum inherits shouldLimitDelayFeedback from the
      // Kit and renders into the shared Kit output and reverb buffers by summing.
      drum.shouldLimitDelayFeedback = this.shouldLimitDelayFeedback;
      drum.renderOutputSumming(buffer, numSamples, reverbBuffer);
    }
  }
}
