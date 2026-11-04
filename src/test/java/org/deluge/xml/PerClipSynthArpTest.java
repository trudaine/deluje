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
}
