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
import java.util.List;
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
 * Verifies lossless XML preset and kit serialization/parsing parity across {@link
 * KitSynthSerializer}, {@link DelugeXmlExporter}, {@link org.deluge.xml.InstrumentXmlParser}, and
 * {@link org.deluge.xml.KitXmlParser} against C++ firmware ({@code mod_controllable_audio.cpp},
 * {@code filter_config.cpp}, {@code sound.cpp}, and {@code patch_cable_set.cpp}).
 */
class KitSynthPresetXmlRoundTripParityTest {

  private static final int SAMPLE_RATE = 44100;
  private static final int BLOCK_SIZE = 128;

  @Test
  void synthPresetRoundTripsDelayFilterRouteMorphFmEqLfoWaveFoldAndPatchCables(
      @TempDir Path tempDir) throws Exception {
    SynthTrackModel original = new SynthTrackModel("ParitySynth");
    original.setSynthMode(1); // FM
    original.setFilterMode(FilterMode.SVF_BAND);
    original.setHpfMode(FilterMode.SVF_NOTCH);
    original.setFilterRoute(2); // PARALLEL -> "PARA"
    original.setLpfMorph(0.65f);
    original.setHpfMorph(0.35f);

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

    // All four LFOs (lfo1..lfo4) with distinct Hz rates (verifies no /100.0f corruption)
    original.setLfo(0, new LfoModel(4.5f, LfoType.TRIANGLE, 0.5f, "NONE", true, 0, 0));
    original.setLfo(1, new LfoModel(1.25f, LfoType.SINE, 0.4f, "NONE", false, 0, 0));
    original.setLfo(2, new LfoModel(7.75f, LfoType.SAW, 0.3f, "NONE", true, 5, 1));
    original.setLfo(3, new LfoModel(0.6f, LfoType.SQUARE, 0.2f, "NONE", false, 8, 2));

    // Patch cable with exact Q31 on quadratic-scaled PITCH destination + automation node output
    List<ParamNode> nodes =
        List.of(new ParamNode(0, 0x10000000, true), new ParamNode(48, 0x60000000, false));
    original
        .getModulation()
        .addPatchCable(
            new PatchCable(
                "lfo1", "PITCH", 0.25f, PatchCable.Polarity.BIPOLAR, List.of(), nodes, 0x40000000));

    File presetFile = tempDir.resolve("parity_synth.XML").toFile();
    KitSynthSerializer.saveSynth(original, presetFile);
    String savedXml = Files.readString(presetFile.toPath());
    assertTrue(savedXml.contains("<filterRoute>PARA</filterRoute>"));
    assertTrue(savedXml.contains("<syncType>19</syncType>"));
    assertTrue(savedXml.contains("0x4000000010000000800000006000000000000030"));

    SynthTrackModel reloaded = DelugeXmlParser.parseSynth(presetFile);
    assertEquals(1, reloaded.getSynthMode());
    assertEquals(2, reloaded.getFilterRoute());
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
    assertEquals(4.5f, reloaded.getLfo(0).rateHz(), 0.05f);
    assertEquals(1.25f, reloaded.getLfo(1).rateHz(), 0.05f);
    assertEquals(7.75f, reloaded.getLfo(2).rateHz(), 0.1f);
    assertEquals(0.6f, reloaded.getLfo(3).rateHz(), 0.05f);
    assertEquals(1, reloaded.getLfo(2).syncType());
    assertEquals(2, reloaded.getLfo(3).syncType());
    assertEquals(1, reloaded.getModulation().getPatchCables().size());
    assertEquals(0x40000000, reloaded.getModulation().getPatchCables().get(0).resolveAmountQ31());

    // Also verify DelugeXmlExporter emits filterRoute, lpfMorph, and hpfMorph cleanly
    File exportedFile = tempDir.resolve("exported_synth.XML").toFile();
    DelugeXmlExporter.saveSynthPreset(original, exportedFile);
    SynthTrackModel exportedReloaded = DelugeXmlParser.parseSynth(exportedFile);
    assertEquals(2, exportedReloaded.getFilterRoute());
    assertEquals(0.65f, exportedReloaded.getLpfMorph(), 0.01f);
    assertEquals(0.35f, exportedReloaded.getHpfMorph(), 0.01f);
  }

  @Test
  void kitSoundDrumsRoundTripFmRingmodModulatorsFourLfosDelayFilterRouteMorphEqAndLiveAudio(
      @TempDir Path tempDir) throws Exception {
    KitTrackModel kit = new KitTrackModel("ParityKit");
    kit.getDrums().clear();

    // Drum 0: 2-op chained FM drum with 4 LFOs, dotted delay, L2H filter route, morph, EQ freqs
    SoundDrum fmDrum = new SoundDrum("FM TOM", "");
    fmDrum.setOsc1Type("SINE");
    fmDrum.setOscAVolume(0.9f);
    fmDrum.setSynthMode(1); // "fm"
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
    fmDrum.setLfo2(new LfoModel(2.0f, LfoType.TRIANGLE, 0.4f, "NONE", false, 0, 0));
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
    ringDrum.setDelayPingPong(1);
    ringDrum.setDelayAnalog(0);
    ringDrum.setDelaySyncLevel(8);
    ringDrum.setDelaySyncType(2); // DOTTED -> file 19

    kit.addDrum(fmDrum);
    kit.addDrum(ringDrum);

    File kitFile = tempDir.resolve("parity_kit.XML").toFile();
    KitSynthSerializer.saveKit(kit, kitFile);
    KitTrackModel reloadedKit = DelugeXmlParser.parseKit(kitFile);
    assertEquals(2, reloadedKit.getDrums().size());

    SoundDrum d0 = (SoundDrum) reloadedKit.getDrums().get(0);
    assertEquals(1, d0.getSynthMode());
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

    SoundDrum d1 = (SoundDrum) reloadedKit.getDrums().get(1);
    assertEquals(2, d1.getSynthMode());
    assertEquals(2, d1.getFilterRoute());
    assertEquals(1, d1.getDelayPingPong());
    assertEquals(0, d1.getDelayAnalog());
    assertEquals(8, d1.getDelaySyncLevel());
    assertEquals(2, d1.getDelaySyncType());

    // Verify rendered DSP audio across a second round-trip is 100% bit-identical!
    File secondKitFile = tempDir.resolve("parity_kit_second.XML").toFile();
    KitSynthSerializer.saveKit(reloadedKit, secondKitFile);
    KitTrackModel reloadedTwice = DelugeXmlParser.parseKit(secondKitFile);
    assertArrayEquals(renderKitSamples(reloadedKit), renderKitSamples(reloadedTwice));
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
