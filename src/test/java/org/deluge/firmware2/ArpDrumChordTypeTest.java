package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.firmware2.Arpeggiator.ArpMode;
import org.deluge.firmware2.Arpeggiator.SyncLevel;
import org.deluge.firmware2.Arpeggiator.SyncType;
import org.deluge.model.ArpModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A drum's arpeggiator is the C's {@code ArpeggiatorForDrum} ({@code Drum::arpeggiator}, drum.h:67,
 * handed to {@code Sound::noteOn} by {@code SoundDrum::noteOn}, sound_drum.cpp:64), and it is the
 * only arpeggiator that reads {@code settings->chordTypeIndex} (arpeggiator.cpp:805, :852, :864).
 *
 * <p>Both halves have to be in place for the arp's chord feature to do anything: the index has to
 * reach {@code Settings} from the file, and the sound has to be driven by the arp that reads it.
 */
class ArpDrumChordTypeTest {

  /** lookuptables.cpp:521-526 — MINOR is index 3, offsets {0, 3, 7}, note count 3. */
  private static final int MINOR = 3;

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  private static ArpModel chordArp(int chordType) {
    return ArpModel.defaultConfig().toBuilder()
        .active(true)
        .mode("arp")
        .chordType(chordType)
        .build();
  }

  @Test
  void aSavedChordTypeReachesTheArpSettings() {
    Arpeggiator.Settings s = new Arpeggiator.Settings();
    s.resetToDefaults();
    FirmwareFactory.configureArpSettings(s, chordArp(MINOR), 0);
    assertEquals(
        MINOR, s.chordTypeIndex, "the chordType the file carries must reach Settings as-is");
  }

  @Test
  void anOutOfRangeChordTypeLeavesTheIndexAlone() {
    // C arpeggiator.cpp:1775-1778 — `if (value >= 0 && value < MAX_CHORD_TYPES)`, so a bad value
    // is skipped rather than written. Both tables are MAX_CHORD_TYPES long and
    // ArpeggiatorForDrum::switchNoteOn indexes them unchecked (arpeggiator.cpp:805).
    Arpeggiator.Settings s = new Arpeggiator.Settings();
    s.resetToDefaults();
    FirmwareFactory.configureArpSettings(s, chordArp(MINOR), 0);
    FirmwareFactory.configureArpSettings(s, chordArp(Arpeggiator.MAX_CHORD_TYPES), 0);
    assertEquals(MINOR, s.chordTypeIndex, "an out-of-range chordType must not be written");
    FirmwareFactory.configureArpSettings(s, chordArp(-1), 0);
    assertEquals(MINOR, s.chordTypeIndex, "a negative chordType must not be written either");
  }

  @Test
  void aDrumIsDrivenByTheDrumArpeggiatorAndASynthByTheSynthOne() {
    assertInstanceOf(
        ArpeggiatorSynth.class,
        new Sound().arpeggiator,
        "a melodic Sound is a SoundInstrument: its arp is an Arpeggiator (sound_instrument.h:83)");

    Sound drum = new Sound();
    drum.setIsDrum(true);
    assertInstanceOf(
        ArpeggiatorForDrum.class,
        drum.arpeggiator,
        "a Drum owns an ArpeggiatorForDrum (drum.h:67)");

    // And the kit paths, which are how a drum is actually built. Every entry in Kit::drums is a
    // Drum (kit.h:50), whether the slot came from the constructor or from a later expansion.
    Kit baseKit = new Kit();
    assertInstanceOf(
        ArpeggiatorForDrum.class,
        baseKit.drumAt(0).arpeggiator,
        "a slot the Kit constructor makes is a Drum, so it runs the drum arp");
    baseKit.ensureDrumCount(baseKit.drums.size() + 1);
    assertInstanceOf(
        ArpeggiatorForDrum.class,
        baseKit.drums.get(baseKit.drums.size() - 1).arpeggiator,
        "a slot added by expanding the kit is a Drum too");
    assertInstanceOf(
        ArpeggiatorKit.class,
        baseKit.arpeggiator,
        "the kit's own arp is ArpeggiatorForKit (kit.h:38)");

    FirmwareKit kit = new FirmwareKit();
    assertInstanceOf(
        ArpeggiatorForDrum.class,
        kit.drumSounds.get(0).fw2Sound.arpeggiator,
        "every kit slot is a SoundDrum, so every one of them runs the drum arp");
    kit.ensureDrumCount(kit.drumSounds.size() + 1);
    assertInstanceOf(
        ArpeggiatorForDrum.class,
        kit.drumSounds.get(kit.drumSounds.size() - 1).fw2Sound.arpeggiator,
        "and so does one added by expanding the kit");
  }

  @Test
  void aDrumArpPlaysTheSavedChordTypesOffsets() {
    List<Integer> minorNotes = arpNotesOverFourSteps(MINOR);
    // C arpeggiator.cpp:852-864 — note = noteForDrum + chordTypeSemitoneOffsets[chordType][i].
    // The drum is triggered at kNoteForDrum = 60 (definitions_cxx.hpp:1019, Kit.drumNoteOn), so a
    // MINOR chord type walks 60, 63, 67 and wraps.
    assertEquals(List.of(60, 63, 67, 60), minorNotes, "the saved chord type must shape the steps");

    // The control the C itself provides: NO_CHORD has offsets {0,0,0,0} and a note count of 1
    // (lookuptables.cpp:520, :532), so every step is the drum's own note.
    assertEquals(
        List.of(60, 60, 60, 60),
        arpNotesOverFourSteps(0),
        "chord type 0 (NO_CHORD) must leave every step at the drum's own note");
  }

  /**
   * The blast-radius control for routing every drum through {@code ArpeggiatorForDrum}: with the
   * arp OFF — which is what almost every kit drum is — the two arpeggiators have to produce the
   * same audio, sample for sample.
   *
   * <p>They are not trivially the same code: {@code ArpeggiatorForDrum::noteOn}
   * (arpeggiator.cpp:189-215) and {@code Arpeggiator::noteOn} (:350-388) each run their own arp-off
   * branch, both call {@code calculateRandomizerAmounts} — which draws from the shared noise seed —
   * and both decide independently whether the step sounds at all. A difference in how many PRNG
   * values they consume, or in the velocity they hand the voice, would show up here.
   */
  @Test
  void withTheArpOffEitherArpeggiatorRendersTheSameDrumHit() {
    int[] underSynthArp = renderDrumHitWith(new Arpeggiator.Synth());
    int[] underDrumArp = renderDrumHitWith(new Arpeggiator.ForDrum());

    long energy = 0;
    for (int v : underDrumArp) {
      energy += Math.abs((long) v);
    }
    assertTrue(energy > 0, "the probe must actually render audio, or it proves nothing");

    assertArrayEquals(
        underSynthArp,
        underDrumArp,
        "with the arp off a drum must sound identically under either arpeggiator — this is what"
            + " makes giving every kit drum the drum arp a no-op for existing kits");
  }

  /** One note-on plus two render blocks of a blank drum sound, with the arp off. */
  private static int[] renderDrumHitWith(ArpeggiatorBase arp) {
    Functions.resetNoiseSeed();
    Sound drum = new Sound();
    drum.setIsDrum(true);
    drum.arpeggiator = arp;
    Sound.initParams(drum.patchedParamValues);
    Sound.setupAsBlankSynth(drum.patchedParamValues, drum, false);
    drum.bitcrushParam = Integer.MIN_VALUE;
    drum.srrParam = Integer.MIN_VALUE;
    drum.arpSettings.resetToDefaults();
    drum.arpSettings.mode = ArpMode.OFF;

    drum.triggerNote(60, 100); // kNoteForDrum, as Kit.drumNoteOn does
    int[] buffer = new int[2 * 128];
    int[] reverb = new int[128];
    drum.renderInternal(buffer, 128, reverb);
    drum.renderInternal(buffer, 128, reverb);
    return buffer;
  }

  /** Four synced arp steps on a drum sound, returning the note each one actually sounded. */
  private static List<Integer> arpNotesOverFourSteps(int chordType) {
    Sound drum = new Sound();
    drum.setIsDrum(true);
    FirmwareFactory.configureArpSettings(drum.arpSettings, chordArp(chordType), 0);
    drum.arpSettings.mode = ArpMode.ARP;
    drum.arpSettings.syncLevel = SyncLevel.SYNC_LEVEL_16TH;
    drum.arpSettings.syncType = SyncType.EVEN;
    drum.arpeggiator.playbackClockActive = true;

    Arpeggiator.ArpReturnInstruction in = new Arpeggiator.ArpReturnInstruction();
    drum.arpeggiator.noteOn(
        drum.arpSettings, 60, 100, in, Arpeggiator.MIDI_CHANNEL_NONE, new int[] {0, 0, 0});

    int period = 3 << (9 - SyncLevel.SYNC_LEVEL_16TH.ordinal());
    List<Integer> played = new ArrayList<>();
    for (int step = 0; step < 4; step++) {
      int before = drum.voices.size();
      drum.doTickForwardForArp(step * period, false);
      assertTrue(
          drum.voices.size() > before,
          "step " + step + " must start a voice, or nothing is proved");
      played.add(drum.voices.get(drum.voices.size() - 1).note);
    }
    return played;
  }
}
