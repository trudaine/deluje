package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
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
import org.deluge.model.FilterMode;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.LfoType;
import org.deluge.model.PatchCable;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.xml.DelugeXmlExporter;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies lossless XML preset and kit serialization/parsing and live DSP parity across {@link
 * KitSynthSerializer}, {@link DelugeXmlExporter}, {@link org.deluge.xml.InstrumentXmlParser},
 * {@link org.deluge.xml.KitXmlParser}, {@link DelugeatorRandomizer}, and {@link FirmwareFactory}
 * against C++ firmware ({@code mod_controllable_audio.cpp}, {@code filter_config.cpp}, {@code
 * sound.cpp}, {@code functions.cpp}, and {@code patch_cable_set.cpp}).
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
      org.deluge.model.ProjectModel project = new org.deluge.model.ProjectModel();
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
