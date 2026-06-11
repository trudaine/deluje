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

  public Kit() {}

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    for (Sound drum : drums) {
      // Each drum is a Sound object, which has its own voices, envelopes, and filter.
      // We render each drum row using its own renderOutput, summing directly into the Kit's buffer.
      drum.renderOutput(buffer, numSamples, reverbBuffer);
    }
  }
}
