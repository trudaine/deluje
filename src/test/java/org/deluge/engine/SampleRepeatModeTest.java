package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.firmware2.Sound;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A sample source's "loopMode" is the C SampleRepeatMode — CUT 0, ONCE 1, LOOP 2, STRETCH 3
 * (definitions_cxx.hpp:493-498) — end to end. fw2 used its own 0 off / 1 loop / 2 once, so a ONCE
 * sample looped and a LOOP sample played once; and kit drums never read osc1's flags at all, so
 * every drum was CUT (a note-off cut it) instead of the stock kits' ONCE.
 */
class SampleRepeatModeTest {

  private static final File KIT_808 = new File("src/main/resources/KITS/000 TR-808.XML");

  @Test
  void repeatModeConstantsAreTheCEnum() {
    // C definitions_cxx.hpp:493-498.
    assertEquals(0, Sound.REPEAT_CUT);
    assertEquals(1, Sound.REPEAT_ONCE);
    assertEquals(2, Sound.REPEAT_LOOP);
    assertEquals(3, Sound.REPEAT_STRETCH);
  }

  @Test
  void stockKitDrumsLoadAsOnceAndNoteOffDoesNotCutThem() throws Exception {
    Assumptions.assumeTrue(KIT_808.isFile());
    KitTrackModel kit = DelugeXmlParser.parseKit(KIT_808);
    SoundDrum kick = (SoundDrum) kit.getDrums().get(0);
    assertEquals(1, kick.getOsc1LoopMode(), "<loopMode>1</loopMode> (tag form) = ONCE");

    ProjectModel project = new ProjectModel();
    project.addTrack(kit);
    FirmwareFactory.createSong(project);
    FirmwareKit fk = (FirmwareKit) kit.getClips().get(0).getSound();
    FirmwareSound drum = fk.drumSounds.get(0);
    assertEquals(Sound.REPEAT_ONCE, drum.sampleSettings[0].loopMode);
    drum.syncParamsToFw2();
    // syncParamsToFw2 copies the repeat mode only for a loaded sample.
    Assumptions.assumeTrue(drum.fw2Sound.hasAnySampleLoaded(0), "808 samples not on disk");
    assertEquals(Sound.REPEAT_ONCE, drum.fw2Sound.sampleLoopMode[0]);
    assertFalse(
        drum.fw2Sound.allowNoteTails(false),
        "a ONCE drum plays to its end: the C ignores its note-off (sound.cpp:1957)");
  }

  @Test
  void kitSaveKeepsEachDrumsRepeatMode() throws Exception {
    Assumptions.assumeTrue(KIT_808.isFile());
    KitTrackModel kit = DelugeXmlParser.parseKit(KIT_808);
    SoundDrum kick = (SoundDrum) kit.getDrums().get(0);
    kick.setOsc1LoopMode(2); // LOOP — the writer used to hardcode 1 for every drum
    kick.setOsc1TimeStretch(true);
    File f = File.createTempFile("kit", ".XML");
    f.deleteOnExit();
    KitSynthSerializer.saveKit(kit, f);
    SoundDrum back = (SoundDrum) DelugeXmlParser.parseKit(f).getDrums().get(0);
    assertEquals(2, back.getOsc1LoopMode());
    assertTrue(back.isOsc1TimeStretch());
  }

  @Test
  void savedSampleSynthKeepsItsFileAndRepeatMode() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("S");
    synth.setOsc1Type("SAMPLE");
    synth.setOsc1SamplePath("SAMPLES/DRUMS/Kick/808 Kick.wav");
    synth.setOsc1LoopMode(2);
    synth.addClip(new ClipModel("c", 1, 16));
    File f = File.createTempFile("synth", ".XML");
    f.deleteOnExit();
    KitSynthSerializer.saveSynth(synth, f);
    SynthTrackModel back = DelugeXmlParser.parseSynth(f);
    assertEquals("SAMPLES/DRUMS/Kick/808 Kick.wav", back.getOsc1SamplePath());
    assertEquals(2, back.getOsc1LoopMode());
  }

  @Test
  void songSaveKeepsDrumFlagsWhereTheCReadsThem() throws Exception {
    Assumptions.assumeTrue(KIT_808.isFile());
    KitTrackModel kit = DelugeXmlParser.parseKit(KIT_808);
    SoundDrum kick = (SoundDrum) kit.getDrums().get(0);
    kick.setOsc1LoopMode(2);
    kick.setOsc1LinearInterpolation(true);
    ProjectModel project = new ProjectModel();
    project.addTrack(kit);
    String xml = org.deluge.project.ProjectSerializer.serializeToString(project);
    // fileName and the flags sit on <osc1>, where sound.cpp reads them — not a <sample> child.
    assertTrue(xml.contains("fileName=\"" + kick.getSamplePath() + "\""), "fileName on osc1");
    assertFalse(xml.contains("<sample "), "no <sample> element inside a drum's <sound>");
    KitTrackModel back =
        (KitTrackModel)
            DelugeXmlParser.parseSong(
                    new java.io.ByteArrayInputStream(
                        xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "rt")
                .getTracks()
                .get(0);
    SoundDrum kickBack = (SoundDrum) back.getDrums().get(0);
    assertEquals(kick.getSamplePath(), kickBack.getSamplePath());
    assertEquals(2, kickBack.getOsc1LoopMode());
    assertTrue(kickBack.isOsc1LinearInterpolation());
    assertEquals(1, ((SoundDrum) back.getDrums().get(1)).getOsc1LoopMode(), "snare stays ONCE");
  }
}
