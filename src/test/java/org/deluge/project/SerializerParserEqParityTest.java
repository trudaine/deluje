package org.deluge.project;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JSlider;
import org.deluge.BridgeContract;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.FilterMode;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.ui.EqPanel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for C++ XML serialization, parsing, and EQ UI scaling parity across
 * ProjectSerializer, KitSynthSerializer, InstrumentXmlParser, KitXmlParser, SongXmlParser, and
 * EqPanel.
 */
class SerializerParserEqParityTest {

  @Test
  void projectSerializerSynthRoundTripsIndependentOscVolumesFmModFxArpAndSync() throws Exception {
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setOsc1Type("ANALOG_SAW");
    synth.setOsc2Type("ANALOG_SQUARE");
    synth.setOscAVolume(1.0f);
    synth.setOscBVolume(0.85f);
    synth.setFmAmount(0.15f);
    synth.setModulator2Amount(0.42f);
    synth.setModFxOffset(0.65f);
    synth.setModFxFeedback(0.35f);
    synth.setArpRateQ31(0x12345678);
    synth.setOscillatorSync(true);
    synth.setPolyphony(SynthTrackModel.PolyphonyMode.AUTO);
    synth.setClippingAmount(4);
    synth.setVoicePriority(2);
    synth.setLfo(0, new LfoModel(3.5f, LfoType.TRIANGLE, 0.5f, "NONE", false, 7, 1)); // syncType=1

    ClipModel clip = new ClipModel("C1", 8, 16);
    synth.addClip(clip);
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);

    // Exact firmware case-sensitive oscType strings (C functions.cpp:734-815).
    assertTrue(xml.contains("type=\"analogSaw\""), "osc1 must write exact analogSaw token");
    assertTrue(xml.contains("type=\"analogSquare\""), "osc2 must write exact analogSquare token");
    assertTrue(xml.contains("oscillatorSync=\"1\""), "osc2 must write oscillatorSync=\"1\"");
    assertTrue(xml.contains("<polyphonic>auto</polyphonic>"), "AUTO polyphony must serialize");
    assertTrue(
        xml.contains("<arpeggiatorRate>0x12345678</arpeggiatorRate>"),
        "arpeggiatorRate must serialize raw Q31");

    // modFXOffset and modFXFeedback must appear once per defaultParams, not duplicated.
    assertEquals(1, countOccurrences(xml, "<modFXOffset>"), "modFXOffset must not be duplicated");
    assertEquals(
        1, countOccurrences(xml, "<modFXFeedback>"), "modFXFeedback must not be duplicated");

    ProjectModel parsedProject =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "SYNTH_ROUNDTRIP");
    SynthTrackModel parsed = (SynthTrackModel) parsedProject.getTracks().get(0);

    assertEquals(1.0f, parsed.getOscAVolume(), 0.01f, "oscAVolume must round-trip at 1.0");
    assertEquals(
        0.85f,
        parsed.getOscBVolume(),
        0.01f,
        "oscBVolume must stay independent (0.85) instead of collapsing to 1 - oscAVolume");
    assertEquals(0.15f, parsed.getFmAmount(), 0.01f);
    assertEquals(
        0.42f,
        parsed.getModulator2Amount(),
        0.01f,
        "modulator2Amount must not copy modulator1Amount");
    assertEquals(0.65f, parsed.getModFxOffset(), 0.01f, "modFXOffset must round-trip");
    assertEquals(0.35f, parsed.getModFxFeedback(), 0.01f, "modFXFeedback must round-trip");
    assertEquals(0x12345678, parsed.getArpRateQ31(), "arpRateQ31 must round-trip");
    assertTrue(parsed.isOscillatorSync(), "oscillatorSync must round-trip");
    assertEquals(SynthTrackModel.PolyphonyMode.AUTO, parsed.getPolyphony());
    assertEquals(4, parsed.getClippingAmount());
    assertEquals(2, parsed.getVoicePriority());
    assertEquals(
        1,
        parsed.getLfo(0).syncType(),
        "LFO syncType written as child tag must round-trip through InstrumentXmlParser");
  }

  @Test
  void projectSerializerSampleSynthAndKitDrumRoundTripFlagsAndTuning() throws Exception {
    ProjectModel project = new ProjectModel();

    SynthTrackModel sampleSynth = new SynthTrackModel("SMP_SYNTH");
    sampleSynth.setOsc1Type("SAMPLE");
    sampleSynth.setOsc1SamplePath("SAMPLES/LEAD.WAV");
    sampleSynth.setOsc1LinearInterpolation(true);
    sampleSynth.setOsc2Type("SAMPLE");
    sampleSynth.setOsc2SamplePath("SAMPLES/SUB.WAV");
    sampleSynth.setOsc2LinearInterpolation(true);
    sampleSynth.addClip(new ClipModel("S1", 8, 16));
    project.addTrack(sampleSynth);

    KitTrackModel kit = new KitTrackModel("DRUMS");
    SoundDrum drum = new SoundDrum("KICK");
    drum.setLpfMode(FilterMode.SVF_BAND);
    drum.setHpfMode(FilterMode.OFF);
    drum.setPolyphony(SynthTrackModel.PolyphonyMode.CHOKE);
    drum.setSamplePath("SAMPLES/KICK.WAV");
    drum.setOsc1Transpose(-5);
    drum.setOsc1Cents(25);
    drum.setOsc1RetrigPhase(180);
    drum.setOsc2Type("ANALOG_SAW");
    drum.setOsc2Transpose(7);
    drum.setOsc2Cents(-15);
    drum.setOsc2RetrigPhase(90);
    drum.setOsc2SamplePath("SAMPLES/LAYER.WAV");
    drum.setOsc2LoopMode(2);
    drum.setOsc2Reversed(true);
    drum.setOsc2TimeStretch(true);
    drum.setOsc2LinearInterpolation(true);
    kit.addDrum(drum);
    kit.addClip(new ClipModel("K1", 1, 16));
    project.addTrack(kit);

    String xml = ProjectSerializer.serializeToString(project);
    ProjectModel parsedProject =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "SAMPLE_KIT_ROUNDTRIP");

    SynthTrackModel parsedSynth = (SynthTrackModel) parsedProject.getTracks().get(0);
    assertTrue(
        parsedSynth.isOsc1LinearInterpolation(), "Synth osc1 linearInterpolation must round-trip");
    assertTrue(
        parsedSynth.isOsc2LinearInterpolation(), "Synth osc2 linearInterpolation must round-trip");

    KitTrackModel parsedKit = (KitTrackModel) parsedProject.getTracks().get(1);
    SoundDrum parsedDrum = (SoundDrum) parsedKit.getDrums().get(0);
    assertEquals(FilterMode.SVF_BAND, parsedDrum.getLpfMode(), "Drum lpfMode must round-trip");
    assertEquals(FilterMode.OFF, parsedDrum.getHpfMode(), "Drum hpfMode must round-trip");
    assertEquals(SynthTrackModel.PolyphonyMode.CHOKE, parsedDrum.getPolyphony());
    assertEquals(-5, parsedDrum.getOsc1Transpose());
    assertEquals(25, parsedDrum.getOsc1Cents());
    assertEquals(180, parsedDrum.getOsc1RetrigPhase());
    assertEquals("ANALOGSAW", parsedDrum.getOsc2Type());
    assertEquals(7, parsedDrum.getOsc2Transpose());
    assertEquals(-15, parsedDrum.getOsc2Cents());
    assertEquals(90, parsedDrum.getOsc2RetrigPhase());
    assertEquals("SAMPLES/LAYER.WAV", parsedDrum.getOsc2SamplePath());
    assertEquals(2, parsedDrum.getOsc2LoopMode());
    assertTrue(parsedDrum.isOsc2Reversed());
    assertTrue(parsedDrum.isOsc2TimeStretch());
    assertTrue(parsedDrum.isOsc2LinearInterpolation());
  }

  @Test
  void kitSynthSerializerRoundTripsSynthAndKitFields(@TempDir File tempDir) throws Exception {
    SynthTrackModel synth = new SynthTrackModel("PRESET_SYNTH");
    synth.setOsc1Type("SAMPLE");
    synth.setOsc1SamplePath("SAMPLES/PAD.WAV");
    synth.setOsc1LinearInterpolation(true);
    synth.setOsc2Type("ANALOG_SAW");
    synth.setOscillatorSync(true);
    synth.setPolyphony(SynthTrackModel.PolyphonyMode.CHOKE);
    synth.setClippingAmount(5);
    synth.setVoicePriority(2);
    synth.setModFxOffset(0.70f);
    synth.setModFxFeedback(0.45f);
    synth.setLfo(
        0, new LfoModel(2.0f, LfoType.SINE, 0.4f, "NONE", false, 6, 2)); // syncType=2 (DOTTED)

    File synthFile = new File(tempDir, "PRESET_SYNTH.XML");
    KitSynthSerializer.saveSynth(synth, synthFile);
    String synthXml = Files.readString(synthFile.toPath(), StandardCharsets.UTF_8);

    assertEquals(
        1,
        countOccurrences(synthXml, "<modFXOffset>"),
        "KitSynthSerializer synth must write modFXOffset once");
    assertEquals(
        1,
        countOccurrences(synthXml, "<modFXFeedback>"),
        "KitSynthSerializer synth must write modFXFeedback once");

    SynthTrackModel parsedSynth = DelugeXmlParser.parseSynth(synthFile);
    assertTrue(parsedSynth.isOsc1LinearInterpolation());
    assertTrue(parsedSynth.isOscillatorSync());
    assertEquals(SynthTrackModel.PolyphonyMode.CHOKE, parsedSynth.getPolyphony());
    assertEquals(5, parsedSynth.getClippingAmount());
    assertEquals(2, parsedSynth.getVoicePriority());
    assertEquals(0.70f, parsedSynth.getModFxOffset(), 0.01f);
    assertEquals(0.45f, parsedSynth.getModFxFeedback(), 0.01f);
    assertEquals(2, parsedSynth.getLfo(0).syncType(), "Child <syncType> on synth LFO must parse");

    KitTrackModel kit = new KitTrackModel("PRESET_KIT");
    SoundDrum drum = new SoundDrum("SNARE");
    drum.setSamplePath("SAMPLES/SNARE.WAV");
    drum.setOsc1Transpose(-3);
    drum.setOsc1Cents(12);
    drum.setOsc2Type("NONE");
    drum.setOsc2Transpose(4);
    drum.setOsc2Cents(-9);
    drum.setModFxOffset(0.60f);
    drum.setModFxFeedback(0.40f);
    kit.addDrum(drum);

    File kitFile = new File(tempDir, "PRESET_KIT.XML");
    KitSynthSerializer.saveKit(kit, kitFile);
    String kitXml = Files.readString(kitFile.toPath(), StandardCharsets.UTF_8);

    assertEquals(
        1,
        countOccurrences(kitXml, "<modFXOffset>"),
        "KitSynthSerializer kit drum must write modFXOffset once");
    assertEquals(
        1,
        countOccurrences(kitXml, "<modFXFeedback>"),
        "KitSynthSerializer kit drum must write modFXFeedback once");

    KitTrackModel parsedKit = DelugeXmlParser.parseKit(kitFile);
    SoundDrum parsedDrum = (SoundDrum) parsedKit.getDrums().get(0);
    assertEquals(-3, parsedDrum.getOsc1Transpose());
    assertEquals(12, parsedDrum.getOsc1Cents());
    assertEquals(4, parsedDrum.getOsc2Transpose());
    assertEquals(-9, parsedDrum.getOsc2Cents());
    assertEquals(0.60f, parsedDrum.getModFxOffset(), 0.01f);
    assertEquals(0.40f, parsedDrum.getModFxFeedback(), 0.01f);
  }

  @Test
  void audioClipEqBassParsesBipolarAndLegacyPolyphonyCode2MapsToChoke() throws Exception {
    ProjectModel project = new ProjectModel();

    AudioTrackModel audioTrack = new AudioTrackModel("VOX");
    AudioTrackModel.AudioClip audioClip = new AudioTrackModel.AudioClip();
    audioClip.setTrackName("VOX");
    audioClip.setFilePath("SAMPLES/VOX.WAV");
    audioClip.setLength(16);
    audioClip.setEqBass(-0.5f);
    audioClip.setEqTreble(0.25f);
    audioTrack.addAudioClip(audioClip);
    project.addTrack(audioTrack);

    String xml = ProjectSerializer.serializeToString(project);
    ProjectModel parsedProject =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "AUDIO_EQ");

    AudioTrackModel parsedTrack = (AudioTrackModel) parsedProject.getTracks().get(0);
    AudioTrackModel.AudioClip parsedClip = parsedTrack.getAudioClips().get(0);
    assertEquals(
        -0.5f,
        parsedClip.getEqBass(),
        0.01f,
        "AudioClip eqBass must parse as bipolar (-0.5) instead of unipolar");
    assertEquals(0.25f, parsedClip.getEqTreble(), 0.01f);

    // Verify legacy pre-2017 polyphonic="2" parses as CHOKE (C functions.cpp:870-871).
    String legacySynthXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound presetName="LEGACY" polyphonic="2">
          <defaultParams/>
        </sound>
        """;
    SynthTrackModel legacySynth =
        DelugeXmlParser.parseSynth(
            new ByteArrayInputStream(legacySynthXml.getBytes(StandardCharsets.UTF_8)), "LEGACY");
    assertEquals(SynthTrackModel.PolyphonyMode.CHOKE, legacySynth.getPolyphony());
  }

  @Test
  void eqPanelMapsSliderPercentToNormalizedBipolarRange() {
    SynthTrackModel model = new SynthTrackModel("EQ_TEST");
    model.setEqBass(-0.50f);
    model.setEqTreble(0.75f);

    ProjectModel project = new ProjectModel();
    project.addTrack(model);
    BridgeContract bridge = new BridgeContract();
    try {
      EqPanel panel = new EqPanel(model, bridge, 0, project);
      List<JSlider> sliders = new ArrayList<>();
      collectSliders(panel, sliders);
      assertEquals(2, sliders.size(), "EqPanel must contain Bass and Treble sliders");

      JSlider bassSlider = sliders.get(0);
      JSlider trebleSlider = sliders.get(1);
      assertEquals(-50, bassSlider.getValue(), "Bass slider must read -0.50 as -50%");
      assertEquals(75, trebleSlider.getValue(), "Treble slider must read 0.75 as 75%");

      bassSlider.setValue(25);
      trebleSlider.setValue(-40);
      assertEquals(0.25f, model.getEqBass(), 1e-4f, "Bass slider 25% must write 0.25f, not 3.0f");
      assertEquals(
          -0.40f, model.getEqTreble(), 1e-4f, "Treble slider -40% must write -0.40f, not -4.8f");
    } finally {
      bridge.shutdown();
    }
  }

  private static void collectSliders(Container root, List<JSlider> out) {
    for (Component comp : root.getComponents()) {
      if (comp instanceof JSlider s) {
        out.add(s);
      } else if (comp instanceof Container child) {
        collectSliders(child, out);
      }
    }
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = text.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
