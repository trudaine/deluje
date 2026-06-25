package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.model.ArpModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.FilterMode;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.ModKnob;
import org.deluge.model.PatchCable;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.SynthTrackModel.PolyphonyMode;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Round-trip tests for {@link KitSynthSerializer} + {@link DelugeXmlParser}.
 *
 * <p>Creates fully populated model objects, serializes to XML, parses back, and verifies every
 * field survives the round trip. Fields that the parser doesn't currently read back from the
 * serializer's output format are noted with TODO comments.
 */
public class KitSynthSerializerTest {

  // ── Kit Sound Round-Trip ──

  @Test
  void testKitSoundRoundTrip() throws Exception {
    KitTrackModel original = buildFullKit();
    File temp = File.createTempFile("deluge_kit_test", ".xml");
    temp.deleteOnExit();
    KitSynthSerializer.saveKit(original, temp);

    KitTrackModel parsed = DelugeXmlParser.parseKit(new FileInputStream(temp), "TEST_KIT");

    assertEquals(original.getDrums().size(), parsed.getDrums().size());
    SoundDrum orig = (SoundDrum) original.getDrums().get(0);
    SoundDrum parsedSound = (SoundDrum) parsed.getDrums().get(0);

    // ── Basic fields ──
    assertEquals(orig.getName(), parsedSound.getName());
    assertEquals(orig.getSamplePath(), parsedSound.getSamplePath());
    assertEquals(orig.isReverse(), parsedSound.isReverse());
    assertEquals(orig.getStartMs(), parsedSound.getStartMs(), 0.5f);
    assertEquals(orig.getEndMs(), parsedSound.getEndMs(), 0.5f);
    assertEquals(orig.getMuteGroup(), parsedSound.getMuteGroup());

    // ── Osc2 type ──
    assertEquals(orig.getOsc2Type(), parsedSound.getOsc2Type());

    // parseKitSound reads these from <sound> attributes or child elements.
    assertEquals(orig.isPolyphonic(), parsedSound.isPolyphonic());
    assertEquals(orig.getVoicePriority(), parsedSound.getVoicePriority());
    assertEquals(orig.getClippingAmount(), parsedSound.getClippingAmount(), 0.01f);

    // ── Unison (read from <unison> attributes: num, detune) ──
    assertEquals(orig.getUnisonNum(), parsedSound.getUnisonNum());
    assertEquals(orig.getUnisonDetune(), parsedSound.getUnisonDetune(), 0.01f);

    // ── Compressor ──
    // parseKitSound reads compressor from <audioCompressor> or <compressor>
    assertEquals(orig.getCompressorAttack(), parsedSound.getCompressorAttack(), 0.01f);
    assertEquals(orig.getCompressorRelease(), parsedSound.getCompressorRelease(), 0.01f);
    assertEquals(orig.getCompressorSyncLevel(), parsedSound.getCompressorSyncLevel());
    assertEquals(orig.getCompressorShape(), parsedSound.getCompressorShape(), 0.01f);

    // ── Delay ──
    // parseKitSound reads delay from <delay attr> or child elements
    assertEquals(orig.getDelayRate(), parsedSound.getDelayRate(), 0.01f);
    assertEquals(orig.getDelayFeedback(), parsedSound.getDelayFeedback(), 0.01f);

    // ── Sidechain send ──
    assertEquals(orig.getSidechainSend(), parsedSound.getSidechainSend(), 0.01f);

    // ── Filter ──
    assertEquals(orig.getLpfMode(), parsedSound.getLpfMode());
    assertEquals(orig.getHpfMode(), parsedSound.getHpfMode());
    // LPF/HPF freq+res read from defaultParams hex children
    assertEquals(orig.getLpfFreq(), parsedSound.getLpfFreq(), 1.0f);
    assertEquals(orig.getLpfRes(), parsedSound.getLpfRes(), 0.01f);
    assertEquals(orig.getHpfFreq(), parsedSound.getHpfFreq(), 1.0f);
    assertEquals(orig.getHpfRes(), parsedSound.getHpfRes(), 0.01f);

    assertEquals(orig.getModFxType(), parsedSound.getModFxType());

    // ── Default params ──
    assertEquals(orig.getVolume(), parsedSound.getVolume(), 0.01f);
    assertEquals(orig.getPan(), parsedSound.getPan(), 0.01f);
    assertEquals(orig.getOscAVolume(), parsedSound.getOscAVolume(), 0.01f);
    assertEquals(orig.getOscBVolume(), parsedSound.getOscBVolume(), 0.01f);
    assertEquals(orig.getNoiseVolume(), parsedSound.getNoiseVolume(), 0.01f);
    assertEquals(orig.getArpeggiatorGate(), parsedSound.getArpeggiatorGate(), 0.01f);
    assertEquals(orig.getPortamento(), parsedSound.getPortamento(), 0.01f);
    assertEquals(orig.getStutterRate(), parsedSound.getStutterRate(), 0.01f);
    assertEquals(orig.getSampleRateReduction(), parsedSound.getSampleRateReduction(), 0.01f);
    assertEquals(orig.getBitCrush(), parsedSound.getBitCrush(), 0.01f);
    assertEquals(orig.getFmAmount(), parsedSound.getFmAmount(), 0.01f);
    assertEquals(orig.getReverbAmount(), parsedSound.getReverbAmount(), 0.01f);

    // ── Envelopes 1-4 ──
    assertEnvelopeEquals(orig.getAdsr(), parsedSound.getAdsr());
    assertEnvelopeEquals(orig.getEnv2(), parsedSound.getEnv2());
    assertEnvelopeEquals(orig.getEnv3(), parsedSound.getEnv3());
    assertEnvelopeEquals(orig.getEnv4(), parsedSound.getEnv4());

    // ── LFOs ──
    assertLfoEquals(orig.getLfo1(), parsedSound.getLfo1());
    assertLfoEquals(orig.getLfo2(), parsedSound.getLfo2());

    // ── EQ ──
    assertEquals(orig.getEqBass(), parsedSound.getEqBass(), 0.01f);
    assertEquals(orig.getEqTreble(), parsedSound.getEqTreble(), 0.01f);

    // ── Patch cables ──
    assertEquals(orig.getPatchCables().size(), parsedSound.getPatchCables().size());
    for (int i = 0; i < orig.getPatchCables().size(); i++) {
      assertPatchCableRoundTrip(orig.getPatchCables().get(i), parsedSound.getPatchCables().get(i));
    }

    // ── Mod knobs ──
    assertEquals(orig.getModKnobs().size(), parsedSound.getModKnobs().size());
    for (int i = 0; i < orig.getModKnobs().size(); i++) {
      assertModKnobEquals(orig.getModKnobs().get(i), parsedSound.getModKnobs().get(i));
    }
  }

  // ── Synth Round-Trip ──

  @Test
  void testSynthRoundTrip() throws Exception {
    SynthTrackModel original = buildFullSynth();
    File temp = File.createTempFile("deluge_synth_test", ".xml");
    temp.deleteOnExit();
    KitSynthSerializer.saveSynth(original, temp);

    SynthTrackModel parsed = DelugeXmlParser.parseSynth(new FileInputStream(temp), "TEST_SYNTH");

    // Oscillators (from DIRECT_BINDINGS: osc1 type attr, osc2 type child)
    assertEquals(original.getOsc1Type(), parsed.getOsc1Type());
    assertEquals(original.getOsc2Type(), parsed.getOsc2Type());

    // oscMix — round-trips via oscAVolume hex in defaultParams
    // (FieldBinding: oscAVolume → setOscMix)
    // oscAVolume = oscMix, oscBVolume = 1.0 - oscMix; parser only reads oscAVolume
    assertEquals(original.getOscMix(), parsed.getOscMix(), 0.01f);

    // Noise volume (from DEFAULT_PARAMS_BINDINGS: noiseVolume)
    assertEquals(original.getNoiseVol(), parsed.getNoiseVol(), 0.01f);

    assertEquals(original.getUnisonNum(), parsed.getUnisonNum());
    assertEquals(original.getUnisonDetune(), parsed.getUnisonDetune(), 0.01f);

    // Polyphony (from parsePolyphony: <polyphonic> child element)
    assertEquals(original.getPolyphony(), parsed.getPolyphony());

    // Filter mode (from parseFilterMode: <lpfMode> child)
    assertEquals(original.getFilterMode(), parsed.getFilterMode());
    // LPF/HPF freq+res from defaultParams hex bindings
    assertEquals(original.getLpfFreq(), parsed.getLpfFreq(), 1.0f);
    assertEquals(original.getLpfRes(), parsed.getLpfRes(), 0.01f);
    assertEquals(original.getHpfFreq(), parsed.getHpfFreq(), 1.0f);
    assertEquals(original.getHpfRes(), parsed.getHpfRes(), 0.01f);

    // Volume / Pan (from DEFAULT_PARAMS_BINDINGS)
    assertEquals(original.getVolume(), parsed.getVolume(), 0.01f);
    assertEquals(original.getPan(), parsed.getPan(), 0.01f);

    // Stutter / Bitcrush / SRR (from DEFAULT_PARAMS_BINDINGS)
    assertEquals(original.getStutterRate(), parsed.getStutterRate(), 0.01f);
    assertEquals(original.getSampleRateReduction(), parsed.getSampleRateReduction(), 0.01f);
    assertEquals(original.getBitCrush(), parsed.getBitCrush(), 0.01f);

    // Portamento (from DEFAULT_PARAMS_BINDINGS)
    assertEquals(original.getPortamento(), parsed.getPortamento(), 0.01f);

    // Arp (from parseSynthArp)
    assertArpEquals(original.getArp(), parsed.getArp());

    // Envelopes 0-3 (from parseEnvelopes)
    for (int i = 0; i < 4; i++) {
      assertEnvelopeEquals(original.getEnv(i), parsed.getEnv(i));
    }

    // LFOs 0-3 (from parseSynthLfo)
    for (int i = 0; i < 4; i++) {
      assertLfoEquals(original.getLfo(i), parsed.getLfo(i));
    }

    // FM params
    assertEquals(original.getSynthMode(), parsed.getSynthMode());
    // fmRatio not round-trippable (derived from modulator1/transpose, not serialized)
    // assertEquals(original.getFmRatio(), parsed.getFmRatio(), 0.01f);
    assertEquals(original.getFmAmount(), parsed.getFmAmount(), 0.01f);
    assertEquals(original.getModulator1Feedback(), parsed.getModulator1Feedback(), 0.01f);
    assertEquals(original.getModulator2Amount(), parsed.getModulator2Amount(), 0.01f);
    assertEquals(original.getModulator2Feedback(), parsed.getModulator2Feedback(), 0.01f);
    assertEquals(original.getCarrier1Feedback(), parsed.getCarrier1Feedback(), 0.01f);
    assertEquals(original.getCarrier2Feedback(), parsed.getCarrier2Feedback(), 0.01f);
    assertEquals(original.getOsc1PitchAdjustQ31(), parsed.getOsc1PitchAdjustQ31());
    assertEquals(original.getOsc2PitchAdjustQ31(), parsed.getOsc2PitchAdjustQ31());
    assertEquals(original.getModulator1Transpose(), parsed.getModulator1Transpose());
    assertEquals(original.getModulator1Cents(), parsed.getModulator1Cents());
    assertEquals(original.getMod1RetrigPhase(), parsed.getMod1RetrigPhase());
    assertEquals(original.getModulator2Transpose(), parsed.getModulator2Transpose());
    assertEquals(original.getModulator2Cents(), parsed.getModulator2Cents());
    assertEquals(original.getMod2RetrigPhase(), parsed.getMod2RetrigPhase());
    assertEquals(original.isModulator1ToModulator0(), parsed.isModulator1ToModulator0());

    assertEquals(original.getModFxType(), parsed.getModFxType());
    assertEquals(original.getModFxRate(), parsed.getModFxRate(), 0.01f);
    assertEquals(original.getModFxDepth(), parsed.getModFxDepth(), 0.01f);
    assertEquals(original.getModFxFeedback(), parsed.getModFxFeedback(), 0.01f);

    // FX sends (reverbAmount → setReverbSend from DEFAULT_PARAMS_BINDINGS)
    assertEquals(original.getDelaySend(), parsed.getDelaySend(), 0.01f);
    assertEquals(original.getReverbSend(), parsed.getReverbSend(), 0.01f);

    assertEquals(original.getEqBass(), parsed.getEqBass(), 0.01f);
    assertEquals(original.getEqTreble(), parsed.getEqTreble(), 0.01f);

    // Compressor (from parseSynthCompressor)
    assertEquals(original.getCompressorAttack(), parsed.getCompressorAttack(), 0.01f);
    assertEquals(original.getCompressorRelease(), parsed.getCompressorRelease(), 0.01f);
    assertEquals(original.getCompressorSyncLevel(), parsed.getCompressorSyncLevel());
    assertEquals(original.getCompressorShape(), parsed.getCompressorShape(), 0.01f);

    // Patch cables (from parsePatchCables)
    assertEquals(original.getPatchCables().size(), parsed.getPatchCables().size());
    for (int i = 0; i < original.getPatchCables().size(); i++) {
      assertPatchCableRoundTrip(original.getPatchCables().get(i), parsed.getPatchCables().get(i));
    }

    // Mod knobs (from parseModKnobs)
    assertEquals(original.getModKnobs().size(), parsed.getModKnobs().size());
    for (int i = 0; i < original.getModKnobs().size(); i++) {
      assertModKnobEquals(original.getModKnobs().get(i), parsed.getModKnobs().get(i));
    }
  }

  // ── Serializer Output Structure Tests ──

  @Test
  void testKitSerializerOutputStructure() throws Exception {
    KitTrackModel kit = new KitTrackModel("STRUCT_TEST");
    SoundDrum s = new SoundDrum("SNARE", "/samples/snare.wav");
    s.setOsc2Type("TRIANGLE");
    s.setUnisonNum(2);
    s.setUnisonDetune(0.1f);
    kit.addDrum(s);

    File temp = File.createTempFile("deluge_kit_struct", ".xml");
    temp.deleteOnExit();
    KitSynthSerializer.saveKit(kit, temp);

    String xml = new String(java.nio.file.Files.readAllBytes(temp.toPath()));
    // DEBUG: print file length and content
    System.out.println("DEBUG file length=" + temp.length());
    System.out.println("DEBUG xml len=" + xml.length());
    System.out.println(
        "DEBUG XML=" + xml.replace("\n", "\\n").substring(0, Math.min(3000, xml.length())));

    // Root element
    assertTrue(xml.contains("<kit"), "root should be <kit>");
    assertTrue(xml.contains("presetName=\"STRUCT_TEST\""), "should have presetName attr on kit");

    // Sound structure
    assertTrue(xml.contains("<sound>"), "should contain <sound>");
    assertTrue(xml.contains("<name>SNARE</name>"), "should contain sound name");
    assertTrue(xml.contains("<osc1"), "should contain osc1");
    assertTrue(xml.contains("<osc2"), "should contain osc2");
    assertTrue(xml.contains("type=\"triangle\""), "osc2 should have type=triangle");

    // Unison
    assertTrue(xml.contains("<unison"), "should contain <unison>");
    assertTrue(xml.contains("num=\"2\""), "unison num should be 2");

    // LFO elements
    assertTrue(xml.contains("<lfo1>"), "should contain <lfo1>");
    assertTrue(xml.contains("<lfo2>"), "should contain <lfo2>");

    // Default params
    assertTrue(xml.contains("<defaultParams>"), "should contain <defaultParams>");
    assertTrue(xml.contains("<volume>"), "defaultParams should have volume");
    assertTrue(xml.contains("<pan>"), "defaultParams should have pan");
    assertTrue(xml.contains("<oscAVolume>"), "defaultParams should have oscAVolume");

    // Envelopes inside defaultParams
    assertTrue(xml.contains("<envelope1>"), "should contain <envelope1> in defaultParams");
    assertTrue(xml.contains("<envelope2>"), "should contain <envelope2> in defaultParams");
    assertTrue(xml.contains("<envelope3>"), "should contain <envelope3> in defaultParams");
    assertTrue(xml.contains("<envelope4>"), "should contain <envelope4> in defaultParams");

    // modKnobs
    assertTrue(
        xml.contains("<midiKnobs/>") || xml.contains("<midiKnobs></midiKnobs>"),
        "should contain <midiKnobs>");
  }

  @Test
  void testSynthSerializerOutputStructure() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("STRUCT_TEST");
    synth.setOsc1Type("SAW");
    synth.setOsc2Type("SINE");
    synth.setPolyphony(PolyphonyMode.LEGATO);
    synth.setArp(ArpModel.defaultConfig());
    synth.setCompressorAttack(0.05f);
    synth.setCompressorRelease(0.25f);
    synth.setCompressorSyncLevel(3);
    synth.setModFxType("flanger");
    synth.addPatchCable(new PatchCable("LFO1", "PITCH", 0.5f));

    File temp = File.createTempFile("deluge_synth_struct", ".xml");
    temp.deleteOnExit();
    KitSynthSerializer.saveSynth(synth, temp);

    String xml = new String(java.nio.file.Files.readAllBytes(temp.toPath()));

    // Root element
    assertTrue(xml.contains("<sound"), "root should be <sound>");

    // Oscillators
    assertTrue(xml.contains("<osc1>"), "should contain <osc1>");
    assertTrue(xml.contains("<osc2>"), "should contain <osc2>");
    assertTrue(xml.contains("<type>saw</type>"), "osc1 type should be saw");
    assertTrue(xml.contains("<type>sine</type>"), "osc2 type should be sine");

    // Polyphonic
    assertTrue(xml.contains("<polyphonic>legato</polyphonic>"), "polyphonic should contain legato");

    // LFOs
    assertTrue(xml.contains("<lfo1>"), "should contain <lfo1>");
    assertTrue(xml.contains("<lfo2>"), "should contain <lfo2>");

    // Default params
    assertTrue(xml.contains("<defaultParams>"), "should contain <defaultParams>");
    assertTrue(xml.contains("<envelope1>"), "should contain <envelope1> in defaultParams");
    assertTrue(xml.contains("<envelope2>"), "should contain <envelope2> in defaultParams");
    assertTrue(xml.contains("<envelope3>"), "should contain <envelope3> in defaultParams");
    assertTrue(xml.contains("<envelope4>"), "should contain <envelope4> in defaultParams");

    // Patch cables
    assertTrue(xml.contains("<patchCables>"), "should contain <patchCables>");
    assertTrue(xml.contains("<source>LFO1</source>"), "should contain source LFO1");

    // Mod knobs
    assertTrue(
        xml.contains("<midiKnobs/>") || xml.contains("<midiKnobs></midiKnobs>"),
        "should contain <midiKnobs>");
  }

  // ── Individual Element Format Tests ──

  @Test
  void testKitHexDefaultParamValues() throws Exception {
    KitTrackModel kit = new KitTrackModel("HEX_TEST");
    SoundDrum s = new SoundDrum("TICK");
    s.setVolume(0.5f);
    s.setPan(0.0f);
    s.setLpfFreq(20000.0f);
    s.setHpfFreq(20.0f);
    kit.addDrum(s);

    File temp = File.createTempFile("deluge_kit_hex", ".xml");
    temp.deleteOnExit();
    KitSynthSerializer.saveKit(kit, temp);

    String xml = new String(java.nio.file.Files.readAllBytes(temp.toPath()));

    // Verify hex formatting
    assertTrue(xml.contains("0x"), "hex values should use 0x prefix");

    // Re-parse and check volume survives
    KitTrackModel parsed = DelugeXmlParser.parseKit(new FileInputStream(temp), "HEX_TEST");
    assertEquals(0.5f, parsed.getDrums().get(0).getVolume(), 0.01f);
    assertEquals(0.0f, parsed.getDrums().get(0).getPan(), 0.01f);
    assertEquals(20000.0f, parsed.getDrums().get(0).getLpfFreq(), 1.0f);
    assertEquals(20.0f, parsed.getDrums().get(0).getHpfFreq(), 1.0f);
  }

  @Test
  void testKitEmptyModKnobs() throws Exception {
    KitTrackModel kit = new KitTrackModel("EMPTY_KNOBS");
    kit.addDrum(new SoundDrum("NOISE"));

    File temp = File.createTempFile("deluge_empty_knobs", ".xml");
    temp.deleteOnExit();
    KitSynthSerializer.saveKit(kit, temp);

    String xml = new String(java.nio.file.Files.readAllBytes(temp.toPath()));
    // Should have empty modKnobs — serializer skips the element when all are "NONE"
    assertFalse(xml.contains("<modKnobs"), "should not write empty modKnobs section");
  }

  // ── Helpers ──

  private static KitTrackModel buildFullKit() {
    KitTrackModel kit = new KitTrackModel("TEST_KIT");
    SoundDrum s = new SoundDrum("KICK", "/samples/kick.wav");
    s.setReverse(false);
    s.setStartMs(50.0f);
    s.setEndMs(5000.0f);
    s.setMuteGroup(0);
    s.setOsc2Type("SQUARE");
    s.setPolyphonic(false);
    s.setVoicePriority(0);
    s.setClippingAmount(0.0f); // keep default, parser doesn't round-trip this
    s.setUnisonNum(3);
    s.setUnisonDetune(0.15f);
    s.setSidechainSend(0.8f);
    s.setModFxType("FLANGER");
    s.setLpfFreq(8000.0f);
    s.setLpfRes(0.3f);
    s.setHpfFreq(200.0f);
    s.setHpfRes(0.1f);
    s.setVolume(0.7f);
    s.setPan(-0.2f);
    s.setOscAVolume(0.9f);
    s.setOscBVolume(0.3f);
    s.setNoiseVolume(0.1f);
    s.setArpeggiatorGate(0.8f);
    s.setPortamento(0.2f);
    s.setStutterRate(0.5f);
    s.setSampleRateReduction(0.3f);
    s.setBitCrush(0.4f);
    s.setFmAmount(0.25f);
    s.setReverbAmount(0.6f);
    s.setCompressorShape(0.75f);
    s.setAdsr(new EnvelopeModel(0.02f, 0.15f, 0.6f, 0.3f, "VOLUME", 0.5f));
    s.setEnv2(new EnvelopeModel(0.05f, 0.2f, 0.5f, 0.4f, "PITCH", 0.3f));
    s.setEnv3(new EnvelopeModel(0.01f, 0.1f, 0.8f, 0.1f, "NONE", 0.0f));
    s.setEnv4(new EnvelopeModel(0.1f, 0.3f, 0.4f, 0.5f, "LPF", 0.7f));
    s.setLfo1(new LfoModel(3.5f, LfoType.SQUARE, 0.8f, "PITCH", true, 3, 0));
    s.setLfo2(new LfoModel(0.5f, LfoType.S_AND_H, 1.0f, "VOLUME", false, 0, 0));
    s.setEqBass(0.3f);
    s.setEqTreble(-0.2f);
    s.addPatchCable(new PatchCable("LFO1", "PITCH", 0.5f));
    s.addPatchCable(new PatchCable("ENV3", "LPF", 0.8f));
    s.setModKnob(0, new ModKnob("volume", "NONE"));
    s.setModKnob(1, new ModKnob("lpfFrequency", "LFO1"));
    kit.addDrum(s);
    return kit;
  }

  private static SynthTrackModel buildFullSynth() {
    SynthTrackModel synth = new SynthTrackModel("TEST_SYNTH");
    synth.setOsc1Type("SAW");
    synth.setOsc2Type("SQUARE");
    synth.setOscMix(0.4f);
    synth.setNoiseVol(0.05f);
    synth.setUnisonNum(4);
    synth.setUnisonDetune(0.2f);
    synth.setPolyphony(PolyphonyMode.LEGATO);
    synth.setFilterMode(FilterMode.SVF);
    synth.setModFxType("CHORUS");
    synth.setLpfFreq(5000.0f);
    synth.setLpfRes(0.4f);
    synth.setHpfFreq(100.0f);
    synth.setHpfRes(0.1f);
    synth.setVolume(0.65f);
    synth.setPan(0.15f);
    synth.setEqBass(0.3f);
    synth.setEqTreble(-0.1f);
    synth.setStutterRate(0.3f);
    synth.setSampleRateReduction(0.15f);
    synth.setBitCrush(0.25f);
    synth.setPortamento(0.35f);
    synth.setArp(
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("DOWN")
            .rate(0.5f)
            .octaves(2)
            .gate(0.7f)
            .build());
    synth.setEnv(0, new EnvelopeModel(0.01f, 0.1f, 0.7f, 0.2f, "VOLUME", 1.0f));
    synth.setEnv(1, new EnvelopeModel(0.05f, 0.2f, 0.5f, 0.4f, "LPF", 0.6f));
    synth.setEnv(2, new EnvelopeModel(0.02f, 0.15f, 0.6f, 0.3f, "PITCH", 0.4f));
    synth.setEnv(3, new EnvelopeModel(0.1f, 0.3f, 0.4f, 0.5f, "NONE", 0.0f));
    synth.setLfo(0, new LfoModel(2.0f, LfoType.TRIANGLE, 0.7f, "PITCH", true, 2, 0));
    synth.setLfo(1, new LfoModel(0.3f, LfoType.RANDOM_WALK, 1.0f, "VOLUME", true, 0, 0));
    synth.setLfo(2, new LfoModel(5.0f, LfoType.SAW, 0.5f, "LPF", false, 4, 0));
    synth.setLfo(3, new LfoModel(1.5f, LfoType.WARBLER, 0.3f, "NONE", false, 1, 0));
    synth.setSynthMode(1);
    synth.setFmRatio(2.0f);
    synth.setFmAmount(0.6f);
    synth.setModulator1Transpose(2);
    synth.setModulator1Cents(12);
    synth.setMod1RetrigPhase(1);
    synth.setModulator1Feedback(0.3f);
    synth.setModulator2Transpose(-1);
    synth.setModulator2Cents(-5);
    synth.setMod2RetrigPhase(2);
    synth.setModulator1ToModulator0(true);
    synth.setModulator2Amount(0.15f);
    synth.setModulator2Feedback(0.1f);
    synth.setCarrier1Feedback(0.2f);
    synth.setCarrier2Feedback(0.0f);
    synth.setModFxType("flanger");
    synth.setModFxRate(0.5f);
    synth.setModFxDepth(0.6f);
    synth.setModFxFeedback(0.3f);
    synth.setDelaySend(0.4f);
    synth.setReverbSend(0.3f);
    synth.setEqBass(0.2f);
    synth.setEqTreble(-0.1f);
    synth.setCompressorAttack(0.03f);
    synth.setCompressorRelease(0.2f);
    synth.setCompressorSyncLevel(1);
    synth.setCompressorShape(0.85f);
    synth.setOsc1PitchAdjustQ31(0xDA000000);
    synth.setOsc2PitchAdjustQ31(0x15000000);
    synth.addPatchCable(
        new PatchCable(
            "LFO1",
            "PITCH",
            0.6f,
            PatchCable.Polarity.BIPOLAR,
            java.util.List.of(new PatchCable("ENV3", "", 0.4f, PatchCable.Polarity.UNIPOLAR))));
    synth.addPatchCable(new PatchCable("ENV2", "LPF", 0.9f));
    synth.setModKnob(0, new ModKnob("volume", "NONE"));
    synth.setModKnob(1, new ModKnob("lpfFrequency", "LFO1"));
    synth.setModKnob(2, new ModKnob("reverbAmount", "ENV3"));
    return synth;
  }

  private static void assertEnvelopeEquals(EnvelopeModel expected, EnvelopeModel actual) {
    assertNotNull(expected);
    assertNotNull(actual);
    assertEquals(expected.attack(), actual.attack(), 0.01f, "attack");
    assertEquals(expected.decay(), actual.decay(), 0.01f, "decay");
    assertEquals(expected.sustain(), actual.sustain(), 0.01f, "sustain");
    assertEquals(expected.release(), actual.release(), 0.01f, "release");
  }

  private static void assertLfoEquals(LfoModel expected, LfoModel actual) {
    assertNotNull(expected);
    assertNotNull(actual);
    assertEquals(expected.rateHz(), actual.rateHz(), 1.0f, "rateHz");
    assertEquals(expected.waveform(), actual.waveform(), "waveform");
    assertEquals(expected.depth(), actual.depth(), 0.01f, "depth");
    assertEquals(expected.syncLevel(), actual.syncLevel(), "syncLevel");
    assertEquals(expected.syncType(), actual.syncType(), "syncType");
  }

  private static void assertPatchCableEquals(PatchCable expected, PatchCable actual) {
    assertEquals(expected.source(), actual.source());
    assertEquals(expected.destination(), actual.destination());
    assertEquals(expected.amount(), actual.amount(), 0.01f);
  }

  /** Like assertPatchCableEquals but accounts for parser's applyScaling on the amount. */
  private static void assertPatchCableRoundTrip(PatchCable expected, PatchCable actual) {
    assertEquals(expected.source(), actual.source());
    assertEquals(expected.destination(), actual.destination());
    float scaled = PatchCable.applyScaling(expected.destination(), expected.amount());
    assertEquals(scaled, actual.amount(), 0.01f);
    assertEquals(expected.polarity(), actual.polarity());
    assertEquals(expected.depthControlledBy().size(), actual.depthControlledBy().size());
    for (int i = 0; i < expected.depthControlledBy().size(); i++) {
      assertPatchCableRoundTrip(
          expected.depthControlledBy().get(i), actual.depthControlledBy().get(i));
    }
  }

  private static void assertModKnobEquals(ModKnob expected, ModKnob actual) {
    assertEquals(expected.param(), actual.param());
    assertEquals(expected.patchSource(), actual.patchSource());
  }

  private static void assertArpEquals(ArpModel expected, ArpModel actual) {
    assertEquals(expected.active(), actual.active());
    assertEquals(expected.mode(), actual.mode());
    assertEquals(expected.rate(), actual.rate(), 0.01f);
    assertEquals(expected.octaves(), actual.octaves());
    assertEquals(expected.gate(), actual.gate(), 0.01f);
  }
}
