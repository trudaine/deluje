package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * C: each instrument clip carries its own arpSettings (instrument_clip.cpp:2404-2412, 2676-2689)
 * and the sound plays the active clip's (Sound::getArpSettings).
 */
class PerClipSynthArpTest {

  @Test
  void eachClipKeepsItsArpAndTheActiveOnePlays() throws Exception {
    ProjectModel model = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    ClipModel a = new ClipModel("a", 1, 16);
    ClipModel b = new ClipModel("b", 1, 16);
    a.setArp(ArpModel.defaultConfig().toBuilder().active(true).syncLevel(8).build());
    b.setArp(ArpModel.defaultConfig().toBuilder().active(false).build());
    synth.addClip(a);
    synth.addClip(b);
    model.addTrack(synth);

    File f = File.createTempFile("perclip", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    SynthTrackModel back = (SynthTrackModel) DelugeXmlParser.parseSong(f).getTracks().get(0);
    assertTrue(back.getClips().get(0).getArp().active());
    assertEquals(8, back.getClips().get(0).getArp().syncLevel());
    assertFalse(back.getClips().get(1).getArp().active());

    FirmwareSound fs = new FirmwareSound();
    back.getClips().forEach(c -> c.setSound(fs));
    back.setActiveClipIndex(0);
    assertTrue(back.getArp().active(), "the active clip's arp is the synth's");
    back.setActiveClipIndex(1);
    assertFalse(back.getArp().active());
    assertEquals(
        Arpeggiator.ArpMode.OFF, fs.fw2Sound.arpSettings.mode, "switching clips reconfigures");
    back.setActiveClipIndex(0);
    assertEquals(Arpeggiator.ArpMode.ARP, fs.fw2Sound.arpSettings.mode);
  }

  /**
   * A clip read from a song has its saved arp sequence in its OWN settings straight away, before
   * anything activates it — which is where the C fills them ({@code
   * arpSettings.readCommonTagsFromFile}, instrument_clip.cpp:2679).
   *
   * <p>Binding at read time rather than at first activation is what makes a clip switch a pure
   * pointer swap. If the first switch to a clip doubled as its load, it would clear the
   * arpeggiator's pending regeneration and cached note order, which the C keeps across a switch —
   * and a preset replacement before that first bind would install the preset's sequence over the
   * clip's own.
   */
  @Test
  void aSongClipsSavedArpSequenceIsBoundWhenTheClipIsRead() throws Exception {
    String lockHex = "0101010101010101010101010101010A";
    ProjectModel model = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    ClipModel a = new ClipModel("a", 1, 16);
    ClipModel b = new ClipModel("b", 1, 16);
    a.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .lockedNoteProbArray(lockHex)
            .build());
    b.setArp(ArpModel.defaultConfig().toBuilder().active(true).mode("arp").build());
    synth.addClip(a);
    synth.addClip(b);
    model.addTrack(synth);

    File f = File.createTempFile("perclipbind", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    SynthTrackModel back = (SynthTrackModel) DelugeXmlParser.parseSong(f).getTracks().get(0);

    // No setSound, no setActiveClipIndex, no engine mapping — just the parse.
    assertEquals(
        lockHex,
        back.getClips().get(0).getArp().lockedNoteProbArray(),
        "the file's sequence must survive the round trip, or this test proves nothing");
    byte[] expected = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    for (int i = 0; i < expected.length; i++) {
      expected[i] = (byte) Integer.parseInt(lockHex.substring(i * 2, i * 2 + 2), 16);
    }
    assertArrayEquals(
        expected,
        back.getClips().get(0).getArpSettings().lockedNoteProbabilityValues,
        "and must already be in the clip's own live settings, bound at read time");
  }

  /**
   * A copied clip must not save — and so must not reload — the source clip's arp sequence.
   *
   * <p>C {@code InstrumentClip::instrumentClip(other)} copies the arp with {@code cloneFrom}
   * (instrument_clip.cpp:151), which carries the params and no sequences
   * (arpeggiator.cpp:1620-1650), and the C writes a clip's sequences from its own LIVE settings.
   * This port serializes them from {@code ClipModel.arp}, so the copy has to clear them there too
   * or the source's sequence comes back on reload.
   */
  @Test
  void aCopiedClipDoesNotSaveTheSourcesArpSequence() throws Exception {
    String lockHex = "0101010101010101010101010101010A";
    ProjectModel model = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("LEAD");
    ClipModel a = new ClipModel("a", 1, 16);
    a.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .notePattern("000102030405060708090A0B0C0D0E0F")
            .lockedNoteProbArray(lockHex)
            .build());
    synth.addClip(a);
    ClipModel copy = a.deepCopy("a-copy");
    synth.addClip(copy);
    model.addTrack(synth);

    assertEquals(
        lockHex,
        a.getArp().lockedNoteProbArray(),
        "the source must still carry its own sequence, or this test proves nothing");

    File f = File.createTempFile("perclipcopy", ".xml");
    f.deleteOnExit();
    ProjectSerializer.save(model, f);
    SynthTrackModel back = (SynthTrackModel) DelugeXmlParser.parseSong(f).getTracks().get(0);

    assertEquals(
        lockHex,
        back.getClips().get(0).getArp().lockedNoteProbArray(),
        "the source clip keeps its saved sequence across the round trip");
    ArpModel copiedArp = back.getClips().get(1).getArp();
    // The copy writes no sequence attributes at all, and the reader normalizes an absent locked
    // array to zeros — which is exactly what cloneFrom leaves a copied clip with
    // (arpeggiator.cpp:58-67 fills them with 0 and :1620-1650 does not copy them).
    assertTrue(
        isAbsentOrAllZeroHex(copiedArp.lockedNoteProbArray()),
        "the copy must not have saved the source's locked sequence; got "
            + copiedArp.lockedNoteProbArray());
    assertTrue(
        copiedArp.notePattern() == null || copiedArp.notePattern().isEmpty(),
        "nor the source's note pattern; got " + copiedArp.notePattern());
    // But the copy DOES keep the arp parameters, which is exactly what cloneFrom carries: compare
    // them against the source's own reloaded values rather than against literals, so this does not
    // also assert how mode round-trips.
    ArpModel sourceArp = back.getClips().get(0).getArp();
    assertEquals(sourceArp.active(), copiedArp.active(), "the copy keeps active");
    assertEquals(sourceArp.mode(), copiedArp.mode(), "the copy keeps the mode");
    assertEquals(
        sourceArp.randomizerLock(),
        copiedArp.randomizerLock(),
        "the copy keeps the randomizer lock — a param, not a sequence");
  }

  private static boolean isAbsentOrAllZeroHex(String hex) {
    return hex == null || hex.isEmpty() || hex.chars().allMatch(c -> c == '0');
  }
}
