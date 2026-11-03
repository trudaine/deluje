package org.deluge.engine;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;

/** Port of the Deluge's Kit class. */
public class FirmwareKit extends org.deluge.firmware2.Kit {
  public final List<FirmwareSound> drumSounds = new ArrayList<>();
  public int inputTickMagnitude = 2;

  /**
   * Which clip {@link org.deluge.firmware2.Kit#arpSettings} currently belongs to. The C gives every
   * {@code InstrumentClip} its own {@code ArpeggiatorSettings} and only points {@code
   * Kit::getArpSettings} at the active one (kit.cpp:787-791); this port shares one per kit, so the
   * settings are re-defaulted when, and only when, this changes.
   */
  public transient Object arpOwningClip = NO_ARP_CLIP_YET;

  /**
   * Distinct from every clip and from {@code null}, so the first {@code configureKitArp} always
   * re-defaults — including for a track with no clips, where the owner is {@code null}. Without it
   * the first call was silently a no-op and a kit's settings depended on whether it had a clip.
   */
  private static final Object NO_ARP_CLIP_YET = new Object();

  public FirmwareKit() {
    super.drums.clear();
    ensureDrumCount(16);
  }

  @Override
  public void ensureDrumCount(int count) {
    while (drumSounds.size() < count) {
      FirmwareSound drumSound = new FirmwareSound();
      drumSound.isDrum = true;
      drumSound.fw2Sound.isDrum = true;
      // A slot added while the transport runs inherits the kit's cached clock state: the C reads
      // playbackHandler.isEitherClockActive() globally (arpeggiator.cpp:1527) while
      // PlaybackHandler.setArpClockActive only refreshes this on transport start/stop.
      drumSound.fw2Sound.arpeggiator.playbackClockActive = arpeggiator.playbackClockActive;
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
    renderKitArp(numSamples); // C kit.cpp:705-785 — the kit arp runs before the drums render
    for (FirmwareSound drum : drumSounds) {
      // C kit.cpp:541-586 / sound.cpp:2178-2215 — keep rendering each drum while voices, arp notes,
      // per-drum delay repeats, stutter, or grain FX tail remain active.
      drum.shouldLimitDelayFeedback = this.shouldLimitDelayFeedback;
      if (drum.shouldRender(numSamples)) {
        drum.renderOutputSumming(buffer, numSamples, reverbBuffer);
      }
    }
  }

  @Override
  protected org.deluge.firmware2.Sound drumAt(int row) {
    return (row >= 0 && row < drumSounds.size()) ? drumSounds.get(row).fw2Sound : null;
  }

  @Override
  protected boolean bypassesKitArp(int row) {
    if (row >= 0 && row < drumSounds.size()) {
      drumSounds.get(row).syncParamsToFw2();
    }
    return super.bypassesKitArp(row);
  }

  @Override
  protected void drumNoteOn(int row, int vel, int samplesLate) {
    if (row < 0 || row >= drumSounds.size()) {
      return;
    }
    FirmwareSound drum = drumSounds.get(row);
    drum.syncParamsToFw2();
    if (samplesLate > 0) {
      drum.triggerNoteLate(60, vel, samplesLate);
    } else {
      drum.triggerNote(60, vel);
    }
  }

  @Override
  protected void drumNoteOff(int row) {
    if (row >= 0 && row < drumSounds.size()) {
      FirmwareSound drum = drumSounds.get(row);
      drum.fw2Sound.arpeggiator.invertReversedFromKitArp = false;
      drum.releaseNote(60);
    }
  }

  @Override
  public synchronized void releaseAllNotes() {
    arpeggiator.reset();
    for (FirmwareSound drum : drumSounds) {
      drum.fw2Sound.arpeggiator.invertReversedFromKitArp = false;
      drum.releaseAllNotes();
    }
  }

  /**
   * C kit.cpp:588-619 — ticks every SoundDrum's ParamManager after rendering the kit, even for
   * drums that skipped audio rendering this block.
   */
  @Override
  protected void tickParamManagersAfterRender(int numSamples) {
    if (shouldLimitDelayFeedback) {
      return;
    }
    for (FirmwareSound drum : drumSounds) {
      if (drum.fw2Sound.arpeggiator.playbackClockActive) {
        drum.tickSamples(numSamples);
      }
    }
  }
}
