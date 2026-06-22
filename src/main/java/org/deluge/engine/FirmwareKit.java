package org.deluge.engine;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;

/** Port of the Deluge's Kit class. */
public class FirmwareKit extends org.deluge.firmware2.Kit {
  public final List<FirmwareSound> drumSounds = new ArrayList<>();

  public FirmwareKit() {
    super.drums.clear();
    for (int i = 0; i < 16; i++) {
      FirmwareSound drumSound = new FirmwareSound();
      drumSound.isDrum = true;
      drumSound.oscTypes[0] = OscType.SAMPLE;
      // C Sound::initParams sets LOCAL_OSC_A_VOLUME = MAX for source 0 → the sample plays
      // at full amplitude through the same sourceAmplitude path as an oscillator. Only silence
      // the second oscillator + noise (a drum uses source 0 — the sample — exclusively).
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      drumSound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
      drumSounds.add(drumSound);
      super.drums.add(drumSound.fw2Sound);
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    for (FirmwareSound drum : drumSounds) {
      if (!drum.fw2Sound.voices.isEmpty()) {
        // Render drum track with its own FX chain, summing directly to the Kit buffer
        drum.renderOutput(buffer, numSamples, reverbBuffer);
      }
    }
  }

  public void triggerDrum(int row, int vel) {
    if (row >= 0 && row < drumSounds.size()) {
      drumSounds.get(row).triggerNote(60, vel);
    }
  }
}
