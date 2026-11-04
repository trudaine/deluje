package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every C {@code InstrumentClip} owns its {@code ArpeggiatorSettings} by value
 * (instrument_clip.h:103) and the instrument hands out a pointer to the active clip's ({@code
 * MelodicInstrument::getArpSettings}, melodic_instrument.cpp:667-677). Those settings carry state
 * the arp MUTATES as it plays — the note pattern and the ten locked randomizer arrays — so on
 * hardware switching A to B and back resumes A's evolved sequence where it left off.
 */
class ArpPerClipSettingsTest {

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  /** A clip whose settings came from a file carrying a distinctive locked sequence. */
  private static ClipModel clipWithSavedArp(String name, String lockHex) {
    ClipModel clip = new ClipModel(name, 1, 16);
    clip.setArpFromXml(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .noteProbability(0.5f)
            .lockedNoteProbArray(lockHex)
            .build());
    return clip;
  }

  @Test
  void eachClipOwnsItsSettingsAndTheSoundPointsAtTheActiveOne() {
    SynthTrackModel track = new SynthTrackModel("LEAD");
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    ClipModel b = clipWithSavedArp("b", "02020202020202020202020202020202");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    b.setSound(sound);
    track.addClip(a);
    track.addClip(b);

    assertNotSame(
        a.getArpSettings(),
        b.getArpSettings(),
        "each clip owns its own ArpeggiatorSettings (instrument_clip.h:103)");

    track.setActiveClipIndex(0);
    FirmwareFactory.applyModelToLiveSound(track, sound);
    assertSame(
        a.getArpSettings(),
        sound.fw2Sound.arpSettings,
        "the sound is pointed at the ACTIVE clip's, not given one of its own"
            + " (melodic_instrument.cpp:667-677)");

    track.setActiveClipIndex(1);
    assertSame(
        b.getArpSettings(),
        sound.fw2Sound.arpSettings,
        "a clip switch repoints it, which in the C is the whole of getArpSettings");
  }

  @Test
  void aClipSwitchAndBackResumesTheClipsOwnEvolvedSequence() {
    SynthTrackModel track = new SynthTrackModel("LEAD");
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    ClipModel b = clipWithSavedArp("b", "02020202020202020202020202020202");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    b.setSound(sound);
    track.addClip(a);
    track.addClip(b);

    track.setActiveClipIndex(0);
    FirmwareFactory.applyModelToLiveSound(track, sound);
    byte[] aFromFile = a.getArpSettings().lockedNoteProbabilityValues.clone();

    // Let the arp EVOLVE clip A's sequence, the way it does while playing: a step leaves
    // regeneration pending (arpeggiator.cpp:1042) and the next calculateRandomizerAmounts refills
    // all ten locked arrays in place (:928-999, which needs randomizerLock set — the clip's file
    // says lock=1). Nothing pushes that back to ArpModel, so the clip's Settings is the only thing
    // holding it.
    sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime = true;
    sound.fw2Sound.arpeggiator.calculateRandomizerAmounts(sound.fw2Sound.arpSettings);
    byte[] aEvolved = a.getArpSettings().lockedNoteProbabilityValues.clone();
    assertFalse(
        java.util.Arrays.equals(aFromFile, aEvolved),
        "the step must actually have changed A's sequence, or this test proves nothing");

    track.setActiveClipIndex(1);
    assertArrayEquals(
        expand("02020202020202020202020202020202"),
        b.getArpSettings().lockedNoteProbabilityValues,
        "B plays its OWN saved sequence, not whatever A had just locked");

    track.setActiveClipIndex(0);
    assertArrayEquals(
        aEvolved,
        sound.fw2Sound.arpSettings.lockedNoteProbabilityValues,
        "returning to A resumes the sequence A had evolved, not the file's values — the whole"
            + " point of per-clip settings ownership");
  }

  @Test
  void aClipsSavedSequenceStillReachesTheDspOnLoad() {
    // The #169 guarantee, on the per-clip path: a clip read from a file has its saved sequence in
    // its own settings before anything plays (C arpSettings.readCommonTagsFromFile,
    // instrument_clip.cpp:2679), so the first activation plays the file's values.
    SynthTrackModel track = new SynthTrackModel("LEAD");
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    track.addClip(a);
    track.setActiveClipIndex(0);
    FirmwareFactory.applyModelToLiveSound(track, sound);

    assertArrayEquals(
        expand("0101010101010101010101010101010A"),
        sound.fw2Sound.arpSettings.lockedNoteProbabilityValues,
        "the clip's saved locked sequence must be what the DSP plays");
  }

  private static byte[] expand(String hex) {
    byte[] out = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    for (int i = 0; i < out.length && i * 2 + 1 < hex.length(); i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
