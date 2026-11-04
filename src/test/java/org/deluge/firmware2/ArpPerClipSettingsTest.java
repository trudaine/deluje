package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  @Test
  void aClipsOwnSettingsSurviveAConfigureWithNoClipAtAll() {
    // The no-clip case is a nullptr in the C (melodic_instrument.cpp:675), so it certainly does
    // not write into the clip that was playing. Here the field has to point somewhere real, so it
    // goes back to the sound's own no-clip object rather than staying on the clip's.
    SynthTrackModel track = new SynthTrackModel("LEAD");
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    track.addClip(a);
    track.setActiveClipIndex(0);
    FirmwareFactory.applyModelToLiveSound(track, sound);
    byte[] aSequence = a.getArpSettings().lockedNoteProbabilityValues.clone();

    FirmwareFactory.configureArp(sound, ArpModel.defaultConfig(), new Object());

    assertSame(
        sound.fw2Sound.noClipArpSettings,
        sound.fw2Sound.arpSettings,
        "with no clip the sound must fall back to its own settings object");
    assertArrayEquals(
        aSequence,
        a.getArpSettings().lockedNoteProbabilityValues,
        "and the clip it was playing must keep its live sequence untouched");
  }

  @Test
  void aPresetLoadClearsThePendingRegenerationEvenForAClipOwner() {
    // A C preset load builds a whole new instrument, arpeggiator included
    // (storage_manager.cpp:635), so the new one cannot be carrying a pending regeneration. That is
    // true whichever settings it is pointed at — and if it were left set, the next locked step
    // would regenerate over the arrays the clip had evolved (arpeggiator.cpp:928-999).
    SynthTrackModel track = new SynthTrackModel("LEAD");
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    track.addClip(a);
    track.setActiveClipIndex(0);
    FirmwareFactory.applyModelToLiveSound(track, sound);

    sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime = true;

    // An ordinary live refresh must leave it alone — this is not a load.
    FirmwareFactory.applyModelToLiveSound(track, sound);
    assertTrue(
        sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "a live refresh is not a load and must not clear it, or this test proves nothing");

    SynthTrackModel preset = new SynthTrackModel("PRESET");
    preset.setArp(ArpModel.defaultConfig().toBuilder().active(true).mode("arp").build());
    track.copyParametersFrom(preset);
    FirmwareFactory.applyModelToLiveSound(track, sound);

    assertFalse(
        sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "a preset load must clear it — the C's replacement arpeggiator never has it set");
  }

  @Test
  void aCopiedClipDoesNotTakeTheSourcesEvolvedOrSavedSequence() {
    // C instrument_clip.cpp:151 copies a clip's arp with cloneFrom, which carries the params and
    // no sequences (arpeggiator.cpp:1620-1650) — the new clip keeps its own constructor state. The
    // copy's ArpModel still carries the source's saved blobs, so the copy must not treat its first
    // activation as a load of them.
    SynthTrackModel track = new SynthTrackModel("LEAD");
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    track.addClip(a);

    ClipModel copy = a.deepCopy("a-copy");
    copy.setSound(sound);
    track.addClip(copy);
    track.setActiveClipIndex(1);
    FirmwareFactory.applyModelToLiveSound(track, sound);

    byte[] zeros = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    assertArrayEquals(
        zeros,
        copy.getArpSettings().lockedNoteProbabilityValues,
        "a copied clip starts from its own constructor state, not the source's saved sequence");
    assertNotSame(a.getArpSettings(), copy.getArpSettings(), "and it has settings of its own");
  }

  /**
   * A clip switch must be atomic against the arp entry points, not merely take their monitor at
   * some point inside itself.
   *
   * <p>Between the repoint and the configure, the settings briefly hold the UNPATCHED defaults
   * rather than the clip's values. A step landing there would not just play one wrong step: for a
   * locked clip the momentary {@code noteProbability} mismatch against {@code
   * lastLockedNoteProbabilityParameterValue} satisfies the second regeneration trigger
   * (arpeggiator.cpp:929) and refills the ten locked arrays, destroying the evolved sequence.
   *
   * <p>A watcher samples under {@code synchronized (arpeggiator)}, exactly as every DSP entry point
   * does, and records any value that belongs to neither clip. An earlier version of this test
   * asserted only that the switch BLOCKED, which passed with every lock removed — {@code
   * configureArp} takes the monitor internally — so it proved nothing.
   */
  @Test
  void aClipSwitchIsAtomicAgainstTheArpEntryPoints() throws Exception {
    SynthTrackModel track = new SynthTrackModel("LEAD");
    // Both clips carry the SAME noteProbability, so the only other value the watcher can see is
    // the UNPATCHED default written mid-switch.
    ClipModel a = clipWithSavedArp("a", "0101010101010101010101010101010A");
    ClipModel b = clipWithSavedArp("b", "02020202020202020202020202020202");
    FirmwareSound sound = new FirmwareSound();
    a.setSound(sound);
    b.setSound(sound);
    track.addClip(a);
    track.addClip(b);
    track.setActiveClipIndex(0);
    FirmwareFactory.applyModelToLiveSound(track, sound);
    int configured = sound.fw2Sound.arpSettings.noteProbability;

    // Bounded protocol rather than a sample-count race: the watcher signals that it has taken its
    // first sample before any switching starts, and the switching continues until the watcher has
    // taken REQUIRED_SAMPLES or a generous deadline passes. Both are asserted, so a runner too
    // slow to produce the samples reports that rather than passing vacuously. An earlier version
    // just ran 2000 switches and asserted `samples > 1000`, which a late-starting watcher on a
    // busy single-core runner could fail with correct production locking.
    // Both bounds matter: the samples prove the watcher was looking, the switches prove there was
    // something to see. The control (production locking removed) reports odd samples at roughly
    // 3% of samples, so these numbers give it hundreds of chances rather than a handful.
    final int requiredSamples = 5000;
    final int requiredSwitches = 2000;
    final long deadlineNanos =
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    java.util.concurrent.atomic.AtomicBoolean run =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.atomic.AtomicInteger oddSamples =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger samples =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.CountDownLatch sampling = new java.util.concurrent.CountDownLatch(1);
    Thread watcher =
        new Thread(
            () -> {
              while (run.get()) {
                synchronized (sound.fw2Sound.arpeggiator) {
                  samples.incrementAndGet();
                  if (sound.fw2Sound.arpSettings.noteProbability != configured) {
                    oddSamples.incrementAndGet();
                  }
                }
                sampling.countDown();
              }
            });
    watcher.start();
    assertTrue(
        sampling.await(20, java.util.concurrent.TimeUnit.SECONDS),
        "the watcher must be sampling before the switching starts");

    int switches = 0;
    while ((samples.get() < requiredSamples || switches < requiredSwitches)
        && System.nanoTime() < deadlineNanos) {
      track.setActiveClipIndex(switches % 2);
      switches++;
    }
    run.set(false);
    watcher.join(20_000);

    assertTrue(
        samples.get() >= requiredSamples && switches >= requiredSwitches,
        "this must reach "
            + requiredSamples
            + " samples over "
            + requiredSwitches
            + " switches to be sensitive; got "
            + samples.get()
            + " over "
            + switches);
    assertEquals(
        0,
        oddSamples.get(),
        "no arp entry point may observe the settings mid-switch; saw "
            + oddSamples.get()
            + " of "
            + samples.get()
            + " samples holding neither clip's noteProbability over "
            + switches
            + " switches");
  }

  @Test
  void aClipSwitchAfterAPresetLoadIsStillJustASwitch() {
    // configureArp decides "the C built a new arpeggiator" from the load revision. The clip-switch
    // path has to pass the track's CURRENT revision: passing 0 after a preset load made an
    // ordinary switch look like an instrument load, clearing the pending regeneration and the
    // locked-sequence cursor, then doing it again on the next refresh when the real revision
    // returned. A switch bumps no revision, so the C keeps the same Arpeggiator across one.
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

    // A preset load, which legitimately clears it and leaves the revision nonzero.
    SynthTrackModel preset = new SynthTrackModel("PRESET");
    preset.setArp(ArpModel.defaultConfig().toBuilder().active(true).mode("arp").build());
    track.copyParametersFrom(preset);
    FirmwareFactory.applyModelToLiveSound(track, sound);
    assertTrue(track.getArpLoadRevision() != 0, "the preset load must have bumped the revision");

    // Now the arp evolves state again, and a plain clip switch must not discard it.
    sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime = true;
    track.setActiveClipIndex(1);
    assertTrue(
        sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "a clip switch after a preset load is still only a switch — the C keeps the arpeggiator");

    // And the following live refresh must not re-trigger it either.
    FirmwareFactory.applyModelToLiveSound(track, sound);
    assertTrue(
        sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime,
        "nor may the next refresh see a load that already happened");
  }

  /**
   * A clip's LIVE evolved sequence survives save and reload — in both serialization branches.
   *
   * <p>The C writes a clip's {@code <arpeggiator>} from its own live {@code arpSettings}
   * (instrument_clip.cpp:2404-2412), so what it saves is what the arp has evolved to. This port
   * keeps the parameters in {@code ArpModel} and the sequences in the clip's {@code Settings}, so
   * the serializer has to snapshot the live ones; writing the model's instead saved whatever the
   * file originally held, and after a preset hot-swap could save the preset's over the sequence the
   * clip was actually still playing.
   */
  @Test
  void aClipsEvolvedSequenceSurvivesSaveAndReload() throws Exception {
    for (boolean clipHasItsOwnArp : new boolean[] {true, false}) {
      org.deluge.model.ProjectModel project = new org.deluge.model.ProjectModel();
      SynthTrackModel track = new SynthTrackModel("LEAD");
      ClipModel clip = new ClipModel("c", 1, 16);
      ArpModel arp =
          ArpModel.defaultConfig().toBuilder()
              .active(true)
              .mode("arp")
              .randomizerLock(1)
              .noteProbability(0.5f)
              .lockedNoteProbArray("0101010101010101010101010101010A")
              .build();
      if (clipHasItsOwnArp) {
        clip.setArpFromXml(arp);
      } else {
        track.setArp(arp); // the fallback branch: the clip carries no <arpeggiator> of its own
      }
      FirmwareSound sound = new FirmwareSound();
      clip.setSound(sound);
      track.addClip(clip);
      track.setActiveClipIndex(0);
      project.addTrack(track);
      FirmwareFactory.applyModelToLiveSound(track, sound);

      // Evolve it, the way a playing arp does.
      byte[] fromFile = clip.getArpSettings().lockedNoteProbabilityValues.clone();
      sound.fw2Sound.arpeggiator.resetLockedRandomizerValuesNextTime = true;
      sound.fw2Sound.arpeggiator.calculateRandomizerAmounts(sound.fw2Sound.arpSettings);
      byte[] evolved = clip.getArpSettings().lockedNoteProbabilityValues.clone();
      String which = clipHasItsOwnArp ? "clip-owned arp" : "track-fallback arp";
      assertFalse(
          java.util.Arrays.equals(fromFile, evolved),
          which + ": the step must have changed the sequence, or this proves nothing");

      java.io.File f = java.io.File.createTempFile("evolvedsave", ".xml");
      f.deleteOnExit();
      org.deluge.project.ProjectSerializer.save(project, f);
      SynthTrackModel back =
          (SynthTrackModel) org.deluge.xml.DelugeXmlParser.parseSong(f).getTracks().get(0);

      assertArrayEquals(
          evolved,
          back.getClips().get(0).getArpSettings().lockedNoteProbabilityValues,
          which + ": the EVOLVED sequence must be what was saved and reloaded, not the file's");
    }
  }

  /**
   * Snapshotting a KIT clip's sequences must take the monitor the kit's arp writers hold — the Kit
   * itself, since every kit-arp entry point is a synchronized method on it — not the clip.
   *
   * <p>Decisive rather than circumstantial: {@code withLiveArpSequences} takes exactly one lock, so
   * whether it blocks on a parked holder of the kit monitor distinguishes the two choices
   * completely. (The round-1 version of the clip-switch test could not make that claim, because
   * {@code configureArp} takes its monitor internally.)
   */
  @Test
  void snapshottingAKitClipTakesTheKitMonitor() throws Exception {
    org.deluge.model.KitTrackModel kitTrack = new org.deluge.model.KitTrackModel("KIT");
    ClipModel clip = new ClipModel("c", 8, 16);
    clip.setArpFromXml(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .lockedNoteProbArray("0101010101010101010101010101010A")
            .build());
    org.deluge.engine.FirmwareKit kit = new org.deluge.engine.FirmwareKit();
    clip.setSound(kit);
    kitTrack.addClip(clip);

    java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              synchronized (kit) {
                holding.countDown();
                try {
                  release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    holder.start();
    assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "holder must take it");

    java.util.concurrent.atomic.AtomicReference<ArpModel> snapshot =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread saver = new Thread(() -> snapshot.set(clip.withLiveArpSequences(clip.getArp())));
    saver.start();
    saver.join(400);
    boolean blocked = saver.isAlive();
    release.countDown();
    saver.join(10_000);
    holder.join(10_000);

    assertTrue(
        blocked, "the snapshot must block on the KIT monitor, which is what its writers hold");
    assertEquals(
        "0101010101010101010101010101010A",
        snapshot.get().lockedNoteProbArray(),
        "and must complete once the monitor is free");
  }

  private static byte[] expand(String hex) {
    byte[] out = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    for (int i = 0; i < out.length && i * 2 + 1 < hex.length(); i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
