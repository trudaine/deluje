package org.chuck.deluge.firmware2;

import java.util.ArrayList;
import java.util.List;

/**
 * Faithful port of the Deluge C++ Kit class logic. Manages a collection of drum rows (Sound
 * objects), rendering them sequentially into the Kit's shared track buffer before applying
 * Kit-level filters and FX.
 */
public class Kit extends GlobalEffectable {
  public final List<Sound> drums = new ArrayList<>();

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
    for (Sound drum : drums) {
      // Each drum is a Sound object, which has its own voices, envelopes, and filter.
      // We render each drum row using its own renderOutput, summing directly into the Kit's buffer.
      drum.renderOutput(buffer, numSamples, reverbBuffer);
    }
  }
}
