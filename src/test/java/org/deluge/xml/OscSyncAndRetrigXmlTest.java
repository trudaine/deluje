package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.model.Drum;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@code <oscillatorSync>} and {@code <retrigPhase>} / {@code <oscillatorReset>} parsing,
 * preset-resolution preservation, and kit {@link SoundDrum} serialization against C {@code
 * sound.cpp:603-614, 3241-3243, 3389-3393, 3701-3703}.
 */
class OscSyncAndRetrigXmlTest {

  private static SynthTrackModel parseSynth(String xml) throws Exception {
    return DelugeXmlParser.parseSynth(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "TestSynth");
  }

  private static KitTrackModel parseKit(String xml) throws Exception {
    return KitXmlParser.parseKit(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "TestKit");
  }

  @Test
  void readFromFileDefaultsRetrigPhaseAndModulator2TransposeToZero() throws Exception {
    // C sound.cpp:3241-3243 — Sound::readFromFile sets modulatorTranspose[1] = 0,
    // oscRetriggerPhase.fill(0), and modulatorRetriggerPhase.fill(0) before reading XML tags.
    SynthTrackModel synth =
        parseSynth(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc1><type>saw</type></osc1>
              <osc2><type>saw</type></osc2>
              <mode>fm</mode>
            </sound>
            """);
    assertEquals(0, synth.getOsc1RetrigPhase());
    assertEquals(0, synth.getOsc2RetrigPhase());
    assertEquals(0, synth.getMod1RetrigPhase());
    assertEquals(0, synth.getMod2RetrigPhase());
    assertEquals(0, synth.getModulator2Transpose());
  }

  @Test
  void oscillatorResetOneKeepsAllRetrigPhasesAtZero() throws Exception {
    // C sound.cpp:603-614, 3241-3243 — 16 factory presets (e.g. "039 Detuned Retriggering Saws")
    // have <oscillatorReset>1</oscillatorReset> with no <retrigPhase> on <osc1>/<osc2>.
    SynthTrackModel synth =
        parseSynth(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc1><type>saw</type></osc1>
              <osc2><type>saw</type></osc2>
              <oscillatorReset>1</oscillatorReset>
            </sound>
            """);
    assertEquals(0, synth.getOsc1RetrigPhase());
    assertEquals(0, synth.getOsc2RetrigPhase());
    assertEquals(0, synth.getMod1RetrigPhase());
    assertEquals(0, synth.getMod2RetrigPhase());
  }

  @Test
  void oscillatorResetZeroNotClobberedByModulatorTagsWithoutRetrigPhase() throws Exception {
    // C sound.cpp:523-568, 603-614 — <oscillatorReset>0</oscillatorReset> sets all four retrigger
    // phases to -1 (0xFFFFFFFF). <modulator1>/<modulator2> tags that omit <retrigPhase> (e.g.
    // "074 Electric Piano.XML") must NOT overwrite -1 back to 0.
    SynthTrackModel synth =
        parseSynth(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound>
              <osc1><type>sine</type></osc1>
              <osc2><type>sine</type></osc2>
              <oscillatorReset>0</oscillatorReset>
              <mode>fm</mode>
              <modulator1><transpose>0</transpose><cents>0</cents></modulator1>
              <modulator2><transpose>-12</transpose><cents>0</cents><toModulator1>0</toModulator1></modulator2>
            </sound>
            """);
    assertEquals(-1, synth.getOsc1RetrigPhase());
    assertEquals(-1, synth.getOsc2RetrigPhase());
    assertEquals(-1, synth.getMod1RetrigPhase());
    assertEquals(-1, synth.getMod2RetrigPhase());
  }

  @Test
  void embeddedOsc2WithoutOscillatorSyncPlaysSyncOffEvenIfThePresetHasIt() throws Exception {
    // C: a song's instrument is read from the song's own <sound> into a fresh Sound (sync false,
    // sound.h:150); the preset file is never consulted, and oscillatorSync is written only when
    // true (sound.cpp:3701). ALLSYN_1.XML's "045 Square Sync" omits it, and the hardware recording
    // plays it without sync (scorecard 0.663 -> 0.960 once this matches).
    SynthTrackModel preserved =
        parseSynth(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound presetName="045 Square Sync" presetFolder="SYNTHS">
              <osc2 type="square" transpose="7" retrigPhase="-1" />
            </sound>
            """);
    assertFalse(
        preserved.isOscillatorSync(),
        "an embedded <osc2> without oscillatorSync is sync OFF, whatever the preset says");

    SynthTrackModel overriddenOff =
        parseSynth(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <sound presetName="045 Square Sync" presetFolder="SYNTHS">
              <osc2 type="square" transpose="7" oscillatorSync="0" retrigPhase="-1" />
            </sound>
            """);
    assertFalse(
        overriddenOff.isOscillatorSync(),
        "explicit oscillatorSync=0 on embedded <osc2> must override the preset");
  }

  @Test
  void kitSoundDrumParsesMapsAndRoundTripsOscillatorSyncAndRetrig(@TempDir Path tempDir)
      throws Exception {
    KitTrackModel kit =
        parseKit(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <kit>
              <soundSources>
                <sound name="SyncedLeadDrum">
                  <osc1><type>square</type></osc1>
                  <osc2>
                    <type>square</type>
                    <transpose>7</transpose>
                    <oscillatorSync>1</oscillatorSync>
                  </osc2>
                  <oscillatorReset>0</oscillatorReset>
                  <modulator1><transpose>0</transpose></modulator1>
                  <modulator2><transpose>-12</transpose></modulator2>
                </sound>
              </soundSources>
            </kit>
            """);
    assertEquals(1, kit.getDrums().size());
    Drum d = kit.getDrums().get(0);
    SoundDrum sd = assertInstanceOf(SoundDrum.class, d);
    assertTrue(sd.isOscillatorSync(), "KitXmlParser must read <oscillatorSync>1</oscillatorSync>");
    assertEquals(-1, sd.getOsc1RetrigPhase());
    assertEquals(-1, sd.getOsc2RetrigPhase());
    assertEquals(-1, sd.getMod1RetrigPhase());
    assertEquals(-1, sd.getMod2RetrigPhase());

    // FirmwareFactory mapping to FirmwareKit drum sound
    ProjectModel project = new ProjectModel();
    FirmwareKit fwKit = FirmwareFactory.createKitSound(kit, project);
    assertTrue(
        fwKit.drumSounds.get(0).fw2Sound.oscillatorSync,
        "FirmwareFactory must copy SoundDrum.isOscillatorSync() to drumSound.fw2Sound");

    // Round-trip through KitSynthSerializer
    File kitFile = tempDir.resolve("synced_kit.xml").toFile();
    KitSynthSerializer.saveKit(kit, kitFile);
    KitTrackModel reloadedKit = KitXmlParser.parseKit(kitFile);
    SoundDrum reloadedSd = assertInstanceOf(SoundDrum.class, reloadedKit.getDrums().get(0));
    assertTrue(
        reloadedSd.isOscillatorSync(),
        "KitSynthSerializer must round-trip oscillatorSync=1 on SoundDrum");

    // Round-trip through ProjectSerializer
    project.addTrack(kit);
    File songFile = tempDir.resolve("synced_song.xml").toFile();
    ProjectSerializer.save(project, songFile);
    ProjectModel reloadedProject = DelugeXmlParser.parseSong(songFile);
    KitTrackModel songKit =
        assertInstanceOf(KitTrackModel.class, reloadedProject.getTracks().get(0));
    SoundDrum songSd = assertInstanceOf(SoundDrum.class, songKit.getDrums().get(0));
    assertTrue(
        songSd.isOscillatorSync(),
        "ProjectSerializer must round-trip oscillatorSync=1 on kit SoundDrum");
  }
}
