package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.engine.DelugeatorRandomizer;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.project.ProjectSerializer;
import org.deluge.shadow.audio.Dx7Patch;
import org.deluge.ui.AlgorithmPanel;
import org.deluge.ui.Dx7Panel;
import org.deluge.xml.DelugeXmlExporter;
import org.deluge.xml.DelugeXmlParser;
import org.deluge.xml.InstrumentXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies C++ firmware parity for DX7 shared {@link Dx7Voice.DxPatch}, once-per-block patch LFO
 * stepping ({@code sound.cpp:2423-2427}), live mid-note parameter updates ({@code
 * voice.cpp:2424-2426}, {@code sound.cpp:2636-2638}), engine mode alignment ({@code 0 = auto},
 * {@code 1 = modern}, {@code 2 = vintage}, {@code dx7note.cpp:63, 66-79}), 6-op routing when UI or
 * randomizer sets {@code synthMode == 1} ({@code sound.cpp:3744-3745}), {@link
 * DelugeatorRandomizer} DX7 generation, and XML round-trips across all serializers.
 */
class Dx7SharedPatchLfoAndRoutingParityTest {

  private static FirmwareSound compileSynth(SynthTrackModel model) {
    FirmwareSound fs = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(model, fs);
    FirmwareFactory.loadOscResources(model, fs);
    return fs;
  }

  private static byte[] createSingleCarrierTestPatch(int coarseRatio) {
    byte[] raw = Dx7Patch.createInitVoiceBytes();
    raw[Dx7Patch.OFF_ALGORITHM] = 31; // Algorithm 32: all 6 slots are independent carriers
    raw[Dx7Patch.OFF_FEEDBACK] = 0;
    raw[Dx7Patch.OFF_OP_SWITCH] = (byte) (1 << 5); // Enable OP1 (slot 5) only
    int op1Off = 5 * 21;
    raw[op1Off + 0] = 99; // Instant attack
    raw[op1Off + 1] = 99;
    raw[op1Off + 2] = 99;
    raw[op1Off + 3] = 99;
    raw[op1Off + 4] = 99;
    raw[op1Off + 5] = 99;
    raw[op1Off + 6] = 99;
    raw[op1Off + 7] = 0;
    raw[op1Off + 16] = 99; // Full output level
    raw[op1Off + 18] = (byte) coarseRatio;
    raw[137] = 50; // LFO speed = 50
    raw[141] = 0; // LFO key sync = off so poly voices do not reset LFO phase
    return raw;
  }

  private static int countPositiveZeroCrossings(int[] interleavedStereo, int startSample, int end) {
    int crossings = 0;
    for (int i = startSample + 1; i < end; i++) {
      if (interleavedStereo[(i - 1) * 2] <= 0 && interleavedStereo[i * 2] > 0) {
        crossings++;
      }
    }
    return crossings;
  }

  @Test
  void testSharedDxPatchAdvancesLfoOncePerBlockAcrossPolyVoicesAndUnison() {
    Functions.resetNoiseSeed();
    byte[] rawPatch = createSingleCarrierTestPatch(1);

    Sound sound = new Sound();
    sound.oscTypes[0] = Oscillator.OscType.DX7;
    sound.sourceDx7Patch[0] = rawPatch;
    sound.numUnison = 4;

    Dx7Voice.DxPatch refPatch = new Dx7Voice.DxPatch();
    System.arraycopy(rawPatch, 0, refPatch.params, 0, 156);
    refPatch.setEngineMode(0);
    int initialLfoPhase = sound.ensureDxPatch(0).lfoPhase;
    refPatch.lfoPhase = initialLfoPhase;

    // Trigger 3 simultaneous polyphonic voices x 4 unison parts = 12 active Dx7Voice instances
    sound.triggerNote(60, 100, 0);
    sound.triggerNote(64, 100, 0);
    sound.triggerNote(67, 100, 0);
    assertEquals(3, sound.voices.size());

    int[] buf = new int[128 * 2];
    sound.renderInternal(buf, 128, null);
    refPatch.computeLfo(128);

    // Every voice & unison part shares the single per-source DxPatch instance and advances 1x
    assertEquals(
        refPatch.lfoPhase,
        sound.sourceDxPatches[0].lfoPhase,
        "Shared DX7 source LFO must advance exactly once per render block regardless of poly/unison");
    for (Voice v : sound.voices) {
      for (int u = 0; u < sound.numUnison; u++) {
        assertSame(
            sound.sourceDxPatches[0].params,
            v.unisonParts[u].sources[0].dxVoice.patch,
            "All voices and unison parts must share sound.sourceDxPatches[0].params");
      }
    }
  }

  @Test
  void testLiveDxPatchEditCallsUpdateMidNoteWithoutRetriggerAndClearsFlag() {
    Functions.resetNoiseSeed();
    SynthTrackModel model = new SynthTrackModel("DX7 Live Edit");
    model.setOsc1Type("DX7");
    model.setOscMix(1.0f);
    byte[] patchRatio1 = createSingleCarrierTestPatch(1);
    model.setDx7Patch(Dx7Patch.bytesToHex(patchRatio1));

    FirmwareSound fs = compileSynth(model);
    fs.triggerNote(60, 100, 0);

    int totalSamples = 4096;
    int[] beforeEdit = new int[totalSamples * 2];
    long[] outBlock = new long[128 * 2];
    long[] revBlock = new long[128];
    for (int pos = 0; pos < totalSamples; pos += 128) {
      java.util.Arrays.fill(outBlock, 0L);
      java.util.Arrays.fill(revBlock, 0L);
      fs.renderOutput(outBlock, 128, revBlock);
      for (int i = 0; i < 128 * 2; i++) {
        beforeEdit[pos * 2 + i] = (int) outBlock[i];
      }
    }
    int crossingsRatio1 = countPositiveZeroCrossings(beforeEdit, 512, totalSamples);
    assertTrue(crossingsRatio1 > 15, "Ratio 1 note must produce clear oscillation");

    // Live edit mid-note: double OP1 coarse ratio from 1 -> 2 (+1 octave) without NoteOff/NoteOn —
    // matching PureFirmwareEngine's live per-block syncDx7FromModel -> renderOutput path!
    byte[] patchRatio2 = createSingleCarrierTestPatch(2);
    model.setDx7Patch(Dx7Patch.bytesToHex(patchRatio2));
    fs.syncDx7FromModel(model);

    int[] afterEdit = new int[totalSamples * 2];
    for (int pos = 0; pos < totalSamples; pos += 128) {
      java.util.Arrays.fill(outBlock, 0L);
      java.util.Arrays.fill(revBlock, 0L);
      fs.renderOutput(outBlock, 128, revBlock);
      assertFalse(
          fs.fw2Sound.sourceDxPatchChanged[0],
          "sourceDxPatchChanged[0] must clear at end of Sound.renderInternal (sound.cpp:2637)");
      for (int i = 0; i < 128 * 2; i++) {
        afterEdit[pos * 2 + i] = (int) outBlock[i];
      }
    }
    int crossingsRatio2 = countPositiveZeroCrossings(afterEdit, 512, totalSamples);
    double freqRatio = (double) crossingsRatio2 / crossingsRatio1;
    assertEquals(
        2.0,
        freqRatio,
        0.12,
        "Live mid-note DX7 ratio edit must double held voice frequency via Dx7Voice.update");

    // Silent block must also clear any pending sourceDxPatchChanged flag
    fs.releaseAllNotes();
    fs.fw2Sound.voices.clear();
    fs.fw2Sound.sourceDxPatchChanged[0] = true;
    fs.fw2Sound.renderInternal(new int[64 * 2], 64, null);
    assertFalse(fs.fw2Sound.sourceDxPatchChanged[0]);
  }

  @Test
  void testEngineModeAlignmentZeroAutoOneModernTwoVintage() throws Exception {
    SynthTrackModel fresh = new SynthTrackModel("DX7 Engine Mode");
    assertEquals(0, fresh.getEngineType(), "Default DX7 engine mode must be 0 = auto");

    // Legacy -1 ("Auto" in earlier Deluje saves) must clamp cleanly to 0 = auto, not Vintage!
    fresh.setEngineType(-1);
    assertEquals(0, fresh.getEngineType());

    Dx7Voice.DxPatch patch = new Dx7Voice.DxPatch();
    // Algo 3 (DX7 Algo 4, 3-op feedback loop) with feedback > 0: auto (0) selects MkI, modern (1)
    // forces FmCore, vintage (2) selects MkI (dx7note.cpp:66-79).
    patch.params[134] = 3;
    patch.params[135] = 6;
    patch.setEngineMode(0);
    assertTrue(patch.useMkI, "engineMode 0 (auto) must enable MkI on Algo 4 with feedback > 0");
    patch.setEngineMode(1);
    assertFalse(patch.useMkI, "engineMode 1 (modern) must select FmCore");
    patch.setEngineMode(2);
    assertTrue(patch.useMkI, "engineMode 2 (vintage) must select EngineMkI");

    // Algo 0 (DX7 Algo 1): auto (0) selects modern FmCore; vintage (2) forces EngineMkI
    patch.params[134] = 0;
    patch.params[135] = 7;
    patch.setEngineMode(0);
    assertFalse(patch.useMkI, "engineMode 0 (auto) must select modern FmCore on Algo 1");
    patch.setEngineMode(2);
    assertTrue(patch.useMkI, "engineMode 2 (vintage) must select EngineMkI on Algo 1");

    SynthTrackModel uiModel = new SynthTrackModel("DX7 UI Engine Mode");
    uiModel.setSynthMode(1);
    uiModel.setOsc1Type("DX7");
    uiModel.setOscMix(1.0f);
    uiModel.setDx7Patch(Dx7Patch.bytesToHex(createSingleCarrierTestPatch(1)));
    uiModel.setEngineType(2);
    SwingUtilities.invokeAndWait(
        () -> {
          AlgorithmPanel panel = new AlgorithmPanel(uiModel, new BridgeContract(), 0);
          JComboBox<?> engineTypeCombo = findEngineTypeCombo(panel);
          assertNotNull(engineTypeCombo);
          assertEquals(2, engineTypeCombo.getSelectedIndex());
          engineTypeCombo.setSelectedIndex(0);
          assertEquals(0, uiModel.getEngineType());
          engineTypeCombo.setSelectedIndex(1);
          assertEquals(1, uiModel.getEngineType());

          // Clicking card 7's Select button must update byte 134 (OFF_ALGORITHM) in dx7Patch!
          JButton selectCard7 = findAlgorithmSelectButton(panel, 7);
          assertNotNull(selectCard7, "Card 7 Select button must exist in AlgorithmPanel");
          selectCard7.doClick();
          assertEquals(7, uiModel.getSynthAlgorithm());
          byte[] updatedRaw = Dx7Patch.hexToBytes(uiModel.getDx7Patch());
          assertEquals(
              7,
              updatedRaw[Dx7Patch.OFF_ALGORITHM] & 0xFF,
              "AlgorithmPanel card Select button must write OFF_ALGORITHM into dx7Patch");
        });
  }

  @Test
  void testSynthModeOneWithDx7PatchRoutesThroughSixOpDx7AndDx7PanelOpensForFirmwareSongs()
      throws Exception {
    byte[] epPatch = createSingleCarrierTestPatch(1);
    // Add strong OP2 modulator into OP1 on Algorithm 0 (6-op FM stack) while leaving 2-op
    // modulator1AmountQ31 at Integer.MIN_VALUE (0.0f) so 2-op sine fallback would be pure sine!
    epPatch[Dx7Patch.OFF_ALGORITHM] = 0;
    epPatch[Dx7Patch.OFF_FEEDBACK] = 6;
    epPatch[Dx7Patch.OFF_OP_SWITCH] = 0x3F;
    int op2Off = 4 * 21;
    epPatch[op2Off + 0] = 99;
    epPatch[op2Off + 4] = 99;
    epPatch[op2Off + 16] = 95;
    epPatch[op2Off + 18] = 7; // Inharmonic 7:1 FM modulator!
    String hex = Dx7Patch.bytesToHex(epPatch);

    SynthTrackModel mode0Model = new SynthTrackModel("DX7 Subtractive Mode");
    mode0Model.setSynthMode(0);
    mode0Model.setOsc1Type("DX7");
    mode0Model.setOscMix(1.0f);
    mode0Model.setDx7Patch(hex);

    SynthTrackModel mode1Model = new SynthTrackModel("DX7 Activated UI Mode");
    mode1Model.setSynthMode(1); // Set by Dx7Panel Activate button & DelugeatorRandomizer
    mode1Model.setOsc1Type("DX7");
    mode1Model.setOscMix(1.0f);
    mode1Model.setDx7Patch(hex);

    Functions.resetNoiseSeed();
    FirmwareSound s0 = compileSynth(mode0Model);
    s0.triggerNote(60, 100, 0);
    int[] buf0 = new int[512 * 2];
    s0.fw2Sound.renderInternal(buf0, 512, null);

    Functions.resetNoiseSeed();
    FirmwareSound s1 = compileSynth(mode1Model);
    s1.triggerNote(60, 100, 0);
    int[] buf1 = new int[512 * 2];
    s1.fw2Sound.renderInternal(buf1, 512, null);

    assertArrayEquals(
        buf0, buf1, "synthMode == 1 with DX7 patch must route identically through 6-op Dx7Voice");

    // Verify Dx7Panel opens active editor (no INACTIVE prompt card) on firmware Dx7C.xml song
    try (InputStream in = getClass().getResourceAsStream("/SONGS/Dx7C.xml")) {
      assertNotNull(in);
      ProjectModel song = DelugeXmlParser.parseSong(in, "Dx7C");
      SynthTrackModel dx7Track =
          song.getTracks().stream()
              .filter(SynthTrackModel.class::isInstance)
              .map(SynthTrackModel.class::cast)
              .filter(t -> t.getDx7Patch() != null && !t.getDx7Patch().isEmpty())
              .findFirst()
              .orElseThrow();
      assertEquals(0, dx7Track.getSynthMode());
      SwingUtilities.invokeAndWait(
          () -> {
            Dx7Panel panel = new Dx7Panel(dx7Track, new BridgeContract(), 0, null, null);
            assertNull(
                findActivateButton(panel),
                "Dx7Panel must open active DX7 editor directly for firmware DX7 songs");
          });
    }
  }

  @Test
  void testBlankDx7InitVoiceNoteOffUnisonIncreaseAndModeSwitchingSafety() throws Exception {
    Functions.resetNoiseSeed();
    // 1. Blank DX7 synth (oscTypes[0] == DX7, sourceDx7Patch[0] == null, e.g. setupAsBlankSynth)
    //    must initialize from INIT_VOICE, render audibly, and never NPE on NoteOff or mid-note
    //    unison expansion.
    Sound blankDx = new Sound();
    blankDx.oscTypes[0] = Oscillator.OscType.DX7;
    blankDx.sourceDx7Patch[0] = null;
    blankDx.numUnison = 1;
    blankDx.triggerNote(60, 100, 0);

    int[] blankBuf = new int[256 * 2];
    blankDx.renderInternal(blankBuf, 256, null);
    long blankEnergy = 0;
    for (int sample : blankBuf) blankEnergy += Math.abs((long) sample);
    assertTrue(blankEnergy > 1_000_000L, "Blank DX7 synth with null patch must render INIT_VOICE");

    // Expand numUnison mid-note and call releaseNote BEFORE next renderInternal block — must NOT
    // NPE
    blankDx.numUnison = 4;
    blankDx.setupUnisonDetuners();
    assertDoesNotThrow(() -> blankDx.releaseNote(60));
    assertDoesNotThrow(() -> blankDx.renderInternal(new int[128 * 2], 128, null));

    // 2. Switching Osc 1 away from DX7 or re-randomizing into Subtractive/RingMod must exit DX7
    DelugeatorRandomizer.RandomizerSettings subSettings =
        new DelugeatorRandomizer.RandomizerSettings();
    subSettings.generalChecked = false;
    subSettings.osc1Checked = true;
    ProjectModel project = new ProjectModel();

    SynthTrackModel track = new SynthTrackModel("Switchable Track");
    track.setSynthMode(1);
    DelugeatorRandomizer.randomizeSynth(track, project, subSettings);
    assertNotNull(track.getDx7Patch());
    FirmwareSound liveFs = compileSynth(track);
    liveFs.syncDx7FromModel(track);
    liveFs.renderOutput(new long[256], 128, new long[128]);
    assertEquals(Oscillator.OscType.DX7, liveFs.fw2Sound.oscTypes[0]);

    // Re-randomize into Subtractive (mode 0) -> clears dx7Patch and restores non-DX7 waveform!
    track.setSynthMode(0);
    DelugeatorRandomizer.randomizeSynth(track, project, subSettings);
    assertNull(track.getDx7Patch(), "Re-randomizing into Subtractive must clear stale dx7Patch");
    liveFs.syncDx7FromModel(track);
    liveFs.renderOutput(new long[256], 128, new long[128]);
    assertNull(liveFs.fw2Sound.sourceDx7Patch[0]);
    assertNotEquals(Oscillator.OscType.DX7, liveFs.fw2Sound.oscTypes[0]);

    // 3. Direct applyDx7Patch (.SYX load path) sets osc1Type="DX7" & silences Osc 2 bleed
    SynthTrackModel syxModel = new SynthTrackModel("Syx Import");
    java.lang.reflect.Method applyPatchMethod =
        Dx7Panel.class.getDeclaredMethod(
            "applyDx7Patch",
            SynthTrackModel.class,
            BridgeContract.class,
            int.class,
            Dx7Patch.class);
    applyPatchMethod.setAccessible(true);
    applyPatchMethod.invoke(
        null,
        syxModel,
        new BridgeContract(),
        0,
        Dx7Patch.fromHex(Dx7Patch.bytesToHex(createSingleCarrierTestPatch(1))));
    assertEquals("DX7", syxModel.getOsc1Type());
    assertEquals(
        0.0f, syxModel.getOscBVolume(), 1e-6f, ".SYX load must silence Osc 2 triangle bleed");
  }

  @Test
  void testDelugeatorRandomizerGeneratesValidAudibleConcertPitchDx7PatchesEveryTime() {
    Functions.resetNoiseSeed();
    DelugeatorRandomizer.RandomizerSettings settings =
        new DelugeatorRandomizer.RandomizerSettings();
    settings.generalChecked = false; // Lock synthMode == 1 (FM/DX7)
    settings.osc1Checked = true;
    settings.osc1Amount = 0.7;
    ProjectModel project = new ProjectModel();

    for (int iter = 0; iter < 40; iter++) {
      SynthTrackModel model = new SynthTrackModel("Random DX7 #" + iter);
      model.setSynthMode(1);
      DelugeatorRandomizer.randomizeSynth(model, project, settings);

      assertEquals("DX7", model.getOsc1Type());
      assertTrue(
          model.getEngineType() == 1 || model.getEngineType() == 2,
          "Randomized DX7 engine mode must be 1 (modern) or 2 (vintage)");

      byte[] syx = Dx7Patch.hexToBytes(model.getDx7Patch());
      int algo = syx[Dx7Patch.OFF_ALGORITHM] & 0xFF;
      assertTrue(algo >= 0 && algo <= 31, "DX7 algorithm index must be 0..31, got " + algo);
      assertEquals(24, syx[144] & 0xFF, "Randomized DX7 patch must preserve concert transpose 24");
      assertEquals(50, syx[130] & 0xFF, "Randomized DX7 patch must preserve neutral Pitch EG L1");

      int opSwitch = syx[Dx7Patch.OFF_OP_SWITCH] & 0x3F;
      for (int op = 0; op < 6; op++) {
        if ((FmCore.ALGORITHMS[algo][op] & FmCore.OUT_BUS_ADD) != 0) {
          assertTrue(
              ((opSwitch >> op) & 1) != 0,
              "Carrier slot " + op + " in algo " + algo + " must be enabled in opSwitch");
          assertTrue(
              (syx[op * 21 + 16] & 0xFF) >= 85,
              "Carrier slot " + op + " output level at +16 must be >= 85");
          int carrierCoarse = syx[op * 21 + 18] & 0xFF;
          assertTrue(
              carrierCoarse == 1 || carrierCoarse == 2,
              "Carrier slot " + op + " coarse ratio at +18 must be octave-harmonic (1 or 2)");
          assertEquals(
              0,
              syx[op * 21 + 19] & 0xFF,
              "Carrier slot " + op + " fine ratio at +19 must be 0 so concert pitch holds");
        }
      }

      FirmwareSound compiled = compileSynth(model);
      compiled.triggerNote(60, 110, 0);
      long absEnergy = 0;
      for (int b = 0; b < 8; b++) {
        int[] block = new int[128 * 2];
        compiled.fw2Sound.renderInternal(block, 128, null);
        for (int sample : block) {
          absEnergy += Math.abs((long) sample);
        }
      }
      assertTrue(
          absEnergy > 1_000_000L,
          "Randomized DX7 patch #" + iter + " (algo=" + algo + ") must render audible audio");
    }
  }

  @Test
  void testXmlRoundTripPreservesDx7EngineModeRandomDetuneAndHardwareSubtractiveMode(
      @TempDir Path tempDir) throws Exception {
    String patchHex = Dx7Patch.bytesToHex(createSingleCarrierTestPatch(1));

    // 1. Non-zero engineMode=2 (vintage) & randomDetune=37 with UI synthMode=1 -> serializes
    //    hardware-compatible <mode>subtractive</mode> + dx7patch + dx7enginemode="2" +
    //    dx7randomdetune="37" across ProjectSerializer, KitSynthSerializer, and DelugeXmlExporter!
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("Vintage EP");
    synth.setSynthMode(1);
    synth.setOsc1Type("DX7");
    synth.setDx7Patch(patchHex);
    synth.setEngineType(2);
    synth.setDx7RandomDetune(37);
    project.getTracks().add(synth);

    File songFile = tempDir.resolve("dx7_roundtrip_song.xml").toFile();
    ProjectSerializer.save(project, songFile);
    String songXml = Files.readString(songFile.toPath(), StandardCharsets.UTF_8);
    assertTrue(songXml.contains("<mode>subtractive</mode>"));
    assertTrue(songXml.contains("dx7enginemode=\"2\""));
    assertTrue(songXml.contains("dx7randomdetune=\"37\""));

    try (InputStream in = Files.newInputStream(songFile.toPath())) {
      ProjectModel reloaded = DelugeXmlParser.parseSong(in, "dx7_roundtrip_song");
      SynthTrackModel reloadedSynth = (SynthTrackModel) reloaded.getTracks().get(0);
      assertEquals(0, reloadedSynth.getSynthMode());
      assertEquals("DX7", reloadedSynth.getOsc1Type());
      assertEquals(patchHex, reloadedSynth.getDx7Patch());
      assertEquals(2, reloadedSynth.getEngineType());
      assertEquals(37, reloadedSynth.getDx7RandomDetune());
    }

    File presetFile = tempDir.resolve("dx7_preset.xml").toFile();
    KitSynthSerializer.saveSynth(synth, presetFile);
    String presetXml = Files.readString(presetFile.toPath(), StandardCharsets.UTF_8);
    assertTrue(presetXml.contains("<mode>subtractive</mode>"));
    SynthTrackModel loadedPreset = InstrumentXmlParser.parseSynth(presetFile);
    assertEquals(2, loadedPreset.getEngineType());
    assertEquals(37, loadedPreset.getDx7RandomDetune());

    File exportedFile = tempDir.resolve("dx7_exported.xml").toFile();
    DelugeXmlExporter.saveSynthPreset(synth, exportedFile);
    SynthTrackModel loadedExport = InstrumentXmlParser.parseSynth(exportedFile);
    assertEquals(0, loadedExport.getSynthMode());
    assertEquals(patchHex, loadedExport.getDx7Patch());
    assertEquals(2, loadedExport.getEngineType());
    assertEquals(37, loadedExport.getDx7RandomDetune());

    // 2. Zero engineMode=0 & randomDetune=0 omits optional attributes per sound.cpp:3750-3757, and
    //    child-tag form (<dx7enginemode> / <dx7randomdetune>) parses cleanly per
    // sound.cpp:3398-3412
    synth.setEngineType(0);
    synth.setDx7RandomDetune(0);
    File autoPresetFile = tempDir.resolve("dx7_auto_preset.xml").toFile();
    KitSynthSerializer.saveSynth(synth, autoPresetFile);
    String autoXml = Files.readString(autoPresetFile.toPath(), StandardCharsets.UTF_8);
    assertFalse(autoXml.contains("dx7enginemode"));
    assertFalse(autoXml.contains("dx7randomdetune"));

    String childTagXml =
        "<sound><osc1><type>dx7</type><dx7patch>"
            + patchHex
            + "</dx7patch><dx7enginemode>2</dx7enginemode>"
            + "<dx7randomdetune>19</dx7randomdetune></osc1></sound>";
    SynthTrackModel fromChildTags =
        InstrumentXmlParser.parseSynth(
            new ByteArrayInputStream(childTagXml.getBytes(StandardCharsets.UTF_8)), "child_dx7");
    assertEquals(2, fromChildTags.getEngineType());
    assertEquals(19, fromChildTags.getDx7RandomDetune());
  }

  @Test
  void liveSyncPolyphonicUpdateBlankUiAndDoneReadingFromFileParity(@TempDir Path tempDir)
      throws Exception {
    // 1. Live FirmwareSound sync across full renderOutput blocks:
    //    - DX7 -> Subtractive SAW must NOT revert oscTypes[0] back to DX7 on renderOutput
    //    - SAW -> blank DX7 (INIT_VOICE) must stay OscType.DX7 on renderOutput
    //    - Held polyphonic chord updates ALL voices cleanly in the same block
    SynthTrackModel liveTrack = new SynthTrackModel("LiveSyncTrack");
    BridgeContract bridge = new BridgeContract();
    Dx7Panel initPanel = new Dx7Panel(liveTrack, bridge, 0, null, () -> {});
    findActivateButton(initPanel).doClick();
    FirmwareSound liveFs = compileSynth(liveTrack);

    liveFs.triggerNote(60, 100);
    liveFs.triggerNote(67, 100);
    long[] stereoBuf = new long[256];
    long[] revBuf = new long[128];
    liveFs.renderOutput(stereoBuf, 128, revBuf);
    assertEquals(2, liveFs.fw2Sound.voices.size());

    // Live mid-chord coarse ratio edit from ratio 1 -> ratio 4 updates BOTH held chord voices
    byte[] ratio4Raw = Dx7Patch.hexToBytes(liveTrack.getDx7Patch());
    for (int op = 0; op < 6; op++) {
      ratio4Raw[op * 21 + 18] = 4;
    }
    liveTrack.setDx7Patch(Dx7Patch.bytesToHex(ratio4Raw));
    liveFs.syncDx7FromModel(liveTrack);
    int v0PitchBefore =
        liveFs.fw2Sound.voices.get(0).unisonParts[0].sources[0].dxVoice.basePitch[5];
    int v1PitchBefore =
        liveFs.fw2Sound.voices.get(1).unisonParts[0].sources[0].dxVoice.basePitch[5];
    liveFs.renderOutput(stereoBuf, 128, revBuf);
    assertTrue(
        liveFs.fw2Sound.voices.get(0).unisonParts[0].sources[0].dxVoice.basePitch[5]
            > v0PitchBefore);
    assertTrue(
        liveFs.fw2Sound.voices.get(1).unisonParts[0].sources[0].dxVoice.basePitch[5]
            > v1PitchBefore);
    liveFs.releaseAllNotes();

    // Exit DX7 to Subtractive SAW via DelugeatorRandomizer with osc1Checked = false
    DelugeatorRandomizer.RandomizerSettings generalOnly =
        new DelugeatorRandomizer.RandomizerSettings();
    generalOnly.osc1Checked = false;
    generalOnly.osc2Checked = false;
    ProjectModel proj = new ProjectModel();
    proj.addTrack(liveTrack);
    for (int attempt = 0; attempt < 200 && liveTrack.getSynthMode() == 1; attempt++) {
      DelugeatorRandomizer.randomizeSynth(liveTrack, proj, generalOnly);
    }
    liveTrack.setSynthMode(0);
    liveFs.syncDx7FromModel(liveTrack);
    liveFs.triggerNote(60, 100);
    liveFs.renderOutput(stereoBuf, 128, revBuf);
    assertNotEquals(Oscillator.OscType.DX7, liveFs.fw2Sound.oscTypes[0]);
    liveFs.releaseAllNotes();

    // Re-enter blank DX7 (INIT_VOICE, dx7Patch == null) and verify renderOutput keeps OscType.DX7
    liveTrack.setOsc1Type("DX7");
    liveTrack.setDx7Patch(null);
    liveFs.syncDx7FromModel(liveTrack);
    liveFs.triggerNote(60, 100);
    liveFs.renderOutput(stereoBuf, 128, revBuf);
    assertEquals(Oscillator.OscType.DX7, liveFs.fw2Sound.oscTypes[0]);

    // 2. Opening Dx7Panel on a blank FM track (synthMode == 1, dx7Patch == null) has live sliders
    SynthTrackModel blankFm = new SynthTrackModel("BlankFm");
    blankFm.setSynthMode(1);
    assertNull(blankFm.getDx7Patch());
    BridgeContract blankBridge = new BridgeContract();
    Dx7Panel blankDxPanel = new Dx7Panel(blankFm, blankBridge, 0, null, () -> {});
    javax.swing.JSlider fbSlider = findSliderWithMax(blankDxPanel, 7);
    assertNotNull(fbSlider, "Feedback slider (0..7) should be present on active Dx7Panel");
    fbSlider.setValue(5);
    assertNotNull(
        blankFm.getDx7Patch(), "Moving slider on blank FM Dx7Panel must materialize patch");
    assertEquals(312, blankFm.getDx7Patch().length());
    assertEquals("DX7", blankFm.getOsc1Type());
    assertEquals(5, Dx7Patch.hexToBytes(blankBridge.getDx7Patch(0))[Dx7Patch.OFF_FEEDBACK] & 0xFF);

    // 3. Hardware Source::doneReadingFromFile (source.cpp:200-209) + presetName inheritance &
    //    inline override parity
    Path synthsDir = tempDir.resolve("SYNTHS");
    Files.createDirectories(synthsDir);
    File presetRef = synthsDir.resolve("PRESET_DX7.XML").toFile();
    liveTrack.setDx7Patch(Dx7Patch.bytesToHex(ratio4Raw));
    liveTrack.setEngineType(2);
    liveTrack.setDx7RandomDetune(17);
    KitSynthSerializer.saveSynth(liveTrack, presetRef);

    String prevCard = System.getProperty("deluge.card");
    try {
      System.setProperty("deluge.card", tempDir.toString());

      // <sound presetName="PRESET_DX7"> with NO inline <osc1> must preserve inherited DX7 state!
      SynthTrackModel inherited = new SynthTrackModel("Inherited");
      org.w3c.dom.Element presetOnlyOverridesLpf =
          javax.xml.parsers.DocumentBuilderFactory.newInstance()
              .newDocumentBuilder()
              .parse(
                  new ByteArrayInputStream(
                      "<sound presetName=\"PRESET_DX7\"><defaultParams lpfFrequency=\"0x20000000\"/></sound>"
                          .getBytes(StandardCharsets.UTF_8)))
              .getDocumentElement();
      InstrumentXmlParser.populateSynth(presetOnlyOverridesLpf, inherited, false);
      assertEquals(Dx7Patch.bytesToHex(ratio4Raw), inherited.getDx7Patch());
      assertEquals(2, inherited.getEngineType());
      assertEquals(17, inherited.getDx7RandomDetune());
      assertEquals("DX7", inherited.getOsc1Type());

      // <sound presetName="PRESET_DX7"><osc1 type="saw"/></sound> must clear all 3 DX7 fields!
      SynthTrackModel overridden = new SynthTrackModel("Overridden");
      org.w3c.dom.Element inlineSaw =
          javax.xml.parsers.DocumentBuilderFactory.newInstance()
              .newDocumentBuilder()
              .parse(
                  new ByteArrayInputStream(
                      "<sound presetName=\"PRESET_DX7\"><mode>subtractive</mode><osc1 type=\"saw\"/></sound>"
                          .getBytes(StandardCharsets.UTF_8)))
              .getDocumentElement();
      InstrumentXmlParser.populateSynth(inlineSaw, overridden, false);
      assertNull(overridden.getDx7Patch(), "Inline non-DX7 <sound> must clear preset dx7Patch");
      assertEquals(0, overridden.getEngineType());
      assertEquals(0, overridden.getDx7RandomDetune());
    } finally {
      if (prevCard == null) {
        System.clearProperty("deluge.card");
      } else {
        System.setProperty("deluge.card", prevCard);
      }
    }

    // Hardware <mode>fm</mode> + <osc1 type="dx7"/> normalizes to 2-op SINE FM and re-saves
    // <mode>fm
    String hw2OpFmXml = "<sound><mode>fm</mode><osc1 type=\"dx7\"/><osc2 type=\"square\"/></sound>";
    SynthTrackModel parsed2OpFm =
        InstrumentXmlParser.parseSynth(
            new ByteArrayInputStream(hw2OpFmXml.getBytes(StandardCharsets.UTF_8)), "hw_2op_fm");
    assertEquals(1, parsed2OpFm.getSynthMode());
    assertEquals("SINE", parsed2OpFm.getOsc1Type());
    assertEquals("SINE", parsed2OpFm.getOsc2Type());
    assertNull(parsed2OpFm.getDx7Patch());
    File saved2OpFm = tempDir.resolve("resaved_2op_fm.xml").toFile();
    KitSynthSerializer.saveSynth(parsed2OpFm, saved2OpFm);
    assertTrue(
        Files.readString(saved2OpFm.toPath(), StandardCharsets.UTF_8).contains("<mode>fm</mode>"));

    // Hardware <mode>ringmod</mode> + <osc1 type="dx7"/> clamps to WAVETABLE and renders non-zero
    String hwRingmodXml =
        "<sound><mode>ringmod</mode><osc1 type=\"dx7\"/><osc2 type=\"sine\"/></sound>";
    SynthTrackModel parsedRingmod =
        InstrumentXmlParser.parseSynth(
            new ByteArrayInputStream(hwRingmodXml.getBytes(StandardCharsets.UTF_8)), "hw_ringmod");
    assertEquals(2, parsedRingmod.getSynthMode());
    assertEquals("WAVETABLE", parsedRingmod.getOsc1Type());
    assertNull(parsedRingmod.getDx7Patch());
    FirmwareSound ringFs = compileSynth(parsedRingmod);
    ringFs.triggerNote(60, 100);
    java.util.Arrays.fill(stereoBuf, 0L);
    ringFs.renderOutput(stereoBuf, 128, revBuf);
    long ringPeak = 0;
    for (long sample : stereoBuf) {
      ringPeak = Math.max(ringPeak, Math.abs(sample));
    }
    assertTrue(
        ringPeak > 0, "RingMod clamped from DX7 must render audible output instead of silence");
  }

  private static javax.swing.JSlider findSliderWithMax(Container root, int maxVal) {
    for (Component c : root.getComponents()) {
      if (c instanceof javax.swing.JSlider s && s.getMaximum() == maxVal && s.getMinimum() == 0) {
        return s;
      }
      if (c instanceof Container child) {
        javax.swing.JSlider found = findSliderWithMax(child, maxVal);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static JComboBox<?> findEngineTypeCombo(Container root) {
    for (Component c : root.getComponents()) {
      if (c instanceof JComboBox<?> cb && cb.getItemCount() == 3) {
        Object first = cb.getItemAt(0);
        if (first != null && first.toString().contains("Auto")) {
          return cb;
        }
      }
      if (c instanceof Container child) {
        JComboBox<?> found = findEngineTypeCombo(child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static JButton findAlgorithmSelectButton(Container root, int algoIndex) {
    String targetLabel = "Algo " + algoIndex;
    for (Component c : root.getComponents()) {
      if (c instanceof Container row) {
        boolean hasTargetLabel = false;
        JButton selectBtn = null;
        for (Component child : row.getComponents()) {
          if (child instanceof javax.swing.JLabel lbl && targetLabel.equals(lbl.getText())) {
            hasTargetLabel = true;
          } else if (child instanceof JButton btn && "Select".equals(btn.getText())) {
            selectBtn = btn;
          }
        }
        if (hasTargetLabel && selectBtn != null) {
          return selectBtn;
        }
        JButton nested = findAlgorithmSelectButton(row, algoIndex);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  private static JButton findActivateButton(Container root) {
    for (Component c : root.getComponents()) {
      if (c instanceof JButton btn && btn.getText().contains("Activate FM / DX7")) {
        return btn;
      }
      if (c instanceof Container child) {
        JButton found = findActivateButton(child);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }
}
