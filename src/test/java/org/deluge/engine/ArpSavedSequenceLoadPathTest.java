package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * The saved arp sequence reaches the DSP on the SYNTH and DRUM load paths, and a live parameter
 * refresh afterwards does not wipe what the arp has locked since.
 *
 * <p>{@link ArpSavedSequenceRestoreTest} covers the mapping itself; this covers the two places that
 * have to ask for it. The C fills these settings once per load and never on a parameter change: a
 * drum owns its own {@code arpSettings} ({@code drum.h:68}) and {@code SoundDrum::getArpSettings}
 * ignores the clip ({@code sound_drum.h:66}), filled by {@code Sound::readFromFile} ({@code
 * sound_drum.cpp:149}); a synth's are the active clip's, which {@code
 * SoundInstrument::getArpSettings} merely repoints at ({@code sound_instrument.cpp:425}). Both live
 * paths here re-enter the same mapping at 5 Hz while a config dialog is open, so the restore is
 * gated on the owner rather than run every time.
 */
class ArpSavedSequenceLoadPathTest {

  /** 16 bytes, two hex chars each, exactly as {@code writeAttributeHexBytes} emits them. */
  private static final String PATTERN_HEX = "0F0E0D0C0B0A09080706050403020100";

  private static final String LOCK_HEX = "2A0000000000000000000000000000BC";

  private static byte[] expectedPattern() {
    byte[] b = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) (0x0F - i);
    }
    return b;
  }

  private static ArpModel savedArp() {
    return ArpModel.defaultConfig().toBuilder()
        .active(true)
        .mode("arp")
        .randomizerLock(1)
        .notePattern(PATTERN_HEX)
        .lastLockedNoteProb(4242)
        .lockedNoteProbArray(LOCK_HEX)
        .build();
  }

  @Test
  void aSynthsSavedSequenceIsLoadedAndSurvivesALiveRefresh() {
    SynthTrackModel model = new SynthTrackModel("SavedArpSynth");
    model.setArp(savedArp());
    FirmwareSound sound = new FirmwareSound();

    FirmwareFactory.applyModelToLiveSound(model, sound);

    assertArrayEquals(
        expectedPattern(),
        sound.fw2Sound.arpSettings.notePattern,
        "the synth load path must install the saved note pattern");
    assertEquals(
        4242,
        sound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue,
        "and the saved locked randomizer value");

    // Now the arp plays and locks new values. Nothing pushes those back to the model, so an
    // ordinary refresh (SwingKitConfigDialog re-applies at 5 Hz) must leave them alone.
    sound.fw2Sound.arpSettings.notePattern[0] = (byte) 0x7B;
    sound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue = 99999;

    FirmwareFactory.applyModelToLiveSound(model, sound);

    assertEquals(
        (byte) 0x7B,
        sound.fw2Sound.arpSettings.notePattern[0],
        "a live refresh must not re-impose the model's pattern over what the arp locked");
    assertEquals(
        99999,
        sound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue,
        "nor reset the locked randomizer value the arp had just chosen");
  }

  @Test
  void anExplicitPresetLoadIntoTheSameTrackAndClipStillRestores() {
    // SynthPresetBrowserPanel.loadSelectedPreset hot-swaps with copyParametersFrom, keeping the
    // same track and the same active clip — so owner identity alone cannot see it, and the second
    // preset's saved pattern never reached the DSP. C instrument_clip.cpp:2312: assigning a
    // SoundInstrument clones its arp settings into the clip, which IS a load.
    SynthTrackModel model = new SynthTrackModel("PresetA");
    model.setArp(savedArp());
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);
    assertArrayEquals(
        expectedPattern(), sound.fw2Sound.arpSettings.notePattern, "preset A must load");

    SynthTrackModel presetB = new SynthTrackModel("PresetB");
    String bHex = "01010101010101010101010101010101";
    presetB.setArp(savedArp().toBuilder().notePattern(bHex).lastLockedNoteProb(777).build());

    model.copyParametersFrom(presetB);
    FirmwareFactory.applyModelToLiveSound(model, sound);

    byte[] expectedB = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    java.util.Arrays.fill(expectedB, (byte) 1);
    assertArrayEquals(
        expectedB,
        sound.fw2Sound.arpSettings.notePattern,
        "an explicit preset load must re-read the new preset's saved pattern");
    assertEquals(
        777,
        sound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue,
        "and its saved locked randomizer value");
  }

  @Test
  void theRestoreHoldsTheMonitorTheDspArpEntryPointsUse() throws Exception {
    // firmware2.Sound guards doTickForwardForArp (Sound.java:1886), note on/off (:1429, :1465) and
    // render (:1533, :1587) with `synchronized (arpeggiator)`. The live mapping holds the
    // FirmwareSound monitor, a DIFFERENT lock, so the restore has to take this one too or a tick
    // can read a half-decoded sequence.
    SynthTrackModel model = new SynthTrackModel("LockedArpSynth");
    model.setArp(savedArp());
    FirmwareSound sound = new FirmwareSound();

    java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch mapped = new java.util.concurrent.CountDownLatch(1);

    Thread tickHolder =
        new Thread(
            () -> {
              synchronized (sound.fw2Sound.arpeggiator) {
                holding.countDown();
                try {
                  release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "arp-monitor-holder");
    tickHolder.start();
    org.junit.jupiter.api.Assertions.assertTrue(
        holding.await(5, java.util.concurrent.TimeUnit.SECONDS),
        "the holder must take the monitor");

    Thread mapper =
        new Thread(
            () -> {
              FirmwareFactory.applyModelToLiveSound(model, sound);
              mapped.countDown();
            },
            "arp-mapper");
    mapper.start();

    org.junit.jupiter.api.Assertions.assertFalse(
        mapped.await(400, java.util.concurrent.TimeUnit.MILLISECONDS),
        "the restore must not run while a DSP arp entry point holds the arpeggiator monitor");

    release.countDown();
    org.junit.jupiter.api.Assertions.assertTrue(
        mapped.await(5, java.util.concurrent.TimeUnit.SECONDS),
        "and must proceed once that monitor is free");
    tickHolder.join(1000);
    mapper.join(1000);

    assertArrayEquals(
        expectedPattern(),
        sound.fw2Sound.arpSettings.notePattern,
        "the sequence still lands once unblocked");
  }

  @Test
  void aDrumsSavedSequenceIsLoadedAndSurvivesALiveRefresh() {
    SoundDrum sd = new SoundDrum("SavedArpDrum");
    sd.setArp(savedArp());
    KitTrackModel kit = new KitTrackModel("SavedArpKit");
    FirmwareSound drumSound = new FirmwareSound();

    FirmwareFactory.mapDrumToSound(sd, drumSound, 0, kit);

    assertArrayEquals(
        expectedPattern(),
        drumSound.fw2Sound.arpSettings.notePattern,
        "the drum load path must install the saved note pattern");
    assertEquals(
        4242,
        drumSound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue,
        "and the saved locked randomizer value");

    drumSound.fw2Sound.arpSettings.notePattern[0] = (byte) 0x7B;
    drumSound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue = 99999;

    FirmwareFactory.mapDrumToSound(sd, drumSound, 0, kit);

    assertEquals(
        (byte) 0x7B,
        drumSound.fw2Sound.arpSettings.notePattern[0],
        "a live refresh must not re-impose the model's pattern over what the arp locked");
    assertEquals(
        99999,
        drumSound.fw2Sound.arpSettings.lastLockedNoteProbabilityParameterValue,
        "nor reset the locked randomizer value the arp had just chosen");
  }
}
