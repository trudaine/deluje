package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ArpModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A preset load has to clear the per-arpeggiator state a C preset load throws away by constructing
 * a whole new instrument, so a sequence restored from the file is actually the one that plays.
 *
 * <p>The C builds {@code new (instrumentMemory) SoundInstrument()} on a preset load
 * (storage_manager.cpp:635), so its arpeggiator starts with {@code
 * resetLockedRandomizerValuesNextTime} at the default {@code false} (arpeggiator.h:344) and an
 * empty {@code notesByPattern} (arpeggiator.cpp:78, :125). This port hot-swaps parameters into the
 * existing sound and keeps the same arpeggiator, so both have to be brought into line explicitly —
 * see {@code ArpeggiatorSynth.adoptRestoredSequence}.
 *
 * <p>A clip switch is deliberately NOT covered by that: the C keeps the instrument and only
 * repoints {@code getArpSettings} (sound_instrument.cpp:425), so the flag and the cached order
 * rightly survive one.
 */
class ArpRestoredSequenceAdoptionTest {

  private static final String LOCK_HEX = "0102030405060708090A0B0C0D0E0F10";

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  private static byte[] lockBytes() {
    byte[] b = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) (i + 1);
    }
    return b;
  }

  private static ArpModel unlockedArp() {
    return ArpModel.defaultConfig().toBuilder()
        .active(true)
        .mode("arp")
        .randomizerLock(0)
        .noteProbability(0.5f)
        .build();
  }

  /**
   * A real saved preset records the parameter value its sequence was locked AT, because {@code
   * calculateRandomizerAmounts} also regenerates when {@code
   * lastLockedNoteProbabilityParameterValue != noteProbability} (arpeggiator.cpp:929) — that clause
   * is faithful and must keep working, so this probes the mapping rather than hard-coding a q31 and
   * leaving the flag as the only trigger.
   */
  private static ArpModel lockedPresetArp() {
    ArpModel base =
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .noteProbability(0.5f)
            .lockedNoteProbArray(LOCK_HEX)
            .build();
    Arpeggiator.Settings probe = new Arpeggiator.Settings();
    FirmwareFactory.configureArpSettings(probe, base, 0);
    return base.toBuilder().lastLockedNoteProb(probe.noteProbability).build();
  }

  @Test
  void loadingALockedPresetAfterUnlockedPlaybackKeepsItsRestoredValues() {
    SynthTrackModel model = new SynthTrackModel("Unlocked");
    model.setArp(unlockedArp());
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);

    // Every step an UNLOCKED arp plays sets the pending-regeneration flag (arpeggiator.cpp:1042).
    // Drive one for real rather than assuming the scenario is reachable.
    sound.arpeggiator.calculateRandomizerAmounts(sound.arpSettings);
    assertTrue(
        sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "an unlocked step must leave regeneration pending, or this test proves nothing");

    SynthTrackModel preset = new SynthTrackModel("LockedPreset");
    preset.setArp(lockedPresetArp());
    model.copyParametersFrom(preset);
    FirmwareFactory.applyModelToLiveSound(model, sound);

    assertArrayEquals(
        lockBytes(),
        sound.arpSettings.lockedNoteProbabilityValues,
        "the loaded preset's locked sequence must be in place");
    assertFalse(
        sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "a load must clear the pending regeneration the previous unlocked playback left");

    // And the next locked step must consume the restored values rather than replace them. With the
    // flag stale this regenerates all ten arrays (arpeggiator.cpp:928-999); the saved lastLocked*
    // values match the parameters, so the flag is the only thing that would trigger it.
    sound.arpeggiator.calculateRandomizerAmounts(sound.arpSettings);
    assertArrayEquals(
        lockBytes(),
        sound.arpSettings.lockedNoteProbabilityValues,
        "a locked step must consume the restored sequence, not regenerate over it");
  }

  @Test
  void aClipSwitchLeavesThePendingRegenerationAlone() {
    // The other side of the same distinction, and the reason this is not simply "clear it on every
    // activation": a clip switch keeps the instrument in the C, so the SAME Arpeggiator carries its
    // pending regeneration across (sound_instrument.cpp:425 only repoints getArpSettings). Clearing
    // it here would be the port inventing state the hardware does not have.
    SynthTrackModel model = new SynthTrackModel("TwoClips");
    model.setArp(unlockedArp());
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);

    org.deluge.model.ClipModel clipA = new org.deluge.model.ClipModel("A", 8, 16);
    org.deluge.model.ClipModel clipB = new org.deluge.model.ClipModel("B", 8, 16);
    clipA.setArp(lockedPresetArp());
    clipB.setArp(lockedPresetArp());
    clipA.setSound(sound);
    clipB.setSound(sound);
    model.addClip(clipA);
    model.addClip(clipB);
    model.setActiveClipIndex(0);

    sound.arpeggiator.calculateRandomizerAmounts(sound.arpSettings);
    sound.arpeggiator.resetLockedRandomizerValuesNextTime = true;

    model.setActiveClipIndex(1);

    assertTrue(
        sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "a clip switch must NOT clear it — the C keeps the same arpeggiator across one");
  }

  @Test
  void loadingAPatternPresetWithNotesHeldReordersThem() {
    // rearrangePatternArpNotes inserts note i at index min(notePattern[i], i)
    // (arpeggiator.cpp:1269-1271), so an all-zero pattern pushes every note to the front — the held
    // chord comes out REVERSED, highest first. A 00,01,02,03... pattern is the identity order.
    SynthTrackModel model = new SynthTrackModel("PatternA");
    model.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .noteMode("pattern")
            .notePattern("00000000000000000000000000000000")
            .build());
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);

    // Hold a chord, so notesByPattern is built from pattern A.
    Arpeggiator.ArpReturnInstruction instr = new Arpeggiator.ArpReturnInstruction();
    int[] mpe = new int[] {0, 0, 0};
    for (int note : new int[] {60, 64, 67, 72}) {
      sound.arpeggiator.noteOn(sound.arpSettings, note, 100, instr, -1, mpe);
    }
    assertEquals(4, sound.arpeggiator.notesByPattern.size(), "the chord must be held");
    assertEquals(
        72,
        sound.arpeggiator.notesByPattern.get(0).noteCode,
        "the all-zero pattern plays the chord highest-note first");

    // Load a preset whose pattern is the identity order, with the chord still down.
    SynthTrackModel preset = new SynthTrackModel("PatternB");
    preset.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .noteMode("pattern")
            .notePattern("000102030405060708090A0B0C0D0E0F")
            .build());
    model.copyParametersFrom(preset);
    FirmwareFactory.applyModelToLiveSound(model, sound);

    byte[] identity = new byte[Arpeggiator.PATTERN_MAX_BUFFER_SIZE];
    for (int i = 0; i < identity.length; i++) {
      identity[i] = (byte) i;
    }
    assertArrayEquals(
        identity, sound.arpSettings.notePattern, "the new preset's pattern must be in place");
    assertEquals(
        60,
        sound.arpeggiator.notesByPattern.get(0).noteCode,
        "held notes must be reordered by the loaded pattern, not left in the previous order");
  }
}
