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

  // ── Kit arpeggiator (C Kit::arpeggiator, kit.h; settings = the active kit clip's arpSettings,
  // Kit::getArpSettings kit.cpp:787-797) ──
  public final ArpeggiatorKit arpeggiator = new ArpeggiatorKit();

  public final Arpeggiator.Settings arpSettings = new Arpeggiator.Settings();

  {
    arpSettings.numOctaves = 1; // C kit.cpp:53 — defaultArpSettings.numOctaves = 1
  }

  private final Arpeggiator.ArpReturnInstruction kitInstruction =
      new Arpeggiator.ArpReturnInstruction();

  /** The kit's unpatched UNPATCHED_ARP_RATE / UNPATCHED_ARP_GATE knobs (C kit.cpp:727-731). */
  public int arpRateKnob = 0;

  public int arpGateKnob = 0;

  /** C playbackHandler.getTimePerInternalTickInverse(), for an unsynced kit arp's rate. */
  public int timePerInternalTickInverse = 0;

  protected Sound drumAt(int row) {
    return (row >= 0 && row < drums.size()) ? drums.get(row) : null;
  }

  /** The drum's own note-on (C Drum::noteOn). */
  protected void drumNoteOn(int row, int vel, int samplesLate) {
    Sound d = drumAt(row);
    if (d == null) {
      return;
    }
    if (samplesLate > 0) {
      d.triggerNoteLate(60, vel, samplesLate);
    } else {
      d.triggerNote(60, vel);
    }
  }

  /** The drum's own note-off (C Drum::noteOff). */
  protected void drumNoteOff(int row) {
    Sound d = drumAt(row);
    if (d != null) {
      // C kit.cpp:737, 754, 1171, 1187, 1349, 1361, 1372 — clear invertReversedFromKitArp on every
      // drum note-off path (bypassed, pass-through, glide-off, and post-arp off).
      d.arpeggiator.invertReversedFromKitArp = false;
      d.releaseNote(60);
    }
  }

  /**
   * C Kit::noteOnPreKitArp / noteOffPreKitArp bypass test (kit.cpp:1303-1318, 1347-1365): a drum
   * excluded from the kit arp, or a sound drum without note tails (it would leave stuck notes),
   * goes straight to the drum.
   */
  protected boolean bypassesKitArp(int row) {
    Sound d = drumAt(row);
    return d == null || !d.arpSettings.includeInKitArp || !d.allowNoteTails(true);
  }

  public void triggerDrum(int row, int vel) {
    triggerDrum(row, vel, 0);
  }

  /** C Kit::noteOnPreKitArp (kit.cpp:1291-1329). */
  public void triggerDrum(int row, int vel, int samplesLate) {
    if (muted || drumAt(row) == null) {
      return;
    }
    if (bypassesKitArp(row)) {
      drumNoteOn(row, vel, samplesLate);
      return;
    }
    kitInstruction.reset();
    arpeggiator.noteOn(arpSettings, row, vel, kitInstruction, Arpeggiator.MIDI_CHANNEL_NONE, null);
    if (kitInstruction.arpNoteOn != null
        && kitInstruction.arpNoteOn.noteCodeOnPostArp[0] != Arpeggiator.ARP_NOTE_NONE) {
      // C kit.cpp:1322-1326 — forward the kit randomizer's invertReversed to the pressed drum and
      // play at the arp note's spread-adjusted velocity.
      Sound d = drumAt(row);
      if (d != null) {
        d.arpeggiator.invertReversedFromKitArp = kitInstruction.invertReversed;
      }
      drumNoteOn(row, kitInstruction.arpNoteOn.velocity, samplesLate);
      kitInstruction.arpNoteOn.noteStatus[0] = Arpeggiator.ArpNoteStatus.PLAYING;
    }
  }

  /** C Kit::noteOffPreKitArp (kit.cpp:1331-1375). */
  public void releaseDrum(int row) {
    if (drumAt(row) == null) {
      return;
    }
    if (bypassesKitArp(row)) {
      drumNoteOff(row);
      return;
    }
    kitInstruction.reset();
    arpeggiator.noteOff(arpSettings, row, kitInstruction);
    if (kitInstruction.noteCodeOffPostArp[0] != Arpeggiator.ARP_NOTE_NONE) {
      drumNoteOff(row);
    }
  }

  /** C kit.cpp:713-717 / 1153-1158 — params the kit arpeggiator does not support. */
  private void nullifyUnsupportedKitArpParams() {
    arpSettings.chordPolyphony = 0;
    arpSettings.chordProbability = 0;
    arpSettings.spreadOctave = 0;
  }

  /**
   * Plays the kit arp's output instruction on the drums (the shared body of kit.cpp:735-782 and
   * 1164-1215): glide note-off, note-off, then a note-on, each on the drum of the arp's output
   * code.
   */
  private void actOnKitInstruction(boolean onlyPendingNoteOn) {
    int glideOff = kitInstruction.glideNoteCodeOffPostArp[0];
    if (glideOff != Arpeggiator.ARP_NOTE_NONE && drumAt(glideOff) != null) {
      drumNoteOff(glideOff);
    }
    int off = kitInstruction.noteCodeOffPostArp[0];
    if (off != Arpeggiator.ARP_NOTE_NONE && drumAt(off) != null) {
      drumNoteOff(off);
    }
    var on = kitInstruction.arpNoteOn;
    if (on != null
        && (!onlyPendingNoteOn || on.noteStatus[0] == Arpeggiator.ArpNoteStatus.PENDING)
        && on.noteCodeOnPostArp[0] != Arpeggiator.ARP_NOTE_NONE
        && drumAt(on.noteCodeOnPostArp[0]) != null) {
      Sound d = drumAt(on.noteCodeOnPostArp[0]);
      // C kit.cpp:771, 1204 — forward the kit arp's invertReversed to the output drum.
      d.arpeggiator.invertReversedFromKitArp = kitInstruction.invertReversed;
      d.pendingSampleSyncLength = kitInstruction.sampleSyncLengthOn;
      drumNoteOn(on.noteCodeOnPostArp[0], on.velocity, 0);
      d.pendingSampleSyncLength = 0;
      on.noteStatus[0] = Arpeggiator.ArpNoteStatus.PLAYING;
    }
  }

  /** C Kit::setupAndRenderArpPreOutput (kit.cpp:705-785), before the drums render. */
  protected void renderKitArp(int numSamples) {
    nullifyUnsupportedKitArpParams();
    if (arpSettings.mode == Arpeggiator.ArpMode.OFF) {
      return;
    }
    int gateThreshold = arpGateKnob + Integer.MIN_VALUE; // (uint32_t)gate + 2147483648
    int phaseIncrement =
        arpSettings.getPhaseIncrement(
            Functions.getFinalParameterValueExp(
                Functions.getParamNeutralValue(Param.GLOBAL_ARP_RATE),
                Functions.cableToExpParamShortcut(arpRateKnob)),
            timePerInternalTickInverse);
    kitInstruction.reset();
    arpeggiator.render(arpSettings, kitInstruction, numSamples, gateThreshold, phaseIncrement);
    actOnKitInstruction(false);
  }

  /** C Kit::doTickForwardForArp, kit-arp half (kit.cpp:1136-1217). */
  public int doTickForwardForKitArp(int currentPos, boolean reversed) {
    nullifyUnsupportedKitArpParams();
    kitInstruction.reset();
    int ticks = arpeggiator.doTickForward(arpSettings, kitInstruction, currentPos, reversed);
    actOnKitInstruction(true);
    return ticks;
  }

  /** C kit.cpp:61, instrument_clip.cpp:1198-1205 — reset kit arp & release every drum's notes. */
  public void releaseAllNotes() {
    arpeggiator.reset();
    for (Sound drum : drums) {
      drum.arpeggiator.invertReversedFromKitArp = false;
      drum.releaseAllNotes();
    }
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    if (muted) {
      return;
    }
    renderKitArp(numSamples);
    for (Sound drum : drums) {
      // C kit.cpp:582-584 / sound.cpp:2630 — each drum inherits shouldLimitDelayFeedback from the
      // Kit and renders into the shared Kit output and reverb buffers by summing.
      drum.shouldLimitDelayFeedback = this.shouldLimitDelayFeedback;
      drum.renderOutputSumming(buffer, numSamples, reverbBuffer);
    }
  }
}
