package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.deluge.engine.DelugeatorRandomizer;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Functions;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.FilterMode;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.NoteModel;
import org.deluge.model.PatchCable;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.xml.DelugeXmlExporter;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies lossless XML preset, kit, and full-song serialization/parsing and live DSP parity across
 * {@link KitSynthSerializer}, {@link ProjectSerializer}, {@link DelugeXmlExporter}, {@link
 * org.deluge.xml.InstrumentXmlParser}, {@link org.deluge.xml.KitXmlParser}, {@link
 * org.deluge.xml.SongXmlParser}, {@link DelugeatorRandomizer}, and {@link FirmwareFactory} against
 * C++ firmware ({@code mod_controllable_audio.cpp}, {@code filter_config.cpp}, {@code sound.cpp},
 * {@code functions.cpp}, and {@code patch_cable_set.cpp}).
 */
class KitSynthPresetXmlRoundTripParityTest {

  private static final int BLOCK_SIZE = 128;

  @Test
  void synthPresetRoundTripsBitExactAudioAndAllFirmwareTags(@TempDir Path tempDir)
      throws Exception {
    SynthTrackModel original = new SynthTrackModel("ParitySynth");
    original.setSynthMode(1); // FM
    original.setFilterMode(FilterMode.SVF_BAND);
    original.setHpfMode(FilterMode.SVF_NOTCH);
    original.setFilterRoute(2); // PARALLEL -> "PARA"
    original.setLpfFreq(1200.0f);
    original.setHpfFreq(300.0f);
    original.setLpfMorph(0.65f);
    original.setHpfMorph(0.35f);
    original.setClippingAmount(5);
    original.setModFxType("StereoChorus");
    original.setModFxRate(0.45f);
    original.setModFxDepth(0.55f);

    // Delay with delaySend == 0 still writes unconditional <delay> with exact pingPong/analog/sync
    original.setDelaySend(0.0f);
    original.setDelayFeedbackQ31(0xBA000000);
    original.setDelayPingPong(false);
    original.setDelayAnalog(false);
    original.setDelaySyncLevel(6);
    original.setDelaySyncType(2); // DOTTED -> file 19

    // 2-op FM exact signed Q31 knobs + modulator chain
    original.setModulator1Transpose(12);
    original.setModulator1Cents(-15);
    original.setModulator2Transpose(-7);
    original.setModulator2Cents(20);
    original.setModulator1ToModulator0(true);
    original.setModulator1AmountQ31(0x5A827999);
    original.setModulator1FeedbackQ31(0xEC000000);
    original.setModulator2AmountQ31(0x28000000);
    original.setModulator2FeedbackQ31(0xD4000000);
    original.setCarrier1FeedbackQ31(0xC0000000);
    original.setCarrier2FeedbackQ31(0x18000000);
    original.setWaveFoldQ31(0x30000000);

    // Equalizer bass/treble + frequencies
    original.setEqBass(0.4f);
    original.setEqTreble(-0.3f);
    original.setEqBassFreq(-0.25f);
    original.setEqTrebleFreq(0.55f);

    // All four LFOs (lfo1..lfo4), including hardware "sah" and "rwalk" waveforms
    original.setLfo(0, new LfoModel(4.5f, LfoType.TRIANGLE, 0.5f, "NONE", true, 0, 0));
    original.setLfo(1, new LfoModel(1.25f, LfoType.S_AND_H, 0.4f, "NONE", false, 0, 0));
    original.setLfo(2, new LfoModel(7.75f, LfoType.RANDOM_WALK, 0.3f, "NONE", true, 5, 1));
    original.setLfo(3, new LfoModel(0.6f, LfoType.SQUARE, 0.2f, "NONE", false, 8, 2));

    // Patch cable with exact Q31 on quadratic-scaled PITCH destination; preset <defaultParams>
    // keeps base 0x40000000 without appending clip automation nodes (C sound.cpp:4251)
    List<ParamNode> nodes =
        List.of(new ParamNode(0, 0x10000000, true), new ParamNode(48, 0x60000000, false));
    original
        .getModulation()
        .addPatchCable(
            new PatchCable(
                "lfo1", "pitch", 0.25f, PatchCable.Polarity.BIPOLAR, List.of(), nodes, 0x40000000));

    int[] preSaveAudio = renderSynthSamples(original);

    File presetFile = tempDir.resolve("parity_synth.XML").toFile();
    KitSynthSerializer.saveSynth(original, presetFile);
    String savedXml = Files.readString(presetFile.toPath());
    assertTrue(savedXml.contains("<filterRoute>PARA</filterRoute>"));
    assertTrue(savedXml.contains("<modFXType>StereoChorus</modFXType>"));
    assertTrue(savedXml.contains("<type>sah</type>"));
    assertTrue(savedXml.contains("<type>rwalk</type>"));
    assertTrue(savedXml.contains("<syncType>19</syncType>"));
    assertTrue(savedXml.contains("<amount>0x40000000</amount>"));
    assertFalse(savedXml.contains("0x4000000010000000"));

    SynthTrackModel reloaded = DelugeXmlParser.parseSynth(presetFile);
    assertEquals(1, reloaded.getSynthMode());
    assertEquals(FilterMode.SVF_BAND, reloaded.getFilterMode());
    assertEquals(FilterMode.SVF_NOTCH, reloaded.getHpfMode());
    assertEquals(2, reloaded.getFilterRoute());
    assertEquals(5, reloaded.getClippingAmount());
    assertEquals(0.65f, reloaded.getLpfMorph(), 0.01f);
    assertEquals(0.35f, reloaded.getHpfMorph(), 0.01f);
    assertFalse(reloaded.isDelayPingPong());
    assertFalse(reloaded.isDelayAnalog());
    assertEquals(6, reloaded.getDelaySyncLevel());
    assertEquals(2, reloaded.getDelaySyncType());
    assertEquals(0xBA000000, reloaded.getDelayFeedbackQ31());
    assertEquals(0x5A827999, reloaded.getModulator1AmountQ31());
    assertEquals(0xEC000000, reloaded.getModulator1FeedbackQ31());
    assertEquals(0x28000000, reloaded.getModulator2AmountQ31());
    assertEquals(0xD4000000, reloaded.getModulator2FeedbackQ31());
    assertEquals(0xC0000000, reloaded.getCarrier1FeedbackQ31());
    assertEquals(0x18000000, reloaded.getCarrier2FeedbackQ31());
    assertEquals(0x30000000, reloaded.getWaveFoldQ31());
    assertTrue(reloaded.isModulator1ToModulator0());
    assertEquals(-0.25f, reloaded.getEqBassFreq(), 0.01f);
    assertEquals(0.55f, reloaded.getEqTrebleFreq(), 0.01f);
    assertEquals(LfoType.S_AND_H, reloaded.getLfo(1).waveform());
    assertEquals(LfoType.RANDOM_WALK, reloaded.getLfo(2).waveform());
    assertEquals(4.5f, reloaded.getLfo(0).rateHz(), 0.05f);
    assertEquals(1.25f, reloaded.getLfo(1).rateHz(), 0.05f);
    assertEquals(7.75f, reloaded.getLfo(2).rateHz(), 0.1f);
    assertEquals(0.6f, reloaded.getLfo(3).rateHz(), 0.05f);
    assertEquals(1, reloaded.getLfo(2).syncType());
    assertEquals(2, reloaded.getLfo(3).syncType());
    assertEquals(1, reloaded.getModulation().getPatchCables().size());
    assertEquals(0x40000000, reloaded.getModulation().getPatchCables().get(0).resolveAmountQ31());

    // Pre-save DSP output vs post-save/reload DSP output MUST be bit-for-bit identical (0 LSB
    // error)
    assertArrayEquals(preSaveAudio, renderSynthSamples(reloaded));

    // Verify DelugeXmlExporter emits lpfMode, hpfMode, filterRoute, lpfMorph, and hpfMorph cleanly
    File exportedFile = tempDir.resolve("exported_synth.XML").toFile();
    DelugeXmlExporter.saveSynthPreset(original, exportedFile);
    SynthTrackModel exportedReloaded = DelugeXmlParser.parseSynth(exportedFile);
    assertEquals(FilterMode.SVF_BAND, exportedReloaded.getFilterMode());
    assertEquals(FilterMode.SVF_NOTCH, exportedReloaded.getHpfMode());
    assertEquals(2, exportedReloaded.getFilterRoute());
    assertEquals(0.65f, exportedReloaded.getLpfMorph(), 0.01f);
    assertEquals(0.35f, exportedReloaded.getHpfMorph(), 0.01f);
  }

  @Test
  void kitSoundDrumsRoundTripBitExactPreSaveVsPostReloadAndSampleZones(@TempDir Path tempDir)
      throws Exception {
    KitTrackModel kit = new KitTrackModel("ParityKit");
    kit.getDrums().clear();

    // Drum 0: 2-op chained FM drum with 4 LFOs, delay, L2H filter route, morph, clipping, EQ freqs
    SoundDrum fmDrum = new SoundDrum("FM TOM", "");
    fmDrum.setOsc1Type("SINE");
    fmDrum.setOscAVolume(0.9f);
    fmDrum.setSynthMode(1); // "fm"
    fmDrum.setClippingAmount(5);
    fmDrum.setModFxType("TapeWarble");
    fmDrum.setModulator1Transpose(7);
    fmDrum.setModulator1Cents(-12);
    fmDrum.setMod1RetrigPhase(0);
    fmDrum.setModulator2Transpose(19);
    fmDrum.setModulator2Cents(25);
    fmDrum.setMod2RetrigPhase(1073741824);
    fmDrum.setModulator1ToModulator0(true);
    fmDrum.setModulator1AmountQ31(0x50000000);
    fmDrum.setModulator1FeedbackQ31(0xE0000000);
    fmDrum.setModulator2AmountQ31(0x30000000);
    fmDrum.setModulator2FeedbackQ31(0xC8000000);
    fmDrum.setCarrier1FeedbackQ31(0xD0000000);
    fmDrum.setCarrier2FeedbackQ31(0x10000000);
    fmDrum.setFilterRoute(1); // L2H
    fmDrum.setLpfMode(FilterMode.SVF_BAND);
    fmDrum.setHpfMode(FilterMode.SVF_NOTCH);
    fmDrum.setLpfFreq(1400.0f);
    fmDrum.setHpfFreq(260.0f);
    fmDrum.setLpfMorph(0.55f);
    fmDrum.setHpfMorph(0.25f);
    fmDrum.setDelayPingPong(0);
    fmDrum.setDelayAnalog(1);
    fmDrum.setDelaySyncLevel(5);
    fmDrum.setDelaySyncType(1); // TRIPLET -> file 10
    fmDrum.setDelayRateQ31(0x10000000);
    fmDrum.setDelayFeedbackQ31(0xBA000000);
    fmDrum.setArpeggiatorRate(0.7f);
    fmDrum.setEqBass(0.3f);
    fmDrum.setEqTreble(-0.2f);
    fmDrum.setEqBassFreq(0.35f);
    fmDrum.setEqTrebleFreq(-0.45f);
    fmDrum.setLfo1(new LfoModel(5.0f, LfoType.SINE, 0.5f, "NONE", true, 0, 0));
    fmDrum.setLfo2(new LfoModel(2.0f, LfoType.S_AND_H, 0.4f, "NONE", false, 0, 0));
    fmDrum.setLfo3(new LfoModel(8.5f, LfoType.SAW, 0.3f, "NONE", true, 6, 1));
    fmDrum.setLfo4(new LfoModel(0.75f, LfoType.SQUARE, 0.2f, "NONE", false, 7, 2));
    fmDrum.addPatchCable(
        new PatchCable(
            "lfo3",
            "lpfFrequency",
            0.3f,
            PatchCable.Polarity.BIPOLAR,
            List.of(),
            List.of(),
            0x26666666));

    // Drum 1: Ringmod metallic percussion with PARA filter routing
    SoundDrum ringDrum = new SoundDrum("RING HAT", "");
    ringDrum.setOsc1Type("SQUARE");
    ringDrum.setOsc2Type("SAW");
    ringDrum.setOsc2Transpose(7);
    ringDrum.setOscAVolume(0.85f);
    ringDrum.setOscBVolume(0.85f);
    ringDrum.setSynthMode(2); // "ringmod"
    ringDrum.setFilterRoute(2); // PARA
    ringDrum.setLpfFreq(2500.0f);
    ringDrum.setHpfFreq(600.0f);
    ringDrum.setDelayPingPong(1);
    ringDrum.setDelayAnalog(0);
    ringDrum.setDelaySyncLevel(8);
    ringDrum.setDelaySyncType(2); // DOTTED -> file 19

    // Drum 2: Sample drum verifying <soundSources>, exact <zone> sample bounds, and no forced 1ms
    SoundDrum sampleDrum = new SoundDrum("SAMPLE KICK", "SAMPLES/DRUMS/Kick.wav");
    sampleDrum.setStartSamplePos(128);
    sampleDrum.setEndSamplePos(22050);
    sampleDrum.setStartLoopPos(1024);
    sampleDrum.setEndLoopPos(8192);
    sampleDrum.setEndMs(0.0f);

    kit.addDrum(fmDrum);
    kit.addDrum(ringDrum);
    kit.addDrum(sampleDrum);

    int[] preSaveKitAudio = renderKitSamples(kit);

    File kitFile = tempDir.resolve("parity_kit.XML").toFile();
    KitSynthSerializer.saveKit(kit, kitFile);
    String savedKitXml = Files.readString(kitFile.toPath());
    assertTrue(savedKitXml.contains("<soundSources>"));
    assertTrue(savedKitXml.contains("<modFXType>TapeWarble</modFXType>"));
    assertFalse(savedKitXml.contains("endMilliseconds=\"1\""));

    KitTrackModel reloadedKit = DelugeXmlParser.parseKit(kitFile);
    assertEquals(3, reloadedKit.getDrums().size());

    SoundDrum d0 = (SoundDrum) reloadedKit.getDrums().get(0);
    assertEquals(1, d0.getSynthMode());
    assertEquals(5.0f, d0.getClippingAmount(), 0.001f);
    assertEquals(7, d0.getModulator1Transpose());
    assertEquals(-12, d0.getModulator1Cents());
    assertEquals(0, d0.getMod1RetrigPhase());
    assertEquals(19, d0.getModulator2Transpose());
    assertEquals(25, d0.getModulator2Cents());
    assertEquals(1073741824, d0.getMod2RetrigPhase());
    assertTrue(d0.isModulator1ToModulator0());
    assertEquals(0x50000000, d0.getModulator1AmountQ31());
    assertEquals(0xE0000000, d0.getModulator1FeedbackQ31());
    assertEquals(0x30000000, d0.getModulator2AmountQ31());
    assertEquals(0xC8000000, d0.getModulator2FeedbackQ31());
    assertEquals(0xD0000000, d0.getCarrier1FeedbackQ31());
    assertEquals(0x10000000, d0.getCarrier2FeedbackQ31());
    assertEquals(1, d0.getFilterRoute());
    assertEquals(FilterMode.SVF_BAND, d0.getLpfMode());
    assertEquals(FilterMode.SVF_NOTCH, d0.getHpfMode());
    assertEquals(0.55f, d0.getLpfMorph(), 0.01f);
    assertEquals(0.25f, d0.getHpfMorph(), 0.01f);
    assertEquals(0, d0.getDelayPingPong());
    assertEquals(1, d0.getDelayAnalog());
    assertEquals(5, d0.getDelaySyncLevel());
    assertEquals(1, d0.getDelaySyncType());
    assertEquals(0x10000000, d0.getDelayRateQ31());
    assertEquals(0xBA000000, d0.getDelayFeedbackQ31());
    assertEquals(0.7f, d0.getArpeggiatorRate(), 0.01f);
    assertEquals(0.35f, d0.getEqBassFreq(), 0.01f);
    assertEquals(-0.45f, d0.getEqTrebleFreq(), 0.01f);
    assertEquals(5.0f, d0.getLfo1().rateHz(), 0.05f);
    assertEquals(2.0f, d0.getLfo2().rateHz(), 0.05f);
    assertEquals(8.5f, d0.getLfo3().rateHz(), 0.1f);
    assertEquals(0.75f, d0.getLfo4().rateHz(), 0.05f);
    assertEquals(1, d0.getLfo3().syncType());
    assertEquals(2, d0.getLfo4().syncType());
    assertEquals(0x26666666, d0.getPatchCables().get(0).resolveAmountQ31());

    SoundDrum d2 = (SoundDrum) reloadedKit.getDrums().get(2);
    assertEquals(128, d2.getStartSamplePos());
    assertEquals(22050, d2.getEndSamplePos());
    assertEquals(1024, d2.getStartLoopPos());
    assertEquals(8192, d2.getEndLoopPos());
    assertEquals(0.0f, d2.getEndMs(), 0.001f);

    // Pre-save kit DSP render vs post-save/reload kit DSP render MUST match bit-for-bit!
    assertArrayEquals(preSaveKitAudio, renderKitSamples(reloadedKit));
  }

  @Test
  void soundDrumDspFeaturesProduceMeasurablyDistinctAudioInFirmwareEngine() {
    assertFalse(
        Arrays.equals(
            renderSingleDrum(buildBaseDrum(false)), renderSingleDrum(buildBaseDrum(true))));

    SoundDrum h2l = buildBaseDrum(true);
    h2l.setFilterRoute(0);
    SoundDrum para = buildBaseDrum(true);
    para.setFilterRoute(2);
    assertFalse(Arrays.equals(renderSingleDrum(h2l), renderSingleDrum(para)));

    SoundDrum morphLow = buildBaseDrum(true);
    morphLow.setLpfMorph(0.1f);
    SoundDrum morphHigh = buildBaseDrum(true);
    morphHigh.setLpfMorph(0.9f);
    assertFalse(Arrays.equals(renderSingleDrum(morphLow), renderSingleDrum(morphHigh)));

    SoundDrum pingPongOff = buildBaseDrum(true);
    pingPongOff.setDelayFeedbackQ31(0x50000000);
    pingPongOff.setDelayPingPong(0);
    SoundDrum pingPongOn = buildBaseDrum(true);
    pingPongOn.setDelayFeedbackQ31(0x50000000);
    pingPongOn.setDelayPingPong(1);
    assertFalse(Arrays.equals(renderSingleDrum(pingPongOff), renderSingleDrum(pingPongOn)));

    SoundDrum eqLowCorner = buildBaseDrum(true);
    eqLowCorner.setEqBass(0.8f);
    eqLowCorner.setEqBassFreq(-0.9f);
    SoundDrum eqHighCorner = buildBaseDrum(true);
    eqHighCorner.setEqBass(0.8f);
    eqHighCorner.setEqBassFreq(0.9f);
    assertFalse(Arrays.equals(renderSingleDrum(eqLowCorner), renderSingleDrum(eqHighCorner)));

    SoundDrum unclipped = buildBaseDrum(true);
    unclipped.setClippingAmount(0);
    SoundDrum saturated = buildBaseDrum(true);
    saturated.setClippingAmount(5);
    assertFalse(Arrays.equals(renderSingleDrum(unclipped), renderSingleDrum(saturated)));

    SoundDrum unpatched = buildBaseDrum(true);
    SoundDrum lfo3Patched = buildBaseDrum(true);
    lfo3Patched.setLfo3(new LfoModel(12.0f, LfoType.SAW, 0.8f, "NONE", true, 0, 0));
    lfo3Patched.addPatchCable(
        new PatchCable(
            "lfo3",
            "lpfFrequency",
            0.5f,
            PatchCable.Polarity.BIPOLAR,
            List.of(),
            List.of(),
            0x40000000));
    assertFalse(Arrays.equals(renderSingleDrum(unpatched), renderSingleDrum(lfo3Patched)));
  }

  @Test
  void delugeatorRandomizerCompilesLiveUsablePatchCablesAndRoundTripsBitExact(@TempDir Path tempDir)
      throws Exception {
    for (int seed = 0; seed < 8; seed++) {
      SynthTrackModel model = new SynthTrackModel("RandomSynth_" + seed);
      ProjectModel project = new ProjectModel();
      DelugeatorRandomizer.randomizeSynth(
          model, project, new DelugeatorRandomizer.RandomizerSettings(), new Random(1000L + seed));

      assertFalse("NOISE".equalsIgnoreCase(model.getOsc1Type()));
      assertFalse("NOISE".equalsIgnoreCase(model.getOsc2Type()));
      assertTrue(
          model.getUnison().getUnisonDetune() >= 0.0f
              && model.getUnison().getUnisonDetune() <= 50.0f);

      FirmwareSound sound = new FirmwareSound();
      FirmwareFactory.applyModelToLiveSound(model, sound);
      sound.syncParamsToFw2();
      assertFalse(
          sound.fw2Sound.patchCableSet.destinations.isEmpty(),
          "Randomized patch cables must compile usable destinations for seed " + seed);

      int[] before = renderSynthSamples(model);
      File saved = tempDir.resolve("randomized_synth_" + seed + ".XML").toFile();
      KitSynthSerializer.saveSynth(model, saved);
      SynthTrackModel after = DelugeXmlParser.parseSynth(saved);
      assertArrayEquals(
          before, renderSynthSamples(after), "Bit-exact round-trip mismatch on seed " + seed);
    }
  }

  @Test
  void realHardwareSynthPresetsPreserveBitExactWaveformAcrossKitSynthSerializerRoundTrip(
      @TempDir Path tempDir) throws Exception {
    String[] resources = {
      "/fidelity/049 Basic FM.XML",
      "/fidelity/009 Hoover Bass.XML",
      "/calibration/SYNTHS/18 CAL FM CHAIN.XML",
      "/calibration/SYNTHS/20 CAL FM FEEDBACK.XML",
      "/calibration/SYNTHS/21 CAL DELAY SYNC.XML",
      "/calibration/SYNTHS/22 CAL DELAY PINGPONG.XML",
      "/calibration/SYNTHS/24 CAL CHORUS.XML",
      "/fidelity/121_FILTER_MORPH_SAW_C5.XML",
      "/fidelity/123_HIGH_LFO_RATE_C5.XML"
    };

    for (String resource : resources) {
      SynthTrackModel before;
      try (InputStream is = getClass().getResourceAsStream(resource)) {
        assertNotNull(is, "Missing test resource: " + resource);
        String xml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        before =
            DelugeXmlParser.parseSynth(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), resource);
      }

      int[] beforeAudio = renderSynthSamples(before);
      File saved = tempDir.resolve(resource.substring(resource.lastIndexOf('/') + 1)).toFile();
      KitSynthSerializer.saveSynth(before, saved);
      SynthTrackModel after = DelugeXmlParser.parseSynth(saved);
      int[] afterAudio = renderSynthSamples(after);

      assertArrayEquals(
          beforeAudio,
          afterAudio,
          "Bit-exact rendered stereo PCM mismatch after round-trip on " + resource);
    }
  }

  @Test
  void fullSongRoundTripPreservesSynthAndKitDspBitExactAndAllTags(@TempDir Path tempDir)
      throws Exception {
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth = new SynthTrackModel("SongSynth");
    synth.setSynthMode(1); // FM
    synth.setFilterMode(FilterMode.SVF_BAND);
    synth.setHpfMode(FilterMode.SVF_NOTCH);
    synth.setFilterRoute(2); // PARALLEL -> "PARA"
    synth.setLpfFreq(1300.0f);
    synth.setHpfFreq(320.0f);
    synth.setLpfMorph(0.6f);
    synth.setHpfMorph(0.3f);
    synth.setClippingAmount(5);
    synth.setCompressorThreshold(0.35f);
    synth.setModFxType("StereoChorus");
    synth.setModFxRate(0.45f);
    synth.setModFxDepth(0.55f);
    synth.setDelaySend(0.0f);
    synth.setDelayFeedbackQ31(0xBA000000);
    synth.setDelayPingPong(false);
    synth.setDelayAnalog(false);
    synth.setDelaySyncLevel(5);
    synth.setDelaySyncType(2); // DOTTED -> file 19
    synth.setModulator1Transpose(12);
    synth.setModulator1Cents(-15);
    synth.setModulator2Transpose(-7);
    synth.setModulator2Cents(20);
    synth.setModulator1ToModulator0(true);
    synth.setModulator1AmountQ31(0x5A827999);
    synth.setModulator1FeedbackQ31(0xEC000000);
    synth.setModulator2AmountQ31(0x28000000);
    synth.setModulator2FeedbackQ31(0xD4000000);
    synth.setCarrier1FeedbackQ31(0xC0000000);
    synth.setCarrier2FeedbackQ31(0x18000000);
    synth.setWaveFoldQ31(0x2C000000);
    synth.setEqBass(0.45f);
    synth.setEqTreble(-0.35f);
    synth.setEqBassFreq(-0.3f);
    synth.setEqTrebleFreq(0.6f);
    synth.setPitchAdjustQ31(0x08000000);
    synth.getOsc1().setPitchAdjustQ31(0x04000000);
    synth.getOsc2().setPitchAdjustQ31(0xFC000000);
    synth.getRawKnobs().getRawParamKnobs().put(Param.LOCAL_MODULATOR_0_PITCH_ADJUST, 0x0C000000);
    synth.getRawKnobs().getRawParamKnobs().put(Param.LOCAL_MODULATOR_1_PITCH_ADJUST, 0xF8000000);
    synth.setEnv(0, new EnvelopeModel(0.025f, 0.18f, 0.65f, 0.35f, "NONE", 0.0f));
    synth.setEnv(1, new EnvelopeModel(0.05f, 0.30f, 0.45f, 0.50f, "NONE", 0.0f));
    synth.setEnv(2, new EnvelopeModel(0.08f, 0.42f, 0.25f, 0.60f, "NONE", 0.0f));
    synth.setEnv(3, new EnvelopeModel(0.12f, 0.55f, 0.75f, 0.80f, "NONE", 0.0f));
    synth.setLfo(0, new LfoModel(4.25f, LfoType.TRIANGLE, 0.5f, "NONE", true, 0, 0));
    synth.setLfo(1, new LfoModel(1.75f, LfoType.S_AND_H, 0.4f, "NONE", false, 0, 0));
    synth.setLfo(2, new LfoModel(8.25f, LfoType.RANDOM_WALK, 0.3f, "NONE", true, 5, 1));
    synth.setLfo(3, new LfoModel(0.65f, LfoType.SQUARE, 0.2f, "NONE", false, 8, 2));
    synth
        .getModulation()
        .addPatchCable(
            new PatchCable(
                "lfo1",
                "pitch",
                0.25f,
                PatchCable.Polarity.BIPOLAR,
                List.of(),
                List.of(),
                0x40000000));
    ClipModel synthClip = new ClipModel("Synth Clip", 192, 0);
    synthClip.getOrCreateRow(60).notes.add(new NoteModel(0, 48, 0.8f, 1.0f, 1));
    synth.addClip(synthClip);

    KitTrackModel kit = new KitTrackModel("SongKit");
    kit.getDrums().clear();
    SoundDrum fmDrum = new SoundDrum("SONG FM TOM", "");
    fmDrum.setOsc1Type("SINE");
    fmDrum.setOscAVolume(0.9f);
    fmDrum.setSynthMode(1);
    fmDrum.setMaxVoiceCount(4);
    fmDrum.setClippingAmount(5);
    fmDrum.setModFxType("TapeWarble");
    fmDrum.setModulator1Transpose(7);
    fmDrum.setModulator1Cents(-12);
    fmDrum.setMod1RetrigPhase(0);
    fmDrum.setModulator2Transpose(19);
    fmDrum.setModulator2Cents(25);
    fmDrum.setMod2RetrigPhase(1073741824);
    fmDrum.setModulator1ToModulator0(true);
    fmDrum.setModulator1AmountQ31(0x50000000);
    fmDrum.setModulator1FeedbackQ31(0xE0000000);
    fmDrum.setModulator2AmountQ31(0x30000000);
    fmDrum.setModulator2FeedbackQ31(0xC8000000);
    fmDrum.setCarrier1FeedbackQ31(0xD0000000);
    fmDrum.setCarrier2FeedbackQ31(0x10000000);
    fmDrum.setFilterRoute(1);
    fmDrum.setLpfMode(FilterMode.SVF_BAND);
    fmDrum.setHpfMode(FilterMode.SVF_NOTCH);
    fmDrum.setLpfFreq(1400.0f);
    fmDrum.setHpfFreq(260.0f);
    fmDrum.setLpfMorph(0.55f);
    fmDrum.setHpfMorph(0.25f);
    fmDrum.setDelayPingPong(0);
    fmDrum.setDelayAnalog(1);
    fmDrum.setDelaySyncLevel(5);
    fmDrum.setDelaySyncType(1);
    fmDrum.setDelayRateQ31(0x10000000);
    fmDrum.setDelayFeedbackQ31(0xBA000000);
    fmDrum.setEqBass(0.3f);
    fmDrum.setEqTreble(-0.2f);
    fmDrum.setEqBassFreq(0.35f);
    fmDrum.setEqTrebleFreq(-0.45f);
    fmDrum.setAdsr(new EnvelopeModel(0.02f, 0.22f, 0.55f, 0.3f, "NONE", 0.0f));
    fmDrum.setEnv2(new EnvelopeModel(0.04f, 0.28f, 0.35f, 0.4f, "NONE", 0.0f));
    fmDrum.setLfo1(new LfoModel(5.0f, LfoType.SINE, 0.5f, "NONE", true, 0, 0));
    fmDrum.setLfo2(new LfoModel(2.0f, LfoType.S_AND_H, 0.4f, "NONE", false, 0, 0));
    fmDrum.setLfo3(new LfoModel(8.5f, LfoType.SAW, 0.3f, "NONE", true, 6, 1));
    fmDrum.setLfo4(new LfoModel(0.75f, LfoType.SQUARE, 0.2f, "NONE", false, 7, 2));
    fmDrum.addPatchCable(
        new PatchCable(
            "lfo3",
            "lpfFrequency",
            0.3f,
            PatchCable.Polarity.BIPOLAR,
            List.of(),
            List.of(),
            0x26666666));

    SoundDrum ringDrum = new SoundDrum("SONG RING HAT", "");
    ringDrum.setOsc1Type("SQUARE");
    ringDrum.setOsc2Type("SAW");
    ringDrum.setOsc2Transpose(7);
    ringDrum.setOscAVolume(0.85f);
    ringDrum.setOscBVolume(0.85f);
    ringDrum.setSynthMode(2);
    ringDrum.setFilterRoute(2);
    ringDrum.setLpfFreq(2500.0f);
    ringDrum.setHpfFreq(600.0f);

    kit.addDrum(fmDrum);
    kit.addDrum(ringDrum);
    ClipModel kitClip = new ClipModel("Kit Clip", 192, 0);
    kitClip.getOrCreateRow(0).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    kitClip.getOrCreateRow(1).notes.add(new NoteModel(48, 24, 0.8f, 1.0f, 1));
    kit.addClip(kitClip);

    project.addTrack(synth);
    project.addTrack(kit);

    int[] preSaveSynthAudio = renderSynthSamples(synth);
    int[] preSaveKitAudio = renderKitSamples(kit);

    String songXml = ProjectSerializer.serializeToString(project);
    assertFalse(songXml.contains("<clippingAmount>5.0</clippingAmount>"));
    assertFalse(songXml.contains("<pitch>0x"));
    assertTrue(songXml.contains("<clippingAmount>5</clippingAmount>"));
    assertTrue(songXml.contains("<filterRoute>PARA</filterRoute>"));

    File savedSong = tempDir.resolve("roundtrip_song.XML").toFile();
    Files.writeString(savedSong.toPath(), songXml, StandardCharsets.UTF_8);

    ProjectModel reloaded = DelugeXmlParser.parseSong(savedSong);
    SynthTrackModel reloadedSynth = (SynthTrackModel) reloaded.getTracks().get(0);
    KitTrackModel reloadedKit = (KitTrackModel) reloaded.getTracks().get(1);

    assertEquals(5, reloadedSynth.getClippingAmount());
    assertEquals(2, reloadedSynth.getFilterRoute());
    assertEquals(FilterMode.SVF_BAND, reloadedSynth.getFilterMode());
    assertEquals(FilterMode.SVF_NOTCH, reloadedSynth.getHpfMode());
    assertEquals(0.35f, reloadedSynth.getCompressorThreshold(), 0.01f);
    assertEquals(0x08000000, reloadedSynth.getPitchAdjustQ31());
    assertEquals(
        0x0C000000,
        reloadedSynth.getRawKnobs().getRawParamKnobs().get(Param.LOCAL_MODULATOR_0_PITCH_ADJUST));
    assertEquals(
        0xF8000000,
        reloadedSynth.getRawKnobs().getRawParamKnobs().get(Param.LOCAL_MODULATOR_1_PITCH_ADJUST));
    assertEquals(4.25f, reloadedSynth.getLfo(0).rateHz(), 0.05f);
    assertEquals(1.75f, reloadedSynth.getLfo(1).rateHz(), 0.05f);
    assertEquals(8.25f, reloadedSynth.getLfo(2).rateHz(), 0.1f);
    assertEquals(0.65f, reloadedSynth.getLfo(3).rateHz(), 0.05f);
    assertEquals(0.65f, reloadedSynth.getEnv(0).sustain(), 0.01f);
    assertEquals(0.75f, reloadedSynth.getEnv(3).sustain(), 0.01f);

    SoundDrum reloadedFmDrum = (SoundDrum) reloadedKit.getDrums().get(0);
    assertEquals(4, reloadedFmDrum.getMaxVoiceCount());
    assertEquals(1, reloadedFmDrum.getSynthMode());
    assertEquals(5.0f, reloadedFmDrum.getClippingAmount(), 0.001f);

    assertArrayEquals(preSaveSynthAudio, renderSynthSamples(reloadedSynth));
    assertArrayEquals(preSaveKitAudio, renderKitSamples(reloadedKit));
  }

  @Test
  void wavetableOscillatorIndexSweepAndStereoChorusRoundTripBitExact(@TempDir Path tempDir)
      throws Exception {
    File wtFile = tempDir.resolve("harmonic_sweep_wt.wav").toFile();
    writeFourCycleWavetableWav(wtFile);

    SynthTrackModel lowPos = new SynthTrackModel("WtLow");
    lowPos.setOsc1Type("WAVETABLE");
    lowPos.setOsc1SamplePath(wtFile.getAbsolutePath());
    lowPos.setOscAVolume(0.9f);
    lowPos.setOscBVolume(0.0f);
    lowPos.setWaveIndex(0.0f); // 0% -> cycle 0 (pure fundamental)

    SynthTrackModel midPos = new SynthTrackModel("WtMid");
    midPos.setOsc1Type("WAVETABLE");
    midPos.setOsc1SamplePath(wtFile.getAbsolutePath());
    midPos.setOscAVolume(0.9f);
    midPos.setOscBVolume(0.0f);
    midPos.setWaveIndex(0.5f); // 50% -> midpoint cycle blend

    int[] lowAudio = renderSynthSamples(lowPos);
    int[] midAudio = renderSynthSamples(midPos);
    assertFalse(
        Arrays.equals(lowAudio, midAudio),
        "Wavetable positions 0.0 (0%) and 0.5 (50%) must render distinct harmonic cycles");

    // Independent osc1 vs osc2 wavetable positions + STEREO_CHORUS alias round-trip
    SynthTrackModel dualWt = new SynthTrackModel("DualWt");
    dualWt.setOsc1Type("WAVETABLE");
    dualWt.setOsc1SamplePath(wtFile.getAbsolutePath());
    dualWt.setOscAVolume(0.8f);
    dualWt.setWaveIndex(0.15f);
    dualWt.setOsc2Type("WAVETABLE");
    dualWt.setOsc2SamplePath(wtFile.getAbsolutePath());
    dualWt.setOscBVolume(0.75f);
    dualWt.setOsc2WaveIndex(0.85f);
    dualWt.setModFxType("STEREO_CHORUS");
    dualWt.setModFxRate(0.4f);
    dualWt.setModFxDepth(0.5f);

    FirmwareSound checkSound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(dualWt, checkSound);
    assertEquals("CHORUS_STEREO", checkSound.fw2Sound.modFXType.name());

    int[] preSaveDualAudio = renderSynthSamples(dualWt);

    File presetFile = tempDir.resolve("dual_wt.XML").toFile();
    KitSynthSerializer.saveSynth(dualWt, presetFile);
    SynthTrackModel presetReloaded = DelugeXmlParser.parseSynth(presetFile);
    assertEquals(0.15f, presetReloaded.getWaveIndex(), 0.005f);
    assertEquals(0.85f, presetReloaded.getOsc2WaveIndex(), 0.005f);
    assertArrayEquals(preSaveDualAudio, renderSynthSamples(presetReloaded));

    ProjectModel song = new ProjectModel();
    ClipModel clip = new ClipModel("Wt Clip", 192, 0);
    clip.getOrCreateRow(60).notes.add(new NoteModel(0, 48, 0.8f, 1.0f, 1));
    dualWt.addClip(clip);
    song.addTrack(dualWt);
    File songFile = tempDir.resolve("dual_wt_song.XML").toFile();
    Files.writeString(
        songFile.toPath(), ProjectSerializer.serializeToString(song), StandardCharsets.UTF_8);
    ProjectModel reloadedSong = DelugeXmlParser.parseSong(songFile);
    SynthTrackModel songReloadedSynth = (SynthTrackModel) reloadedSong.getTracks().get(0);
    assertEquals(0.15f, songReloadedSynth.getWaveIndex(), 0.005f);
    assertEquals(0.85f, songReloadedSynth.getOsc2WaveIndex(), 0.005f);
    assertArrayEquals(preSaveDualAudio, renderSynthSamples(songReloadedSynth));
  }

  @Test
  void soundDrumPitchAdjustsAndDualOscillatorZonesRoundTripBitExact(@TempDir Path tempDir)
      throws Exception {
    File smpA = tempDir.resolve("drum_layer_a.wav").toFile();
    File smpB = tempDir.resolve("drum_layer_b.wav").toFile();
    writeSingleToneWav(smpA, 220.0);
    writeSingleToneWav(smpB, 330.0);

    KitTrackModel kit = new KitTrackModel("DualZoneKit");
    kit.getDrums().clear();

    SoundDrum layered = new SoundDrum("LAYERED HIT", smpA.getAbsolutePath());
    layered.setOscAVolume(0.85f);
    layered.setStartSamplePos(128);
    layered.setEndSamplePos(8192);
    layered.setStartLoopPos(1024);
    layered.setEndLoopPos(4096);
    layered.setOsc2Type("SAMPLE");
    layered.setOsc2SamplePath(smpB.getAbsolutePath());
    layered.setOscBVolume(0.75f);
    layered.setOsc2StartSamplePos(256);
    layered.setOsc2EndSamplePos(6144);
    layered.setOsc2StartLoopPos(1536);
    layered.setOsc2EndLoopPos(5120);
    layered.setRawDefaultParamQ31("pitchAdjust", 0x08000000);
    layered.setRawDefaultParamQ31("oscAPitchAdjust", 0x04000000);
    layered.setRawDefaultParamQ31("oscBPitchAdjust", 0xFC000000);
    layered.setRawDefaultParamQ31("mod1PitchAdjust", 0x0C000000);
    layered.setRawDefaultParamQ31("mod2PitchAdjust", 0xF8000000);

    SoundDrum companion = new SoundDrum("SUB TONE", "");
    companion.setOsc1Type("SINE");
    companion.setOscAVolume(0.8f);
    companion.setRawDefaultParamQ31("pitchAdjust", 0x0A000000);

    kit.addDrum(layered);
    kit.addDrum(companion);

    FirmwareKit checkKit = new FirmwareKit();
    FirmwareFactory.applyModelToLiveSound(kit, checkKit);
    assertEquals(1024, checkKit.drumSounds.get(0).sampleSettings[0].loopStart);
    assertEquals(4096, checkKit.drumSounds.get(0).sampleSettings[0].loopEnd);
    assertEquals(1536, checkKit.drumSounds.get(0).sampleSettings[1].loopStart);
    assertEquals(5120, checkKit.drumSounds.get(0).sampleSettings[1].loopEnd);
    assertTrue(checkKit.drumSounds.get(0).samples[1] != null, "osc2 sample must load");

    int[] preSaveAudio = renderKitSamples(kit);

    // Non-vacuity ablations: muting osc2 or shifting osc2StartSamplePos must change rendered PCM
    layered.setOscBVolume(0.0f);
    int[] mutedOsc2Audio = renderKitSamples(kit);
    layered.setOscBVolume(0.75f);
    assertFalse(
        Arrays.equals(preSaveAudio, mutedOsc2Audio),
        "osc2 sample layer must audibly contribute to rendered SoundDrum PCM");

    layered.setOsc2StartSamplePos(2048);
    int[] shiftedOsc2ZoneAudio = renderKitSamples(kit);
    layered.setOsc2StartSamplePos(256);
    assertFalse(
        Arrays.equals(preSaveAudio, shiftedOsc2ZoneAudio),
        "osc2 zone startSamplePos must measurably change rendered SoundDrum PCM");

    File kitFile = tempDir.resolve("dual_zone_kit.XML").toFile();
    KitSynthSerializer.saveKit(kit, kitFile);
    KitTrackModel reloadedKit = DelugeXmlParser.parseKit(kitFile);
    SoundDrum d0 = (SoundDrum) reloadedKit.getDrums().get(0);
    assertEquals(128, d0.getStartSamplePos());
    assertEquals(8192, d0.getEndSamplePos());
    assertEquals(1024, d0.getStartLoopPos());
    assertEquals(4096, d0.getEndLoopPos());
    assertEquals(256, d0.getOsc2StartSamplePos());
    assertEquals(6144, d0.getOsc2EndSamplePos());
    assertEquals(1536, d0.getOsc2StartLoopPos());
    assertEquals(5120, d0.getOsc2EndLoopPos());
    assertEquals(0x08000000, d0.getRawDefaultParamQ31("pitchAdjust"));
    assertEquals(0x04000000, d0.getRawDefaultParamQ31("oscAPitchAdjust"));
    assertEquals(0xFC000000, d0.getRawDefaultParamQ31("oscBPitchAdjust"));
    assertEquals(0x0C000000, d0.getRawDefaultParamQ31("mod1PitchAdjust"));
    assertEquals(0xF8000000, d0.getRawDefaultParamQ31("mod2PitchAdjust"));
    assertArrayEquals(preSaveAudio, renderKitSamples(reloadedKit));

    ProjectModel song = new ProjectModel();
    ClipModel clip = new ClipModel("Dual Zone Clip", 192, 0);
    clip.getOrCreateRow(0).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    kit.addClip(clip);
    song.addTrack(kit);
    File songFile = tempDir.resolve("dual_zone_song.XML").toFile();
    Files.writeString(
        songFile.toPath(), ProjectSerializer.serializeToString(song), StandardCharsets.UTF_8);
    ProjectModel reloadedSong = DelugeXmlParser.parseSong(songFile);
    KitTrackModel songReloadedKit = (KitTrackModel) reloadedSong.getTracks().get(0);
    SoundDrum sd0 = (SoundDrum) songReloadedKit.getDrums().get(0);
    assertEquals(128, sd0.getStartSamplePos());
    assertEquals(8192, sd0.getEndSamplePos());
    assertEquals(1024, sd0.getStartLoopPos());
    assertEquals(4096, sd0.getEndLoopPos());
    assertEquals(256, sd0.getOsc2StartSamplePos());
    assertEquals(6144, sd0.getOsc2EndSamplePos());
    assertEquals(1536, sd0.getOsc2StartLoopPos());
    assertEquals(5120, sd0.getOsc2EndLoopPos());
    assertEquals(0x08000000, sd0.getRawDefaultParamQ31("pitchAdjust"));
    assertArrayEquals(preSaveAudio, renderKitSamples(songReloadedKit));

    // Also verify child-element <osc2><zone><startSamplePos>... form and dual-wavetable SoundDrum
    File wtFile = tempDir.resolve("drum_wt.wav").toFile();
    writeFourCycleWavetableWav(wtFile);
    KitTrackModel wtKit = new KitTrackModel("WavetableDrumKit");
    wtKit.getDrums().clear();
    SoundDrum wtDrum = new SoundDrum("WT DRUM", wtFile.getAbsolutePath());
    wtDrum.setOsc1Type("WAVETABLE");
    wtDrum.setOsc2Type("WAVETABLE");
    wtDrum.setOsc2SamplePath(wtFile.getAbsolutePath());
    wtDrum.setOscAVolume(0.85f);
    wtDrum.setOscBVolume(0.75f);
    wtDrum.setWaveIndex(0.20f);
    wtDrum.setOsc2WaveIndex(0.85f);
    SoundDrum wtCompanion = new SoundDrum("WT SUB", "");
    wtCompanion.setOsc1Type("SINE");
    wtCompanion.setOscAVolume(0.5f);
    wtKit.addDrum(wtDrum);
    wtKit.addDrum(wtCompanion);

    int[] wtDrumPreSave = renderKitSamples(wtKit);
    wtDrum.setOsc2WaveIndex(0.20f);
    int[] wtDrumCollapsed = renderKitSamples(wtKit);
    wtDrum.setOsc2WaveIndex(0.85f);
    assertFalse(
        Arrays.equals(wtDrumPreSave, wtDrumCollapsed),
        "Distinct osc2WaveIndex on WAVETABLE SoundDrum must produce distinct audio");

    File wtKitFile = tempDir.resolve("wt_drum_kit.XML").toFile();
    KitSynthSerializer.saveKit(wtKit, wtKitFile);
    KitTrackModel wtKitReloaded = DelugeXmlParser.parseKit(wtKitFile);
    assertArrayEquals(wtDrumPreSave, renderKitSamples(wtKitReloaded));

    ProjectModel wtDrumSong = new ProjectModel();
    ClipModel wtDrumClip = new ClipModel("WT Drum Clip", 192, 0);
    wtDrumClip.getOrCreateRow(0).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    wtKit.addClip(wtDrumClip);
    wtDrumSong.addTrack(wtKit);
    File wtDrumSongFile = tempDir.resolve("wt_drum_song.XML").toFile();
    Files.writeString(
        wtDrumSongFile.toPath(),
        ProjectSerializer.serializeToString(wtDrumSong),
        StandardCharsets.UTF_8);
    ProjectModel wtDrumSongReloaded = DelugeXmlParser.parseSong(wtDrumSongFile);
    assertArrayEquals(
        wtDrumPreSave, renderKitSamples((KitTrackModel) wtDrumSongReloaded.getTracks().get(0)));

    // Verify child-element <osc2><zone><startSamplePos>...</zone></osc2> hardware XML format too.
    File childZoneKitFile = tempDir.resolve("osc2_child_zone_kit.XML").toFile();
    Files.writeString(
        childZoneKitFile.toPath(),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <kit>
          <soundSources>
            <sound>
              <name>CHILD ZONE DRUM</name>
              <osc1 type="saw" />
              <osc2 type="sample">
                <fileName>SAMPLES/LAYER.WAV</fileName>
                <zone>
                  <startSamplePos>128</startSamplePos>
                  <endSamplePos>4096</endSamplePos>
                  <startLoopPos>256</startLoopPos>
                  <endLoopPos>2048</endLoopPos>
                </zone>
              </osc2>
            </sound>
          </soundSources>
        </kit>
        """,
        StandardCharsets.UTF_8);
    SoundDrum parsedChildZone =
        (SoundDrum) DelugeXmlParser.parseKit(childZoneKitFile).getDrums().get(0);
    assertEquals(128, parsedChildZone.getOsc2StartSamplePos());
    assertEquals(4096, parsedChildZone.getOsc2EndSamplePos());
    assertEquals(256, parsedChildZone.getOsc2StartLoopPos());
    assertEquals(2048, parsedChildZone.getOsc2EndLoopPos());
  }

  @Test
  void fastEnvelopeAttackDecayReleaseRoundTripsZeroLsbAcrossPresetAndSong(@TempDir Path tempDir)
      throws Exception {
    SynthTrackModel fastSynth = new SynthTrackModel("FastPluck");
    fastSynth.setOsc1Type("SAW");
    fastSynth.setOscAVolume(0.9f);
    fastSynth.setEnv(0, new EnvelopeModel(0.002f, 0.008f, 0.45f, 0.004f, "NONE", 1.0f));
    fastSynth.setEnv(1, new EnvelopeModel(0.0015f, 0.012f, 0.25f, 0.006f, "NONE", 1.0f));

    int[] preSaveSynthPcm = renderSynthWithNoteOffRelease(fastSynth);

    File synthPreset = tempDir.resolve("fast_pluck.XML").toFile();
    KitSynthSerializer.saveSynth(fastSynth, synthPreset);
    SynthTrackModel reloadedSynthPreset = DelugeXmlParser.parseSynth(synthPreset);
    assertArrayEquals(preSaveSynthPcm, renderSynthWithNoteOffRelease(reloadedSynthPreset));

    KitTrackModel fastKit = new KitTrackModel("FastDrumKit");
    fastKit.getDrums().clear();
    SoundDrum fastDrum = new SoundDrum("FAST SNAP", "");
    fastDrum.setOsc1Type("TRIANGLE");
    fastDrum.setOscAVolume(0.95f);
    fastDrum.setAdsr(new EnvelopeModel(0.002f, 0.008f, 0.35f, 0.004f, "NONE", 1.0f));
    fastDrum.setEnv2(new EnvelopeModel(0.0015f, 0.010f, 0.0f, 0.005f, "NONE", 1.0f));
    SoundDrum fastDrum2 = new SoundDrum("FAST TOM", "");
    fastDrum2.setOsc1Type("SINE");
    fastDrum2.setOscAVolume(0.8f);
    fastDrum2.setAdsr(new EnvelopeModel(0.002f, 0.015f, 0.2f, 0.004f, "NONE", 1.0f));
    fastKit.addDrum(fastDrum);
    fastKit.addDrum(fastDrum2);

    int[] preSaveKitPcm = renderKitSamples(fastKit);
    File kitPreset = tempDir.resolve("fast_drum_kit.XML").toFile();
    KitSynthSerializer.saveKit(fastKit, kitPreset);
    KitTrackModel reloadedKitPreset = DelugeXmlParser.parseKit(kitPreset);
    assertArrayEquals(preSaveKitPcm, renderKitSamples(reloadedKitPreset));

    ProjectModel song = new ProjectModel();
    ClipModel synthClip = new ClipModel("Pluck Clip", 192, 0);
    synthClip.getOrCreateRow(60).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    fastSynth.addClip(synthClip);
    ClipModel kitClip = new ClipModel("Snap Clip", 192, 0);
    kitClip.getOrCreateRow(0).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    fastKit.addClip(kitClip);
    song.addTrack(fastSynth);
    song.addTrack(fastKit);

    File songFile = tempDir.resolve("fast_env_song.XML").toFile();
    Files.writeString(
        songFile.toPath(), ProjectSerializer.serializeToString(song), StandardCharsets.UTF_8);
    ProjectModel reloadedSong = DelugeXmlParser.parseSong(songFile);
    assertArrayEquals(
        preSaveSynthPcm,
        renderSynthWithNoteOffRelease((SynthTrackModel) reloadedSong.getTracks().get(0)));
    assertArrayEquals(
        preSaveKitPcm, renderKitSamples((KitTrackModel) reloadedSong.getTracks().get(1)));
  }

  @Test
  void stutterSidechainMaxVoicesAndStandaloneKitBusRoundTripBitExact(@TempDir Path tempDir)
      throws Exception {
    SynthTrackModel synth = new SynthTrackModel("FullMetaSynth");
    synth.setOsc1Type("SQUARE");
    synth.setOscAVolume(0.85f);
    synth.setMaxVoiceCount(24);
    synth.setSidechainSend(0.65f);
    synth.getStutter().setStutterQuantized(false);
    synth.getStutter().setStutterReversed(true);
    synth.getStutter().setStutterPingPong(true);
    synth.setCompressorAttack(0.25f);
    synth.setCompressorRelease(0.40f);
    synth.setCompressorSyncLevel(5);
    synth.setSidechainAttackRaw(327244);
    synth.setSidechainReleaseRaw(936);
    synth.setSidechainSyncLevel(6);
    synth.setSidechainSyncType(2);

    File synthFile = tempDir.resolve("meta_synth.XML").toFile();
    KitSynthSerializer.saveSynth(synth, synthFile);
    String synthXml = Files.readString(synthFile.toPath(), StandardCharsets.UTF_8);
    assertTrue(synthXml.contains("reverse=\"1\""), "Hardware XML must emit reverse=\"1\"");
    assertFalse(synthXml.contains("reversed=\"1\""), "Must not emit non-firmware reversed=\"1\"");

    SynthTrackModel reloadedSynth = DelugeXmlParser.parseSynth(synthFile);
    assertEquals(24, reloadedSynth.getMaxVoiceCount());
    assertEquals(0.65f, reloadedSynth.getSidechainSend(), 1e-4f);
    assertFalse(reloadedSynth.getStutter().isStutterQuantized());
    assertTrue(reloadedSynth.getStutter().isStutterReversed());
    assertTrue(reloadedSynth.getStutter().isStutterPingPong());
    assertEquals(5, reloadedSynth.getCompressorSyncLevel());
    assertEquals(327244, reloadedSynth.getSidechainAttackRaw());
    assertEquals(936, reloadedSynth.getSidechainReleaseRaw());
    assertEquals(6, reloadedSynth.getSidechainSyncLevel());
    assertEquals(2, reloadedSynth.getSidechainSyncType());

    KitTrackModel kit = new KitTrackModel("PannedBusKit");
    kit.getDrums().clear();
    SoundDrum d0 = new SoundDrum("KICK SC", "");
    d0.setOsc1Type("SINE");
    d0.setOscAVolume(0.9f);
    d0.setMaxVoiceCount(12);
    d0.setSidechainSend(0.75f);
    d0.setStutterQuantized(false);
    d0.setStutterReversed(true);
    d0.setStutterPingPong(true);
    d0.setSidechainAttackRaw(327244);
    d0.setSidechainReleaseRaw(936);
    d0.setSidechainSyncLevel(7);
    d0.setSidechainSyncType(1);
    SoundDrum d1 = new SoundDrum("HAT SC", "");
    d1.setOsc1Type("TRIANGLE");
    d1.setOscAVolume(0.7f);
    kit.addDrum(d0);
    kit.addDrum(d1);

    int[] defaultKitBusPcm = renderKitSamples(kit);
    kit.setVolume(0.82f);
    kit.setPan(-0.35f);
    kit.setReverbAmount(0.42f);
    kit.setBitCrush(0.18f);
    kit.setLpfMode(FilterMode.DRIVE);
    kit.setHpfMode(FilterMode.SVF_NOTCH);
    kit.setFilterRoute(2);
    kit.setModFxType("STEREO_CHORUS");
    int[] customKitBusPcm = renderKitSamples(kit);
    assertFalse(
        Arrays.equals(defaultKitBusPcm, customKitBusPcm),
        "Custom KitTrackModel volume/pan must measurably change rendered PCM");

    File kitFile = tempDir.resolve("meta_kit.XML").toFile();
    KitSynthSerializer.saveKit(kit, kitFile);
    String kitXml = Files.readString(kitFile.toPath(), StandardCharsets.UTF_8);
    assertTrue(kitXml.contains("reverse=\"1\""));
    assertFalse(kitXml.contains("reversed=\"1\""));

    KitTrackModel reloadedKit = DelugeXmlParser.parseKit(kitFile);
    assertEquals(0.82f, reloadedKit.getVolume(), 1e-4f);
    assertEquals(-0.35f, reloadedKit.getPan(), 1e-4f);
    assertEquals(0.42f, reloadedKit.getReverbAmount(), 1e-4f);
    assertEquals(0.18f, reloadedKit.getBitCrush(), 1e-4f);
    assertEquals(FilterMode.DRIVE, reloadedKit.getLpfMode());
    assertEquals(FilterMode.SVF_NOTCH, reloadedKit.getHpfMode());
    assertEquals(2, reloadedKit.getFilterRoute());
    assertEquals("StereoChorus", reloadedKit.getModFxType());
    SoundDrum rd0 = (SoundDrum) reloadedKit.getDrums().get(0);
    assertEquals(12, rd0.getMaxVoiceCount());
    assertFalse(rd0.isStutterQuantized());
    assertTrue(rd0.isStutterReversed());
    assertTrue(rd0.isStutterPingPong());
    assertEquals(327244, rd0.getSidechainAttackRaw());
    assertEquals(936, rd0.getSidechainReleaseRaw());
    assertEquals(7, rd0.getSidechainSyncLevel());
    assertEquals(1, rd0.getSidechainSyncType());
    assertArrayEquals(customKitBusPcm, renderKitSamples(reloadedKit));

    // Also verify full-song ProjectSerializer -> parseSong preserves every single tag & 0-LSB PCM
    ProjectModel song = new ProjectModel();
    ClipModel sClip = new ClipModel("Meta S", 192, 0);
    sClip.getOrCreateRow(60).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    synth.addClip(sClip);
    ClipModel kClip = new ClipModel("Meta K", 192, 0);
    kClip.getOrCreateRow(0).notes.add(new NoteModel(0, 24, 0.8f, 1.0f, 1));
    kit.addClip(kClip);
    song.addTrack(synth);
    song.addTrack(kit);

    String songXml = ProjectSerializer.serializeToString(song);
    assertTrue(songXml.contains("reverse=\"1\""));
    assertFalse(songXml.contains("reversed=\"1\""));
    File songFile = tempDir.resolve("meta_song.XML").toFile();
    Files.writeString(songFile.toPath(), songXml, StandardCharsets.UTF_8);
    ProjectModel reloadedSong = DelugeXmlParser.parseSong(songFile);
    SynthTrackModel songSynth = (SynthTrackModel) reloadedSong.getTracks().get(0);
    KitTrackModel songKit = (KitTrackModel) reloadedSong.getTracks().get(1);
    assertEquals(24, songSynth.getMaxVoiceCount());
    assertEquals(0.65f, songSynth.getSidechainSend(), 1e-4f);
    assertFalse(songSynth.getStutter().isStutterQuantized());
    assertTrue(songSynth.getStutter().isStutterReversed());
    assertTrue(songSynth.getStutter().isStutterPingPong());
    assertEquals(327244, songSynth.getSidechainAttackRaw());
    assertEquals(936, songSynth.getSidechainReleaseRaw());
    assertEquals(6, songSynth.getSidechainSyncLevel());
    assertEquals(2, songSynth.getSidechainSyncType());
    assertEquals(0.82f, songKit.getVolume(), 1e-4f);
    assertEquals(-0.35f, songKit.getPan(), 1e-4f);
    assertArrayEquals(customKitBusPcm, renderKitSamples(songKit));
  }

  private static int[] renderSynthWithNoteOffRelease(SynthTrackModel model) {
    Functions.resetNoiseSeed();
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);
    int totalFrames = BLOCK_SIZE * 16;
    int[] out = new int[totalFrames * 2];
    int[] reverb = new int[BLOCK_SIZE];
    sound.triggerNote(60, 100);
    for (int b = 0; b < 8; b++) {
      int[] block = new int[BLOCK_SIZE * 2];
      sound.renderOutputSumming(block, BLOCK_SIZE, reverb);
      System.arraycopy(block, 0, out, b * BLOCK_SIZE * 2, block.length);
    }
    sound.releaseNote(60);
    for (int b = 8; b < 16; b++) {
      int[] block = new int[BLOCK_SIZE * 2];
      sound.renderOutputSumming(block, BLOCK_SIZE, reverb);
      System.arraycopy(block, 0, out, b * BLOCK_SIZE * 2, block.length);
    }
    return out;
  }

  private static void writeFourCycleWavetableWav(File file) throws Exception {
    int cycleLen = 2048;
    int numCycles = 4;
    short[] pcm = new short[cycleLen * numCycles];
    int[] harmonics = {1, 3, 5, 9};
    for (int c = 0; c < numCycles; c++) {
      int h = harmonics[c];
      for (int i = 0; i < cycleLen; i++) {
        double phase = 2.0 * Math.PI * h * i / cycleLen;
        pcm[c * cycleLen + i] = (short) Math.round(Math.sin(phase) * 24000.0);
      }
    }
    writeMono16BitWav(file, pcm);
  }

  private static void writeSingleToneWav(File file, double freqHz) throws Exception {
    int numSamples = 12000;
    short[] pcm = new short[numSamples];
    for (int i = 0; i < numSamples; i++) {
      double phase = 2.0 * Math.PI * freqHz * i / 44100.0;
      pcm[i] = (short) Math.round(Math.sin(phase) * 22000.0);
    }
    writeMono16BitWav(file, pcm);
  }

  private static void writeMono16BitWav(File file, short[] samples) throws Exception {
    int dataLen = samples.length * 2;
    ByteArrayOutputStream baos = new ByteArrayOutputStream(44 + dataLen);
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    header.putInt(36 + dataLen);
    header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    header.putInt(16);
    header.putShort((short) 1); // PCM
    header.putShort((short) 1); // Mono
    header.putInt(44100);
    header.putInt(44100 * 2);
    header.putShort((short) 2);
    header.putShort((short) 16);
    header.put("data".getBytes(StandardCharsets.US_ASCII));
    header.putInt(dataLen);
    baos.write(header.array());
    ByteBuffer body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN);
    for (short s : samples) {
      body.putShort(s);
    }
    baos.write(body.array());
    Files.write(file.toPath(), baos.toByteArray());
  }

  private static SoundDrum buildBaseDrum(boolean chainMod2ToMod1) {
    SoundDrum d = new SoundDrum("TestDrum", "");
    d.setOsc1Type("SINE");
    d.setOscAVolume(0.95f);
    d.setSynthMode(1);
    d.setModulator1Transpose(7);
    d.setModulator2Transpose(19);
    d.setModulator1AmountQ31(0x48000000);
    d.setModulator2AmountQ31(0x48000000);
    d.setModulator1ToModulator0(chainMod2ToMod1);
    d.setLpfMode(FilterMode.SVF_BAND);
    d.setHpfMode(FilterMode.LADDER_12);
    d.setLpfFreq(1100.0f);
    d.setHpfFreq(350.0f);
    d.setLpfRes(0.4f);
    d.setHpfRes(0.3f);
    d.setDelaySyncLevel(8);
    return d;
  }

  private static int[] renderSingleDrum(SoundDrum drum) {
    KitTrackModel kit = new KitTrackModel("DiffKit");
    kit.getDrums().clear();
    kit.addDrum(drum);
    Functions.resetNoiseSeed();
    FirmwareKit fwKit = new FirmwareKit();
    FirmwareFactory.applyModelToLiveSound(kit, fwKit);
    int totalFrames = BLOCK_SIZE * 24;
    int[] out = new int[totalFrames * 2];
    int[] reverb = new int[BLOCK_SIZE];
    fwKit.triggerDrum(0, 110);
    for (int b = 0; b < 24; b++) {
      int[] block = new int[BLOCK_SIZE * 2];
      fwKit.renderOutputSumming(block, BLOCK_SIZE, reverb);
      System.arraycopy(block, 0, out, b * BLOCK_SIZE * 2, block.length);
    }
    return out;
  }

  private static int[] renderSynthSamples(SynthTrackModel model) {
    Functions.resetNoiseSeed();
    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, sound);
    int totalFrames = BLOCK_SIZE * 16;
    int[] out = new int[totalFrames * 2];
    int[] reverb = new int[BLOCK_SIZE];
    sound.triggerNote(60, 100);
    for (int b = 0; b < 16; b++) {
      int[] block = new int[BLOCK_SIZE * 2];
      sound.renderOutputSumming(block, BLOCK_SIZE, reverb);
      System.arraycopy(block, 0, out, b * BLOCK_SIZE * 2, block.length);
    }
    return out;
  }

  private static int[] renderKitSamples(KitTrackModel kitModel) {
    Functions.resetNoiseSeed();
    FirmwareKit fwKit = new FirmwareKit();
    FirmwareFactory.applyModelToLiveSound(kitModel, fwKit);
    int totalFrames = BLOCK_SIZE * 16;
    int[] out = new int[totalFrames * 2];
    int[] reverb = new int[BLOCK_SIZE];
    fwKit.triggerDrum(0, 100);
    fwKit.triggerDrum(1, 100);
    for (int b = 0; b < 16; b++) {
      int[] block = new int[BLOCK_SIZE * 2];
      fwKit.renderOutputSumming(block, BLOCK_SIZE, reverb);
      System.arraycopy(block, 0, out, b * BLOCK_SIZE * 2, block.length);
    }
    return out;
  }
}
