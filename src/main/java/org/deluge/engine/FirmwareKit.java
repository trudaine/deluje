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
      drumSound.setIsDrum(true);
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

  /**
   * The active clip's ParamManager, which is where a kit clip's {@code <kitParams>} automation
   * lives. The C reads the kit's unpatched params straight out of it on every render — {@code
   * Kit::setupAndRenderArpPreOutput} is handed {@code paramManager = getParamManager(song)} and
   * calls {@code arpSettings->updateParamsFromUnpatchedParamSet(unpatchedParams)}, then takes the
   * arp's gate and rate from {@code unpatchedParams->getValue(...)} (kit.cpp:709-724).
   *
   * <p>Null until {@link FirmwareFactory#syncKitTopLevelParams} binds it, and null for a kit with
   * no active clip — which in the C is the {@code getArpSettings() == nullptr} early return
   * (kit.cpp:711-713).
   */
  public transient org.deluge.modulation.params.ParamManager clipParamManager;

  /**
   * Push the active clip's automated unpatched arp params into {@link #arpSettings} and the arp
   * knobs, the C's {@code updateParamsFromUnpatchedParamSet} step (kit.cpp:714).
   *
   * <p>Without this a {@code <kitParams>} {@code arpeggiatorGate} or {@code arpeggiatorRate} curve
   * was parsed, stored and advanced on the clip's timeline but never read, so the kit arp ran at
   * whatever {@code syncKitTopLevelParams} had written at load. The knob fields keep their meaning
   * as the UNAUTOMATED base, which is how the C treats the unpatched set's own value when nothing
   * automates it.
   *
   * <p>Mirrors FirmwareSound.syncParamsToFw2's loop over the same params, and takes the Kit monitor
   * because every kit-arp entry point does.
   */
  synchronized void applyClipArpAutomation() {
    if (clipParamManager == null) {
      return;
    }
    for (int p = org.deluge.firmware2.Param.UNPATCHED_ARP_GATE;
        p <= org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY;
        p++) {
      var auto = clipParamManager.getAutomatedParam(p);
      if (auto == null || auto.nodes.isEmpty()) {
        continue;
      }
      arpSettings.updateParamFromUnpatched(p, auto.currentValue);
      // GATE is not read from arpSettings by the kit arp — renderKitArp takes it from this knob
      // (C kit.cpp:722 reads it from the unpatched set directly), so the automated value has to
      // reach the knob as well.
      if (p == org.deluge.firmware2.Param.UNPATCHED_ARP_GATE) {
        arpGateKnob = auto.currentValue;
      }
    }
    // RATE separately, because UNPATCHED_ARP_RATE (120) is NOT inside the ARP_GATE..SPREAD_VELOCITY
    // block (101..115) the loop above walks — it sits with the shared unpatched params. The C reads
    // it from the same unpatched set as the gate (kit.cpp:723).
    var rateAuto =
        clipParamManager.getAutomatedParam(org.deluge.firmware2.Param.UNPATCHED_ARP_RATE);
    if (rateAuto != null && !rateAuto.nodes.isEmpty()) {
      arpRateKnob = rateAuto.currentValue;
    }
  }

  /**
   * C Kit::noteOnPreKitArp (kit.cpp:1291-1332) — with the clip's automation applied FIRST.
   *
   * <p>A sequenced note-on runs after the clip has advanced its automation but before the kit's
   * next arp tick, so without this it read the PREVIOUS {@code arpSettings}. For a drum routed
   * through the kit arp that is audible: a {@code noteProbability} automated from 100% to 0% on the
   * hit's own tick would let the hit sound anyway. The C has no gap here because it reads the
   * unpatched set at the point of use (kit.cpp:714).
   *
   * <p>The 3-argument entry point is the one to override: {@code Kit.triggerDrum(row, vel)}
   * delegates to it, so sequenced, late and live note-ons are all covered.
   */
  @Override
  public synchronized void triggerDrum(int row, int vel, int samplesLate) {
    applyClipArpAutomation();
    super.triggerDrum(row, vel, samplesLate);
  }

  @Override
  protected synchronized void renderKitArp(int numSamples) {
    applyClipArpAutomation(); // C kit.cpp:714 — before the arp renders
    super.renderKitArp(numSamples);
  }

  @Override
  public synchronized int doTickForwardForKitArp(int currentPos, boolean reversed) {
    applyClipArpAutomation(); // C kit.cpp:1140-1146 — the tick path reads them too
    return super.doTickForwardForKitArp(currentPos, reversed);
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
      setInvertReversedFromKitArp(drum.fw2Sound, false);
      drum.releaseNote(60);
    }
  }

  @Override
  public synchronized void releaseAllNotes() {
    arpeggiator.reset();
    for (FirmwareSound drum : drumSounds) {
      setInvertReversedFromKitArp(drum.fw2Sound, false);
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
