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
      // Initialize all generator volumes (Osc A, Osc B, Noise) to absolute silence
      // (Integer.MIN_VALUE)
      // to keep unconfigured/empty drum slots completely muted and prevent background leakage.
      drumSound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MIN_VALUE;
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      drumSound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
      drumSounds.add(drumSound);
      super.drums.add(drumSound.fw2Sound);
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    if (muted) {
      return;
    }
    for (FirmwareSound drum : drumSounds) {
      // C kit.cpp:541-586 / sound.cpp:2178-2215 — keep rendering each drum while voices, arp notes,
      // per-drum delay repeats, stutter, or grain FX tail remain active.
      if (drum.shouldRender(numSamples)) {
        drum.renderOutputSumming(buffer, numSamples, reverbBuffer);
      }
    }
  }

  @Override
  public void triggerDrum(int row, int vel) {
    triggerDrum(row, vel, 0);
  }

  @Override
  public void triggerDrum(int row, int vel, int samplesLate) {
    if (muted) {
      return;
    }
    if (row >= 0 && row < drumSounds.size()) {
      if (samplesLate > 0) {
        drumSounds.get(row).triggerNoteLate(60, vel, samplesLate);
      } else {
        drumSounds.get(row).triggerNote(60, vel);
      }
    }
  }

  @Override
  public void releaseDrum(int row) {
    if (row >= 0 && row < drumSounds.size()) {
      drumSounds.get(row).releaseNote(60);
    }
  }

  @Override
  public void releaseAllNotes() {
    for (FirmwareSound drum : drumSounds) {
      drum.releaseAllNotes();
    }
  }
}
