package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Dedicated regression tests for the six Copilot findings on PR #8. */
class CopilotPr8FixesTest {

  @Test
  void playConditionRoundTripsThroughStepDataAndClipModel() {
    StepData step = new StepData(true, 0.8f, 1.0f, 0.5f, 60, 2, 1.0f, 0.0f, 0x0201);
    assertEquals(0x0201, step.playCondition());
    assertEquals(2, step.iterance());
    assertTrue(step.hasEditedNoteParameters());
    assertEquals(0x0201, step.withPitch(64).playCondition());

    ClipModel clip = new ClipModel("CondClip", 8, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 2, step);
    assertTrue(clip.nudgeNotesVertically(1, false, false, null, 0));

    StepData retuned = clip.getStep(0, 2);
    assertEquals(61, retuned.pitch());
    assertEquals(0x0201, retuned.playCondition());
    assertEquals(1.0f, retuned.fill(), 0.001f);
    assertEquals(0.5f, retuned.probability(), 0.001f);
    assertEquals(0x0201, clip.noteRows.get(0).notes.get(0).getIterance().toInt());
    assertEquals(2, clip.noteRows.get(0).notes.get(0).getFill());
  }

  @Test
  void songXmlParserParses26CharNoteDataWithIteranceAndFill() throws Exception {
    // 26 hex chars per note: pos(8) + len(8) + vel(2) + lift(2) + prob(2) + iterance(2) + fill(2).
    // pos=0x00000030 (48 ticks = step 2 at 24 ticks/step), len=0x00000018 (24 ticks = 1 step),
    // vel=0x66, lift=0x40, prob=0x0A (50%), iterance=0x01 (preset 1 = 1 of 2 = 0x0201), fill=0x02.
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="c1.3.0" earliestCompatibleFirmware="4.1.0-alpha">
          <instruments>
            <sound presetName="INIT" mode="subtractive" polyphonic="1" voicePriority="1">
              <osc1 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <osc2 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <defaultParams lpfFrequency="0x7FFFFFFF" lpfResonance="0x80000000" volume="0x4CCCCCA8" />
            </sound>
          </instruments>
          <sessionClips>
            <instrumentClip length="384" instrumentPresetName="INIT">
              <noteRows>
                <noteRow y="60" noteDataWithIteranceAndFill="0x000000300000001866400A0102" />
              </noteRows>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    ProjectModel project =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "Iterance26Song");
    ClipModel clip = project.getTracks().get(0).getClips().get(0);
    StepData step = null;
    for (int r = 0; r < clip.getRowCount(); r++) {
      StepData candidate = clip.getStep(r, 2);
      if (candidate.active()) {
        step = candidate;
        break;
      }
    }
    assertTrue(step != null && step.active(), "Step 2 should be parsed from 26-char noteData");
    assertEquals(0.5f, step.probability(), 0.001f);
    assertEquals(0x0201, step.playCondition());
    assertEquals(1.0f, step.fill(), 0.001f);
  }

  @Test
  void projectSerializerRoundTripsSplitProbConditionsAndFill(@TempDir Path tempDir)
      throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("COND_SYNTH");
    ClipModel clip = new ClipModel("Clip 1", 8, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 2, new StepData(true, 0.8f, 1.0f, 0.25f, 60, 0, 1.0f, 0.0f, 0x0302));
    synth.addClip(clip);
    project.addTrack(synth);

    File songFile = tempDir.resolve("roundtrip_cond.xml").toFile();
    ProjectSerializer.save(project, songFile);

    String savedXml = Files.readString(songFile.toPath());
    assertTrue(savedXml.contains("noteDataWithSplitProb"), "Should emit 28-char split-prob format");

    ProjectModel reloaded = SongXmlParser.parseSong(songFile);
    ClipModel reloadedClip = reloaded.getTracks().get(0).getClips().get(0);
    StepData step = null;
    for (int r = 0; r < reloadedClip.getRowCount(); r++) {
      StepData candidate = reloadedClip.getStep(r, 2);
      if (candidate.active()) {
        step = candidate;
        break;
      }
    }
    assertTrue(step != null && step.active(), "Step 2 should survive save/load round-trip");
    assertEquals(0.25f, step.probability(), 0.001f);
    assertEquals(0x0302, step.playCondition());
    assertEquals(1.0f, step.fill(), 0.001f);
  }

  @Test
  void synthTrackModelEditsClearRawParamKnobOverrides() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <sound mode="subtractive" polyphonic="1" voicePriority="1">
          <osc1 type="saw" transpose="0" cents="0" retrigPhase="-1" />
          <osc2 type="saw" transpose="0" cents="0" retrigPhase="-1" />
          <defaultParams
              pitchAdjust="0x10000000"
              oscAPitchAdjust="0x20000000"
              oscBPitchAdjust="0x30000000">
            <equalizer bass="0x40000000" treble="0x50000000" />
          </defaultParams>
        </sound>
        """;

    SynthTrackModel synth =
        InstrumentXmlParser.parseSynth(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "RAW_TEST");

    assertTrue(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.LOCAL_PITCH_ADJUST));
    assertTrue(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.LOCAL_OSC_A_PITCH_ADJUST));
    assertTrue(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.LOCAL_OSC_B_PITCH_ADJUST));
    assertTrue(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.UNPATCHED_BASS));
    assertTrue(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.UNPATCHED_TREBLE));

    synth.setPitchAdjustQ31(11111);
    synth.setOsc1PitchAdjustQ31(22222);
    synth.setOsc2PitchAdjustQ31(33333);
    synth.setEqBass(0.0f);
    synth.setEqTreble(0.0f);

    assertFalse(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.LOCAL_PITCH_ADJUST));
    assertFalse(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.LOCAL_OSC_A_PITCH_ADJUST));
    assertFalse(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.LOCAL_OSC_B_PITCH_ADJUST));
    assertFalse(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.UNPATCHED_BASS));
    assertFalse(synth.getRawKnobs().getRawParamKnobs().containsKey(Param.UNPATCHED_TREBLE));

    FirmwareSound sound = new FirmwareSound();
    FirmwareFactory.applyModelToLiveSound(synth, sound);
    assertEquals(11111, sound.paramKnobs[Param.LOCAL_PITCH_ADJUST]);
    assertEquals(22222, sound.paramKnobs[Param.LOCAL_OSC_A_PITCH_ADJUST]);
    assertEquals(33333, sound.paramKnobs[Param.LOCAL_OSC_B_PITCH_ADJUST]);
    assertEquals(0, sound.paramKnobs[Param.UNPATCHED_BASS]);
    assertEquals(0, sound.paramKnobs[Param.UNPATCHED_TREBLE]);
  }

  @Test
  void kitSynthSerializerWritesDecimalUnisonSpreadForSynth(@TempDir Path tempDir) throws Exception {
    SynthTrackModel synth = new SynthTrackModel("SPREAD_SYNTH");
    synth.getUnison().setUnisonNum(4);
    synth.getUnison().setUnisonDetune(18);
    synth.getUnison().setUnisonStereoSpread(35);

    File synthFile = tempDir.resolve("spread_synth.xml").toFile();
    KitSynthSerializer.saveSynth(synth, synthFile);

    String xml = Files.readString(synthFile.toPath());
    assertTrue(xml.contains("<spread>35</spread>"), "Unison spread must be plain decimal integer");
    assertFalse(xml.contains("<spread>0x"), "Unison spread must not be hex Q31");

    SynthTrackModel parsed = InstrumentXmlParser.parseSynth(synthFile);
    assertEquals(35.0f, parsed.getUnison().getUnisonStereoSpread(), 0.001f);
  }

  @Test
  void kitDrumTransposeCentsAndSpreadRoundTripInKitAndProjectSerializers(@TempDir Path tempDir)
      throws Exception {
    KitTrackModel kit = new KitTrackModel("TUNE_KIT");
    SoundDrum drum = new SoundDrum("RIM");
    drum.setPitchSemitones(-7.0f);
    drum.setCents(15);
    drum.setUnisonNum(3);
    drum.setUnisonDetune(12);
    drum.setUnisonStereoSpread(25);
    kit.addDrum(drum);

    // 1. Standalone KitSynthSerializer -> KitXmlParser round-trip
    File kitFile = tempDir.resolve("tune_kit.xml").toFile();
    KitSynthSerializer.saveKit(kit, kitFile);
    KitTrackModel parsedKit = KitXmlParser.parseKit(kitFile);
    SoundDrum parsedDrum = (SoundDrum) parsedKit.getDrums().get(0);
    assertEquals(-7.0f, parsedDrum.getPitchSemitones(), 0.001f);
    assertEquals(15, parsedDrum.getCents());
    assertEquals(25.0f, parsedDrum.getUnisonStereoSpread(), 0.001f);

    // 2. Song ProjectSerializer -> SongXmlParser round-trip
    ProjectModel project = new ProjectModel();
    kit.addClip(new ClipModel("Kit Clip", 8, 16));
    project.addTrack(kit);
    File songFile = tempDir.resolve("tune_song.xml").toFile();
    ProjectSerializer.save(project, songFile);

    ProjectModel parsedProject = SongXmlParser.parseSong(songFile);
    KitTrackModel songKit = (KitTrackModel) parsedProject.getTracks().get(0);
    SoundDrum songDrum = (SoundDrum) songKit.getDrums().get(0);
    assertEquals(-7.0f, songDrum.getPitchSemitones(), 0.001f);
    assertEquals(15, songDrum.getCents());
    assertEquals(25.0f, songDrum.getUnisonStereoSpread(), 0.001f);
  }

  @Test
  void notFillRoundTripsThroughProjectSerializerWithoutTurningIntoFill(@TempDir Path tempDir)
      throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("NOT_FILL_SYNTH");
    ClipModel clip = new ClipModel("Clip 1", 8, 16);
    clip.setRowYNote(0, 60);
    // fillMode == 1 (C FillMode::NOT_FILL), playCondition == 0x0201 (1 of 2)
    StepData notFillStep = new StepData(true, 0.8f, 1.0f, 0.75f, 60, 0, 0.0f, 0.0f, 0x0201, 1);
    assertEquals(1, notFillStep.fillMode());
    assertEquals(0.0f, notFillStep.fill(), 0.001f);
    assertTrue(notFillStep.hasEditedNoteParameters());

    clip.setStep(0, 3, notFillStep);
    assertEquals(1, clip.noteRows.get(0).notes.get(0).getFill());
    synth.addClip(clip);
    project.addTrack(synth);

    File songFile = tempDir.resolve("roundtrip_not_fill.xml").toFile();
    ProjectSerializer.save(project, songFile);

    String savedXml = Files.readString(songFile.toPath());
    assertTrue(
        savedXml.contains("020101\""),
        "28-char noteDataWithSplitProb tail must end with iterance 0201 and fill 01 (NOT_FILL)");

    ProjectModel reloaded = SongXmlParser.parseSong(songFile);
    ClipModel reloadedClip = reloaded.getTracks().get(0).getClips().get(0);
    StepData step = null;
    int activeRow = -1;
    for (int r = 0; r < reloadedClip.getRowCount(); r++) {
      StepData candidate = reloadedClip.getStep(r, 3);
      if (candidate.active()) {
        step = candidate;
        activeRow = r;
        break;
      }
    }
    assertTrue(step != null && step.active(), "Step 3 should survive save/load round-trip");
    assertEquals(1, step.fillMode(), "NOT_FILL (1) must not flip to FILL (2)");
    assertEquals(0.0f, step.fill(), 0.001f);
    assertEquals(0x0201, step.playCondition());
    assertEquals(0.75f, step.probability(), 0.001f);
    assertEquals(1, reloadedClip.getRawNoteEvents(activeRow).get(0).getFill());
  }

  @Test
  void stepDataWithHelpersPreservePlayConditionAndFillMode() {
    StepData base = new StepData(true, 0.8f, 1.0f, 0.5f, 60, 2, 0.0f, 0.1f, 0x0405, 1);
    StepData edited =
        base.withVelocity(0.4f).withGate(2.5f).withProbability(0.25f).withPitch(67).withNudge(0.2f);
    assertEquals(0.4f, edited.velocity(), 0.001f);
    assertEquals(2.5f, edited.gate(), 0.001f);
    assertEquals(0.25f, edited.probability(), 0.001f);
    assertEquals(67, edited.pitch());
    assertEquals(0.2f, edited.nudge(), 0.001f);
    assertEquals(2, edited.iterance());
    assertEquals(0x0405, edited.playCondition());
    assertEquals(1, edited.fillMode());
    assertEquals(0.0f, edited.fill(), 0.001f);
  }

  @Test
  void songXmlParserPreservesRawNoteLiftWhenApplyingRowPitch() throws Exception {
    // Lift = 0x55 (85) in 28-char noteDataWithSplitProb on row y="64".
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="c1.3.0" earliestCompatibleFirmware="4.1.0-alpha">
          <instruments>
            <sound presetName="INIT" mode="subtractive" polyphonic="1" voicePriority="1">
              <osc1 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <osc2 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <defaultParams lpfFrequency="0x7FFFFFFF" lpfResonance="0x80000000" volume="0x4CCCCCA8" />
            </sound>
          </instruments>
          <sessionClips>
            <instrumentClip length="384" instrumentPresetName="INIT">
              <noteRows>
                <noteRow y="64" noteDataWithSplitProb="0x000000300000001866550A020101" />
              </noteRows>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    ProjectModel project =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "RawLiftSong");
    ClipModel clip = project.getTracks().get(0).getClips().get(0);
    assertEquals(64, clip.getStep(0, 2).pitch());
    assertEquals(1, clip.getStep(0, 2).fillMode());
    assertEquals(0x55, clip.getRawNoteEvents(0).get(0).getLift());
    assertEquals(1, clip.getRawNoteEvents(0).get(0).getFill());
  }

  @Test
  void scriptingEngineRoundTripsPlayConditionAndFillMode(@TempDir Path tempDir) throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("MACRO_SYNTH");
    ClipModel clip = new ClipModel("Clip 1", 8, 16);
    clip.setRowYNote(0, 60);
    synth.addClip(clip);
    project.addTrack(synth);

    StepData stepWithCondAndNotFill =
        new StepData(true, 0.7f, 1.5f, 0.5f, 60, 1, 0.0f, 0.1f, 0x0302, 1);

    org.deluge.model.ScriptingEngine engine = new org.deluge.model.ScriptingEngine();
    engine.startRecording();
    engine.record(
        new org.deluge.model.Consequence.StepConsequence(
            project, 0, 0, 0, 4, StepData.empty(), stepWithCondAndNotFill));
    engine.stopRecording();

    File scriptFile = tempDir.resolve("cond_macro.txt").toFile();
    engine.saveScript(scriptFile);

    // Clear clip and replay macro script
    clip.setStep(0, 4, StepData.empty());
    engine.loadAndExecuteScript(scriptFile, project);

    StepData replayed = clip.getStep(0, 4);
    assertTrue(replayed.active());
    assertEquals(0x0302, replayed.playCondition(), "Macro replay must preserve playCondition");
    assertEquals(1, replayed.fillMode(), "Macro replay must preserve NOT_FILL (fillMode == 1)");
    assertEquals(0.0f, replayed.fill(), 0.001f);
    assertEquals(1, clip.noteRows.get(0).notes.get(0).getFill());

    // Legacy 13-token STEP macro lines (without playCondition/fillMode) must still load cleanly.
    File legacyFile = tempDir.resolve("legacy_macro.txt").toFile();
    Files.writeString(
        legacyFile.toPath(),
        "STEP|0|0|0|5|true|0.800000|1.000000|1.000000|60|0|1.000000|0.000000\n");
    engine.loadAndExecuteScript(legacyFile, project);
    StepData legacyReplayed = clip.getStep(0, 5);
    assertTrue(legacyReplayed.active());
    assertEquals(0, legacyReplayed.playCondition());
    assertEquals(2, legacyReplayed.fillMode());
  }

  @Test
  void stepConsequenceUsesStoredClipRowIndexForSparseSynthClip() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("SPARSE_SYNTH");
    // Sparse synth clip: only 1 stored row (index 0) tuned to MIDI pitch 72 (display row 55).
    ClipModel clip = new ClipModel("Sparse Clip", 1, 16);
    clip.setRowYNote(0, 72);
    synth.addClip(clip);
    project.addTrack(synth);

    StepData cloned = new StepData(true, 0.8f, 1.0f, 0.5f, 72, 0, 0.0f, 0.0f, 0x0201, 1);
    clip.setStep(0, 4, cloned);

    // StepConsequence must store clip row 0 (not visual pitch row 55) so undo/redo hit row 0.
    org.deluge.model.Consequence.StepConsequence cons =
        new org.deluge.model.Consequence.StepConsequence(
            project, 0, 0, 0, 4, StepData.empty(), cloned);
    cons.undo();
    assertFalse(clip.getStep(0, 4).active(), "Undo must clear stored row 0");

    cons.redo();
    StepData redone = clip.getStep(0, 4);
    assertTrue(redone.active(), "Redo must restore stored row 0");
    assertEquals(72, redone.pitch());
    assertEquals(0x0201, redone.playCondition());
    assertEquals(1, redone.fillMode());
  }

  @Test
  void engineSyncCoordinatorPushesStepProbabilityForSynthAndKit() throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="c1.3.0" earliestCompatibleFirmware="4.1.0-alpha">
          <instruments>
            <sound presetName="INIT" mode="subtractive" polyphonic="1" voicePriority="1">
              <osc1 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <osc2 type="saw" transpose="0" cents="0" retrigPhase="-1" />
              <defaultParams lpfFrequency="0x7FFFFFFF" lpfResonance="0x80000000" volume="0x4CCCCCA8" />
            </sound>
            <kit presetName="KIT1">
              <soundSources>
                <sound name="KICK" mode="subtractive" polyphonic="0" voicePriority="1">
                  <osc1 type="saw" transpose="0" cents="0" retrigPhase="-1" />
                  <osc2 type="saw" transpose="0" cents="0" retrigPhase="-1" />
                  <defaultParams lpfFrequency="0x7FFFFFFF" lpfResonance="0x80000000" volume="0x4CCCCCA8" />
                </sound>
              </soundSources>
            </kit>
          </instruments>
          <sessionClips>
            <instrumentClip length="384" instrumentPresetName="INIT">
              <noteRows>
                <noteRow y="60" noteDataWithSplitProb="0x0000003000000018664005020100" />
              </noteRows>
            </instrumentClip>
            <instrumentClip length="384" instrumentPresetFolder="KITS" instrumentPresetName="KIT1">
              <noteRows>
                <noteRow drumIndex="0" noteDataWithSplitProb="0x000000480000001866400F000000" />
              </noteRows>
            </instrumentClip>
          </sessionClips>
        </song>
        """;

    ProjectModel project =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "BridgeProbSong");

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    // Synth track (row 0, step 2) -> probByte 0x05 = 5/20 = 0.25
    assertEquals(0.25, bridge.getStepProbability(0, 2), 0.001);
    // Kit track (startRow 8, row 0, step 3) -> probByte 0x0F = 15/20 = 0.75
    int kitStartRow = sync.getTrackEngineStart(1);
    assertEquals(0.75, bridge.getStepProbability(kitStartRow, 3), 0.001);
  }

  @Test
  void zeroProbabilityStepRoundTripsThroughProjectSaveAndLoad(@TempDir Path tempDir)
      throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("ZERO_PROB_SYNTH");
    ClipModel clip = new ClipModel("Clip 1", 8, 16);
    clip.setRowYNote(0, 60);
    // Step 2: 0% probability (silent), step 3: 2% probability (positive, clamps up to 5%)
    clip.setStep(0, 2, new StepData(true, 0.8f, 1.0f, 0.0f, 60, 0, 0.0f, 0.0f, 0x0201, 1));
    clip.setStep(0, 3, new StepData(true, 0.8f, 1.0f, 0.02f, 60, 0, 0.0f, 0.0f, 0, 0));
    synth.addClip(clip);
    project.addTrack(synth);

    File songFile = tempDir.resolve("zero_prob_song.xml").toFile();
    ProjectSerializer.save(project, songFile);

    ProjectModel loaded = SongXmlParser.parseSong(songFile);
    ClipModel loadedClip = loaded.getTracks().get(0).getClips().get(0);

    StepData step2 = loadedClip.getStep(0, 2);
    assertTrue(step2.active(), "Step 2 must remain active");
    assertEquals(
        0.0f, step2.probability(), 0.001f, "0% probability must remain 0% after save/load");
    assertEquals(0x0201, step2.playCondition(), "Step 2 playCondition must be preserved");
    assertEquals(1, step2.fillMode(), "Step 2 NOT_FILL must be preserved");
    assertEquals(0.0f, loadedClip.getRawNoteEvents(0).get(0).getProbability(), 0.001f);

    StepData step3 = loadedClip.getStep(0, 3);
    assertTrue(step3.active(), "Step 3 must remain active");
    assertEquals(0.05f, step3.probability(), 0.001f, "Positive <5% probability clamps up to 5%");
  }

  @Test
  void decodeRowHandlesHighBitPositionWithoutOverflowingLong() {
    // Position 0x80000000 has bit 31 set; slicing length from idx+8 avoids 16-digit Long overflow.
    String hex = "0x800000000000000C664014000000";
    var rawNotes = DelugeNoteDataMapper.decodeRawNotes(hex, 28);
    assertEquals(1, rawNotes.size());
    assertEquals(0x80000000, rawNotes.get(0).getPos());
    assertEquals(12, rawNotes.get(0).getLength());

    var row = DelugeNoteDataMapper.decodeRow(hex, 16, 12, 28);
    assertEquals(16, row.size());
    for (StepData step : row) {
      assertFalse(step.active(), "Out-of-range negative step position must be ignored cleanly");
    }
  }

  @Test
  void duplicateStepOnKitTrackUsesDestinationDrumRowIndex() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    KitTrackModel kit = new KitTrackModel("DRUMS");
    for (int i = 0; i < 4; i++) {
      kit.getDrums().add(new SoundDrum("DRUM_" + i));
    }
    ClipModel clip = new ClipModel("Kit Clip", 4, 16);
    clip.setIsKit(true);
    clip.setStep(0, 1, new StepData(true, 0.8f, 1.0f, 0.75f, 0, 0, 0.0f, 0.0f, 0x0201, 1));
    kit.addClip(clip);
    project.addTrack(kit);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setProjectModel(project);
    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // In a 4-drum kit CLIP view (bottom-up), visual row 3 maps to drum 0 and visual row 0 maps to
    // drum 3.
    controller.duplicateStep(3, 1, 0, 5);

    StepData cloned = clip.getStep(3, 5);
    assertTrue(cloned.active(), "Destination drum 3 step 5 must be active");
    assertEquals(3, cloned.pitch(), "Cloned kit step pitch must equal destination drum index 3");
    assertEquals(0x0201, cloned.playCondition());
    assertEquals(1, cloned.fillMode());

    int kitBaseEngineRow = sync.getTrackEngineStart(0);
    assertTrue(
        bridge.getStep(kitBaseEngineRow + 3, 5),
        "Bridge must mark destination drum 3 step 5 active");
    assertFalse(
        bridge.getStep(kitBaseEngineRow + 0, 5),
        "Bridge must not write cloned drum 3 step onto drum 0");

    // A subsequent properties edit on visual row 0 (drum 3) must read the active bridge step and
    // preserve the cloned note's active state, drum pitch, playCondition, and fillMode.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 0, 5, 0.9);
    StepData edited = clip.getStep(3, 5);
    assertTrue(edited.active(), "Cloned kit step must remain active after properties edit");
    assertEquals(0.9f, edited.velocity(), 0.001f);
    assertEquals(3, edited.pitch(), "Edited kit step pitch must remain drum index 3");
    assertEquals(0x0201, edited.playCondition());
    assertEquals(1, edited.fillMode());

    clip.syncNoteRowsFromGrid();
    assertEquals(
        3,
        clip.getOrCreateRow(3).getPitch(),
        "NoteRowModel pitch for drum row 3 must stay 3 (not 60)");
  }

  @Test
  void duplicateStepOnSparseSynthClipSyncsBridgeAndSurvivesVelocityEdit() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("SPARSE_SYNTH");
    // Sparse synth clip: only 1 stored row (row 0) at MIDI 60.
    ClipModel clip = new ClipModel("Sparse Clip", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 1, new StepData(true, 0.8f, 1.0f, 0.75f, 60, 0, 0.0f, 0.0f, 0x0201, 1));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setProjectModel(project);
    // With scrollOffset=64, visual row 3 maps to modelRow 67 (MIDI 60) and visual row 1 maps to
    // modelRow 65 (MIDI 64 in C major).
    panel.setScrollOffset(64);
    assertEquals(60, panel.getRowPitch(67));
    int dstPitch = panel.getRowPitch(65);
    assertEquals(64, dstPitch);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    controller.duplicateStep(3, 1, 1, 5);

    assertEquals(2, clip.getRowCount(), "Cloning to a new pitch must append stored row 1");
    assertEquals(dstPitch, clip.getRowYNote(1));
    StepData cloned = clip.getStep(1, 5);
    assertTrue(cloned.active(), "Cloned sparse synth step must be active in ClipModel");
    assertEquals(dstPitch, cloned.pitch());
    assertEquals(0x0201, cloned.playCondition());
    assertEquals(1, cloned.fillMode());

    int synthBaseEngineRow = sync.getTrackEngineStart(0);
    assertTrue(
        bridge.getStep(synthBaseEngineRow + 1, 5),
        "Bridge must mark stored row 1 step 5 active for the cloned synth note");
    assertEquals(dstPitch, bridge.getPitch(synthBaseEngineRow + 1, 5));

    // A subsequent Quick Velocity edit on visual row 1 step 5 must find the active bridge entry at
    // stored row 1 and keep the cloned note active.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 1, 5, 0.45);
    StepData edited = clip.getStep(1, 5);
    assertTrue(edited.active(), "Cloned sparse synth step must survive Quick Velocity edit");
    assertEquals(0.45f, edited.velocity(), 0.001f);
    assertEquals(dstPitch, edited.pitch());
    assertEquals(0x0201, edited.playCondition());
    assertEquals(1, edited.fillMode());
  }

  @Test
  void duplicateStepPreservesNonzeroIteranceRepeatsAcrossVelocityEdit() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    KitTrackModel kit = new KitTrackModel("REPEAT_DRUMS");
    for (int i = 0; i < 4; i++) {
      kit.getDrums().add(new SoundDrum("DRUM_" + i));
    }
    ClipModel clip = new ClipModel("Repeat Clip", 4, 16);
    clip.setIsKit(true);
    // Source step on drum 0 step 2 has iterance=2 (2 repeats / 3 subtriggers) and condition 0x0302.
    clip.setStep(0, 2, new StepData(true, 0.8f, 1.0f, 0.75f, 0, 2, 0.0f, 0.0f, 0x0302, 0));
    kit.addClip(clip);
    project.addTrack(kit);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setProjectModel(project);
    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Clone from visual row 3 (drum 0) step 2 to visual row 1 (drum 2) step 6.
    controller.duplicateStep(3, 2, 1, 6);

    StepData cloned = clip.getStep(2, 6);
    assertTrue(cloned.active());
    assertEquals(2, cloned.iterance(), "Cloned step must keep iterance repeats in ClipModel");
    assertEquals(0x0302, cloned.playCondition());

    int kitBaseEngineRow = sync.getTrackEngineStart(0);
    assertEquals(
        2,
        bridge.getIterance(kitBaseEngineRow + 2, 6),
        "Bridge must receive cloned iterance repeat count");

    // A subsequent Quick Velocity edit on visual row 1 (drum 2) step 6 must preserve iterance=2.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 1, 6, 0.6);
    StepData edited = clip.getStep(2, 6);
    assertTrue(edited.active(), "Cloned step must stay active after Quick Velocity edit");
    assertEquals(0.6f, edited.velocity(), 0.001f);
    assertEquals(2, edited.iterance(), "Quick Velocity edit must not erase cloned repeats");
    assertEquals(2, bridge.getIterance(kitBaseEngineRow + 2, 6));
    assertEquals(0x0302, edited.playCondition());
  }

  @Test
  void pushModelToBridgeCapsSynthRowsAtBridgeCapacityWithoutOverflow() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth0 = new SynthTrackModel("SYNTH_80");
    ClipModel clip0 = new ClipModel("Clip 80", 80, 16);
    for (int r = 0; r < 80; r++) {
      clip0.setRowYNote(r, r);
    }
    clip0.setStep(0, 1, StepData.of(true, 0.8f, 1.0f, 1.0f, 0));
    synth0.addClip(clip0);
    project.addTrack(synth0);

    SynthTrackModel synth1 = new SynthTrackModel("SYNTH_60");
    ClipModel clip1 = new ClipModel("Clip 60", 60, 16);
    for (int r = 0; r < 60; r++) {
      clip1.setRowYNote(r, r);
    }
    clip1.setStep(47, 2, StepData.of(true, 0.7f, 1.0f, 1.0f, 47));
    clip1.setStep(59, 3, StepData.of(true, 0.9f, 1.0f, 1.0f, 59));
    synth1.addClip(clip1);
    project.addTrack(synth1);

    org.deluge.model.AudioTrackModel audio2 = new org.deluge.model.AudioTrackModel("AUDIO_OVER");
    project.addTrack(audio2);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);

    sync.pushModelToBridge(project);

    assertEquals(0, sync.getTrackEngineStart(0));
    assertEquals(80, sync.getTrackVoiceCount(0));
    assertEquals(80, sync.getTrackEngineStart(1));
    assertEquals(org.deluge.BridgeContract.TRACKS - 80, sync.getTrackVoiceCount(1));
    assertEquals(-1, sync.getTrackEngineStart(2));
    assertEquals(0, sync.getTrackVoiceCount(2));

    assertEquals(1, bridge.getTrackType(0), "Track 0 row 0 must remain synth type 1");
    assertTrue(bridge.getStep(80 + 47, 2), "Last allocated row of track 1 must sync");
    assertEquals(1, bridge.getTrackId(org.deluge.BridgeContract.TRACKS - 1));
  }

  @Test
  void ninthSynthRowReservesBridgeCapacityWithoutOverlappingNextTrack() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth = new SynthTrackModel("SYNTH_8");
    ClipModel synthClip = new ClipModel("Synth Clip", 8, 16);
    int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};
    for (int r = 0; r < 8; r++) {
      synthClip.setRowYNote(r, cMajorPitches[r]);
    }
    synthClip.setStep(0, 1, new StepData(true, 0.8f, 1.0f, 1.0f, 60, 0, 0.0f, 0.0f, 0x0201, 0));
    synth.addClip(synthClip);
    project.addTrack(synth);

    KitTrackModel kit = new KitTrackModel("NEXT_KIT");
    for (int i = 0; i < 4; i++) {
      kit.getDrums().add(new SoundDrum("DRUM_" + i));
    }
    ClipModel kitClip = new ClipModel("Kit Clip", 4, 16);
    kitClip.setIsKit(true);
    kitClip.setStep(0, 5, StepData.of(true, 0.9f, 1.0f, 1.0f, 0));
    kit.addClip(kitClip);
    project.addTrack(kit);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    assertEquals(8, sync.getTrackEngineStart(1));
    assertTrue(bridge.getStep(8, 5), "Next track drum 0 step 5 starts at engine row 8");

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setProjectModel(project);
    // With scrollOffset=60, visual row 7 maps to modelRow 67 (MIDI 60, stored row 0) and visual
    // row 0 maps to modelRow 60 (MIDI 72? Let's check: 67->60, 66->62, 65->64, 64->65, 63->67,
    // 62->69, 61->71, 60->72, 59->74). With scrollOffset=60, let's use scrollOffset=59 so visual
    // row 0 is modelRow 59 (MIDI 74, 9th pitch) and visual row 7 is modelRow 66 (MIDI 62, row 1).
    panel.setScrollOffset(59);
    synthClip.setStep(1, 2, new StepData(true, 0.8f, 1.0f, 1.0f, 62, 0, 0.0f, 0.0f, 0x0201, 0));
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Toggle on an empty 9th synth pitch (visual row 0 -> modelRow 59 -> MIDI 74) at step 5, where
    // the following kit track has an active step at its drum 0 step 5.
    controller.handleStepToggled(0, 5);

    assertEquals(9, synthClip.getRowCount(), "Synth clip must grow to 9 stored rows");
    assertTrue(
        synthClip.getStep(8, 5).active(),
        "Empty 9th synth row must toggle ON despite next track having active step 5");
    assertEquals(9, sync.getTrackVoiceCount(0), "Synth track voice count must grow to 9");
    assertEquals(9, sync.getTrackEngineStart(1), "Following kit track must shift to engine row 9");
    assertTrue(bridge.getStep(8, 5), "Engine row 8 now holds the 9th synth row step");
    assertTrue(bridge.getStep(9, 5), "Following kit track step 5 must remain intact at row 9");

    // Also clone from visual row 7 (modelRow 66 -> stored row 1) step 2 onto row 0 (9th synth row)
    // step 6, and verify Track 1 is untouched.
    controller.duplicateStep(7, 2, 0, 6);
    assertTrue(synthClip.getStep(8, 6).active());
    assertTrue(bridge.getStep(8, 6));
    assertFalse(bridge.getStep(9, 6), "Cloning on 9th synth row must not touch kit track row 9");
  }

  @Test
  void mouseWheelEditOnSparseSynthClipSyncsBridgeAndSurvivesVelocityEdit() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("WHEEL_SYNTH");
    ClipModel clip = new ClipModel("Sparse Wheel Clip", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 2, new StepData(true, 0.8f, 1.0f, 1.0f, 60, 0, 0.0f, 0.0f, 0x0201, 0));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setProjectModel(project);
    panel.setScrollOffset(64);
    assertEquals(60, panel.getRowPitch(67));

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Shift + wheel down (rotation = 1 -> dir = -1) adjusts probability from 1.0 to 0.95 on visual
    // row 3 (modelRow 67, stored row 0), col 2.
    panel.setShiftHeld(true);
    java.awt.event.MouseWheelEvent wheelEvent =
        new java.awt.event.MouseWheelEvent(
            panel,
            java.awt.event.MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            java.awt.event.InputEvent.SHIFT_DOWN_MASK,
            0,
            0,
            0,
            false,
            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            1);
    controller.handlePadMouseWheel(3, 2, wheelEvent);
    panel.setShiftHeld(false);

    assertEquals(0.95f, clip.getStep(0, 2).probability(), 0.001f);
    assertEquals(
        0.95,
        bridge.getStepProbability(0, 2),
        0.001,
        "Wheel edit must sync stored row 0 in bridge");

    // A subsequent Quick Velocity edit on visual row 3, col 2 must read stored row 0's updated
    // bridge probability (0.95) and preserve it.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 3, 2, 0.5);
    StepData edited = clip.getStep(0, 2);
    assertTrue(edited.active());
    assertEquals(0.5f, edited.velocity(), 0.001f);
    assertEquals(
        0.95f,
        edited.probability(),
        0.001f,
        "Quick Velocity edit must not overwrite wheel-edited probability");
    assertEquals(0x0201, edited.playCondition());
  }

  @Test
  void arrangerSchedulerUsesCoordinatorEngineStartForNineRowSynth() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth0 = new SynthTrackModel("SYNTH_9");
    ClipModel clip0 = new ClipModel("Clip 9", 9, 16);
    for (int r = 0; r < 9; r++) {
      clip0.setRowYNote(r, 60 + r);
    }
    clip0.setStep(8, 0, StepData.of(true, 0.9f, 1.0f, 1.0f, 68));
    synth0.addClip(clip0);
    project.addTrack(synth0);

    SynthTrackModel synth1 = new SynthTrackModel("SYNTH_2");
    ClipModel clip1 = new ClipModel("Clip 2", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip1.setRowYNote(r, 48 + r);
    }
    clip1.setStep(0, 0, StepData.of(true, 0.7f, 1.0f, 1.0f, 48));
    synth1.addClip(clip1);
    project.addTrack(synth1);

    project.getArrangerTimeline().add(new org.deluge.model.ArrangerClip(0, clip0, 0, 16 * 24));
    project.getArrangerTimeline().add(new org.deluge.model.ArrangerClip(1, clip1, 0, 16 * 24));

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    assertEquals(0, sync.getTrackEngineStart(0));
    assertEquals(9, sync.getTrackVoiceCount(0));
    assertEquals(9, sync.getTrackEngineStart(1));
    assertEquals(8, sync.getTrackVoiceCount(1));

    org.deluge.ui.ArrangerPlaybackScheduler scheduler =
        new org.deluge.ui.ArrangerPlaybackScheduler(bridge, project);
    try {
      java.lang.reflect.Method updateMethod =
          org.deluge.ui.ArrangerPlaybackScheduler.class.getDeclaredMethod(
              "updateBridgeUpcomingStep", int.class);
      updateMethod.setAccessible(true);
      updateMethod.invoke(scheduler, 0);

      assertTrue(bridge.getStep(8, 0), "Track 0 ninth synth row at engine row 8 must stay active");
      assertEquals(68, bridge.getPitch(8, 0));
      assertTrue(bridge.getStep(9, 0), "Track 1 first synth row must write at engine row 9");
      assertEquals(48, bridge.getPitch(9, 0));
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  void editingNonActiveClipReservesCapacityAndRestoresEditedBridgeState() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth = new SynthTrackModel("DUAL_CLIP_SYNTH");
    ClipModel clipA = new ClipModel("Clip A Playing", 8, 16);
    int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};
    for (int r = 0; r < 8; r++) {
      clipA.setRowYNote(r, cMajorPitches[r]);
    }
    clipA.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    synth.addClip(clipA);

    ClipModel clipB = new ClipModel("Clip B Edited", 8, 16);
    for (int r = 0; r < 8; r++) {
      clipB.setRowYNote(r, cMajorPitches[r]);
    }
    clipB.setStep(1, 3, StepData.of(true, 0.7f, 1.0f, 1.0f, 62));
    synth.addClip(clipB);
    synth.setActiveClipIndex(0);
    project.addTrack(synth);

    KitTrackModel nextKit = new KitTrackModel("NEXT_KIT");
    for (int i = 0; i < 4; i++) {
      nextKit.getDrums().add(new SoundDrum("DRUM_" + i));
    }
    ClipModel kitClip = new ClipModel("Kit Clip", 4, 16);
    kitClip.setIsKit(true);
    kitClip.setStep(0, 5, StepData.of(true, 0.9f, 1.0f, 1.0f, 0));
    nextKit.addClip(kitClip);
    project.addTrack(nextKit);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setActiveClipId(1);
    panel.setProjectModel(project);
    panel.setScrollOffset(59);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Toggle a 9th pitch row on edited Clip B while Clip A (8 rows) remains the playing clip.
    controller.handleStepToggled(0, 5);

    assertEquals(9, clipB.getRowCount(), "Edited Clip B must grow to 9 rows");
    assertEquals(9, sync.getTrackVoiceCount(0), "Coordinator must reserve 9 rows across clips");
    assertEquals(9, sync.getTrackEngineStart(1), "Following kit track must shift to engine row 9");
    assertTrue(bridge.getStep(8, 5), "Edited Clip B row 8 step 5 must be active in bridge");
    assertTrue(bridge.getStep(1, 3), "Edited Clip B existing step 3 must remain in bridge");
    assertFalse(
        bridge.getStep(0, 0),
        "Playing Clip A step 0 must not overwrite edited Clip B bridge state");
    assertTrue(bridge.getStep(9, 5), "Following kit track step 5 must stay intact at row 9");
  }

  @Test
  void unallocatedAndMissingSynthRowsDoNotCorruptBridgeOrThrow() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth0 = new SynthTrackModel("SYNTH_80");
    ClipModel clip0 = new ClipModel("Clip 80", 80, 16);
    for (int r = 0; r < 80; r++) {
      clip0.setRowYNote(r, r);
    }
    synth0.addClip(clip0);
    project.addTrack(synth0);

    SynthTrackModel synth1 = new SynthTrackModel("SYNTH_60");
    ClipModel clip1 = new ClipModel("Clip 60", 60, 16);
    for (int r = 0; r < 60; r++) {
      clip1.setRowYNote(r, r);
    }
    // Stored row 47 is the last allocated row (80 + 47 = 127). Stored row 59 is beyond capacity.
    clip1.setStep(47, 2, StepData.of(true, 0.9f, 1.0f, 1.0f, 47));
    clip1.setStep(59, 3, new StepData(true, 0.8f, 1.0f, 0.9f, 59, 2, 0.0f, 0.0f, 0x0201, 0));
    synth1.addClip(clip1);
    project.addTrack(synth1);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setEditedModelTrack(1);
    panel.setBaseTrackId(80);
    panel.setProjectModel(project);
    // In chromatic mode, modelRow = 127 - pitch. For pitch 59, modelRow = 68. For pitch 100
    // (missing pitch), modelRow = 27.
    panel.setScrollOffset(68);
    assertEquals(59, panel.getRowPitch(68));

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // 1. Quick Velocity on stored row 59 (beyond the 48 allocated bridge rows) must update the
    // model without throwing ArrayIndexOutOfBoundsException.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 0, 3, 0.4);
    StepData updated59 = clip1.getStep(59, 3);
    assertTrue(updated59.active());
    assertEquals(0.4f, updated59.velocity(), 0.001f);
    assertEquals(2, updated59.iterance());
    assertEquals(0x0201, updated59.playCondition());

    // 2. Quick Velocity on a missing pitch (pitch 100 -> modelRow 27) must be a no-op and must not
    // touch row 47's bridge velocity or create an unintended active copy.
    panel.setScrollOffset(27);
    assertEquals(100, panel.getRowPitch(27));
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 0, 2, 0.25);
    assertEquals(60, clip1.getRowCount(), "Missing pitch velocity edit must not create a new row");
    assertEquals(
        0.9,
        bridge.getVelocity(80 + 47, 2),
        0.001,
        "Last allocated row velocity must remain untouched");
  }

  @Test
  void wheelEditFollowedByPropertiesEditOnSparseClipPreservesProbability() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("SPARSE_WHEEL");
    ClipModel clip = new ClipModel("Sparse", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 2, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setProjectModel(project);
    // In chromatic mode, MIDI 60 maps to display modelRow = 127 - 60 = 67, stored row = 0.
    panel.setScrollOffset(67);
    assertEquals(60, panel.getRowPitch(67));
    panel.setShiftHeld(true);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    java.awt.event.MouseWheelEvent wheelDown =
        new java.awt.event.MouseWheelEvent(
            panel,
            java.awt.event.MouseEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            java.awt.event.InputEvent.SHIFT_DOWN_MASK,
            0,
            0,
            0,
            false,
            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
            1,
            1);
    controller.handlePadMouseWheel(0, 2, wheelDown);
    panel.setShiftHeld(false);

    assertEquals(0.95f, clip.getStep(0, 2).probability(), 0.001f);
    assertEquals(
        0.95, bridge.getStepProbability(0, 2), 0.001, "Stored row 0 bridge prob must update");
    assertEquals(
        1.0, bridge.getStepProbability(67, 2), 0.001, "Display row 67 bridge prob must not change");

    // Subsequent Quick Velocity edit must read probability from stored row 0 (0.95), not stale 1.0.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 0, 2, 0.5);
    StepData afterVel = clip.getStep(0, 2);
    assertTrue(afterVel.active());
    assertEquals(0.5f, afterVel.velocity(), 0.001f);
    assertEquals(0.95f, afterVel.probability(), 0.001f);
  }

  @Test
  void missingPitchOnEightRowSynthDoesNotTouchRow7AndPasteAllocatesRow8() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("SYNTH_8");
    ClipModel clip = new ClipModel("Clip 8", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip.setRowYNote(r, 60 + r);
    }
    clip.setStep(7, 2, StepData.of(true, 0.9f, 1.0f, 0.85f, 67));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);
    assertEquals(8, sync.getTrackVoiceCount(0));

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setProjectModel(project);
    // Pitch 70 is missing in clip (rows are 60..67). In chromatic mode, modelRow = 127 - 70 = 57.
    panel.setScrollOffset(57);
    assertEquals(70, panel.getRowPitch(57));
    assertEquals(-1, panel.getAllocatedEngineRow(57));

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Quick Velocity and Quick Probability on missing pitch 70 must skip without touching row 7.
    org.deluge.ui.StepPropertiesEditor.applyVelocity(controller, 0, 2, 0.25);
    org.deluge.ui.StepPropertiesEditor.saveStepProbability(controller, 0, 2, 0.5);
    assertEquals(8, clip.getRowCount());
    assertEquals(0.9, bridge.getVelocity(7, 2), 0.001, "Row 7 bridge velocity must stay 0.9");
    assertEquals(
        0.85, bridge.getStepProbability(7, 2), 0.001, "Row 7 bridge probability must stay 0.85");

    // Pasting onto missing pitch 70 must allocate row 8 and write to bridge row 8, leaving row 7
    // intact.
    StepData copied = clip.getStep(7, 2);
    int dstClipRow = panel.getClipRowIndex(clip, 57, true);
    assertEquals(8, dstClipRow);
    assertEquals(9, clip.getRowCount());
    assertEquals(9, sync.getTrackVoiceCount(0));
    StepData pasted = copied.withPitch(70);
    clip.setStep(dstClipRow, 2, pasted);
    int dstEngineRow = panel.getAllocatedEngineRow(57);
    assertEquals(8, dstEngineRow);
    bridge.setStep(dstEngineRow, 2, true);
    bridge.setVelocity(dstEngineRow, 2, pasted.velocity());
    bridge.setPitch(dstEngineRow, 2, pasted.pitch());

    assertTrue(bridge.getStep(8, 2));
    assertEquals(70, bridge.getPitch(8, 2));
    assertEquals(67, bridge.getPitch(7, 2));
    assertEquals(0.9, bridge.getVelocity(7, 2), 0.001);
  }

  @Test
  void editingNonZeroActiveClipRestoresSelectedClipBridgeStateAfterRowAllocation() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("ACTIVE_CLIP_1");
    int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};

    ClipModel clip0 = new ClipModel("Clip 0", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip0.setRowYNote(r, cMajorPitches[r]);
    }
    clip0.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    synth.addClip(clip0);

    ClipModel clip1 = new ClipModel("Clip 1 Active", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip1.setRowYNote(r, cMajorPitches[r]);
    }
    clip1.setStep(1, 3, StepData.of(true, 0.7f, 1.0f, 1.0f, 62));
    synth.addClip(clip1);
    synth.setActiveClipIndex(1);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setActiveClipId(1);
    panel.setProjectModel(project);
    panel.setScrollOffset(59);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Adding a 9th row on Clip 1 (which is both selected and active) must restore Clip 1's bridge
    // state after pushModelToBridge's all-clips loop writes Clip 0 into step.pattern.
    controller.handleStepToggled(0, 5);

    assertEquals(9, clip1.getRowCount());
    assertTrue(bridge.getStep(8, 5), "Clip 1 new 9th row note must be active in bridge");
    assertTrue(bridge.getStep(1, 3), "Clip 1 existing note must remain active in bridge");
    assertFalse(
        bridge.getStep(0, 0),
        "Clip 0 note at row 0 step 0 must not leak into Clip 1 primary bridge pattern");
  }

  @Test
  void arrangementViewFillerLaneDoesNotReturnNegativeEngineOffsetWhenEditedTrackHasFourDrums() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    KitTrackModel kit = new KitTrackModel("KIT_4");
    for (int i = 0; i < 4; i++) {
      kit.getDrums().add(new SoundDrum("DRUM_" + i));
    }
    ClipModel clip = new ClipModel("Kit Clip", 4, 16);
    clip.setIsKit(true);
    kit.addClip(clip);
    project.addTrack(kit);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ArrangerGridPanel arrPanel = new org.deluge.ui.ArrangerGridPanel(bridge);
    arrPanel.setEditedModelTrack(0);
    arrPanel.setBaseTrackId(0);
    arrPanel.setProjectModel(project);
    arrPanel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.ARRANGEMENT);

    // Filler lane 4 in ARRANGEMENT view must not be capped by editedModelTrack's 4 drum voices.
    assertDoesNotThrow(() -> arrPanel.refresh());
    assertDoesNotThrow(() -> arrPanel.updatePlayhead(0));
  }

  @Test
  void midiRecordingAtFullBridgeCapacityDoesNotThrowOrOverwritePreviousTrack() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth0 = new SynthTrackModel("SYNTH_80");
    ClipModel clip0 = new ClipModel("Clip 80", 80, 16);
    for (int r = 0; r < 80; r++) {
      clip0.setRowYNote(r, r);
    }
    clip0.setStep(79, 0, StepData.of(true, 0.9f, 1.0f, 1.0f, 79));
    synth0.addClip(clip0);
    project.addTrack(synth0);

    SynthTrackModel synth1 = new SynthTrackModel("SYNTH_48");
    ClipModel clip1 = new ClipModel("Clip 48", 48, 16);
    for (int r = 0; r < 48; r++) {
      clip1.setRowYNote(r, r);
    }
    synth1.addClip(clip1);
    project.addTrack(synth1);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.SwingDelugeApp oldApp = org.deluge.ui.SwingDelugeApp.mainInstance;
    boolean oldRecordMode = org.deluge.ui.SwingGridPanel.isLiveRecordModeActive;
    try {
      org.deluge.ui.EngineSyncCoordinator sync =
          new org.deluge.ui.EngineSyncCoordinator(null, bridge);
      sync.pushModelToBridge(project);
      assertEquals(80, sync.getTrackVoiceCount(0));
      assertEquals(48, sync.getTrackVoiceCount(1));

      org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
      panel.setScaleModeEnabled(false);
      panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
      panel.setEditedModelTrack(1);
      panel.setBaseTrackId(80);
      panel.setProjectModel(project);
      panel.updatePlayhead(0);

      sun.misc.Unsafe unsafe;
      java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      unsafe = (sun.misc.Unsafe) f.get(null);
      org.deluge.ui.SwingDelugeApp dummyApp =
          (org.deluge.ui.SwingDelugeApp)
              unsafe.allocateInstance(org.deluge.ui.SwingDelugeApp.class);
      java.lang.reflect.Field clipPanelField =
          org.deluge.ui.SwingDelugeApp.class.getDeclaredField("clipPanel");
      clipPanelField.setAccessible(true);
      clipPanelField.set(dummyApp, panel);
      org.deluge.ui.SwingDelugeApp.mainInstance = dummyApp;

      org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = true;
      bridge.setGlobalInt(org.deluge.BridgeContract.G_PLAY, 1L);

      org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);
      org.deluge.shadow.midi.MidiMsg noteOn = new org.deluge.shadow.midi.MidiMsg();
      noteOn.status = 0x90;
      noteOn.data1 = 0x90;
      noteOn.data2 = 100; // Missing pitch on Track 1 when all 128 bridge rows are full
      noteOn.data3 = 100;
      noteOn.channel = 0;

      assertDoesNotThrow(() -> router.handleMidiMessage(noteOn));
      assertEquals(48, clip1.getRowCount(), "Full-capacity MIDI note must not grow Clip 1");
      assertFalse(
          project.getUndoRedoStack().canUndo(), "Failed MIDI row allocation must not push undo");
      assertTrue(
          bridge.getStep(79, 0),
          "Preceding track's last bridge row (79) must remain active and untouched");
      assertEquals(0.9, bridge.getVelocity(79, 0), 0.001);
    } finally {
      org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = oldRecordMode;
      org.deluge.ui.SwingDelugeApp.mainInstance = oldApp;
    }
  }

  @Test
  void ninthSynthRowAllocationDoesNotMixClipZeroIntoOtherTracksNonZeroActiveClip() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};

    SynthTrackModel synth0 = new SynthTrackModel("TRACK_0");
    ClipModel t0Clip = new ClipModel("T0 Clip", 8, 16);
    for (int r = 0; r < 8; r++) {
      t0Clip.setRowYNote(r, cMajorPitches[r]);
    }
    synth0.addClip(t0Clip);
    project.addTrack(synth0);

    SynthTrackModel synth1 = new SynthTrackModel("TRACK_1_DUAL");
    ClipModel t1Clip0 = new ClipModel("T1 Clip 0", 8, 16);
    ClipModel t1Clip1 = new ClipModel("T1 Clip 1 Active", 8, 16);
    for (int r = 0; r < 8; r++) {
      t1Clip0.setRowYNote(r, cMajorPitches[r]);
      t1Clip1.setRowYNote(r, cMajorPitches[r]);
    }
    t1Clip0.setStep(0, 1, StepData.of(true, 0.9f, 1.0f, 1.0f, 60));
    t1Clip1.setStep(0, 4, StepData.of(true, 0.7f, 1.0f, 1.0f, 60));
    synth1.addClip(t1Clip0);
    synth1.addClip(t1Clip1);
    synth1.setActiveClipIndex(1);
    project.addTrack(synth1);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setProjectModel(project);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setScrollOffset(59);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Adding a 9th row on Track 0 triggers pushModelToBridge; Track 1 must keep only Clip 1 active.
    controller.handleStepToggled(0, 5);

    int track1Start = sync.getTrackEngineStart(1);
    assertEquals(9, track1Start);
    assertTrue(bridge.getStep(track1Start, 4), "Track 1 active Clip 1 step 4 must stay active");
    assertFalse(
        bridge.getStep(track1Start, 1),
        "Track 1 inactive Clip 0 step 1 must not leak into Track 1 primary bridge pattern");
  }

  @Test
  void ninthSynthRowAllocationPreservesTrackMuteKitRowMuteAndSoloState() {
    System.setProperty("chuck.audio.dummy", "true");
    org.deluge.ui.SwingGridPanel.soloedTracks.clear();
    try {
      ProjectModel project = new ProjectModel();
      int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};

      SynthTrackModel synth0 = new SynthTrackModel("TRACK_0");
      ClipModel t0Clip = new ClipModel("T0 Clip", 8, 16);
      for (int r = 0; r < 8; r++) {
        t0Clip.setRowYNote(r, cMajorPitches[r]);
      }
      synth0.addClip(t0Clip);
      project.addTrack(synth0);

      KitTrackModel kit1 = new KitTrackModel("MUTED_ROW_KIT");
      for (int i = 0; i < 4; i++) {
        kit1.getDrums().add(new SoundDrum("DRUM_" + i));
      }
      ClipModel kitClip = new ClipModel("Kit Clip", 4, 16);
      kitClip.setIsKit(true);
      kitClip.getOrCreateRow(1).setMuted(true);
      kit1.addClip(kitClip);
      project.addTrack(kit1);

      SynthTrackModel synth2 = new SynthTrackModel("MUTED_TRACK");
      synth2.setMuted(true);
      ClipModel t2Clip = new ClipModel("T2 Clip", 8, 16);
      for (int r = 0; r < 8; r++) {
        t2Clip.setRowYNote(r, cMajorPitches[r]);
      }
      synth2.addClip(t2Clip);
      project.addTrack(synth2);

      org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
      org.deluge.ui.EngineSyncCoordinator sync =
          new org.deluge.ui.EngineSyncCoordinator(null, bridge);
      sync.pushModelToBridge(project);

      org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
      panel.setProjectModel(project);
      panel.setEditedModelTrack(0);
      panel.setBaseTrackId(0);
      panel.setScrollOffset(59);

      org.deluge.ui.ClipEditorController controller =
          new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

      controller.handleStepToggled(0, 5);

      int kitStart = sync.getTrackEngineStart(1);
      int synth2Start = sync.getTrackEngineStart(2);
      assertEquals(9, kitStart);
      assertEquals(13, synth2Start);
      assertFalse(bridge.getMute(kitStart + 0), "Unmuted drum 0 must remain unmuted");
      assertTrue(bridge.getMute(kitStart + 1), "Muted drum 1 must stay muted after shift");
      assertTrue(bridge.getMute(synth2Start), "Muted Track 2 must stay muted after shift");
    } finally {
      org.deluge.ui.SwingGridPanel.soloedTracks.clear();
    }
  }

  @Test
  void ninthSynthRowAllocationClearsStaleZeroProbabilityFromReassignedRow() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};

    SynthTrackModel synth = new SynthTrackModel("SYNTH_8");
    ClipModel synthClip = new ClipModel("Synth Clip", 8, 16);
    for (int r = 0; r < 8; r++) {
      synthClip.setRowYNote(r, cMajorPitches[r]);
    }
    synth.addClip(synthClip);
    project.addTrack(synth);

    KitTrackModel kit = new KitTrackModel("NEXT_KIT");
    for (int i = 0; i < 4; i++) {
      kit.getDrums().add(new SoundDrum("DRUM_" + i));
    }
    ClipModel kitClip = new ClipModel("Kit Clip", 4, 16);
    kitClip.setIsKit(true);
    // Drum 0 (initially at engine row 8) has 0% probability and 20% velocity at step 5.
    kitClip.setStep(0, 5, StepData.of(true, 0.2f, 1.0f, 0.0f, 0));
    kit.addClip(kitClip);
    project.addTrack(kit);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    assertEquals(0.0, bridge.getStepProbability(8, 5), 0.001);
    assertEquals(0.2, bridge.getVelocity(8, 5), 0.001);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setProjectModel(project);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setScrollOffset(59);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});

    // Adding a 9th synth row at step 5 shifts the kit to row 9 and reassigns row 8 to the synth.
    controller.handleStepToggled(0, 5);

    StepData ninthStep = synthClip.getStep(8, 5);
    assertTrue(ninthStep.active());
    assertEquals(1.0f, ninthStep.probability(), 0.001f, "Reassigned row must default to 100% prob");
    assertEquals(0.8f, ninthStep.velocity(), 0.001f, "Reassigned row must default to 0.8 velocity");
    assertEquals(1.0, bridge.getStepProbability(8, 5), 0.001);
    assertEquals(0.0, bridge.getStepProbability(9, 5), 0.001, "Shifted kit row 9 keeps 0% prob");
  }

  @Test
  void bulkEditsOnSparseChromaticSynthClipSyncStoredRowZeroInsteadOfDisplayRow() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("SPARSE_BULK");
    ClipModel clip = new ClipModel("Sparse Clip", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 1, StepData.of(true, 0.75f, 1.0f, 0.9f, 60));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setProjectModel(project);

    // 1. Copy + Paste into a fresh 1-row sparse clip at MIDI 60 (display row 67, stored row 0).
    assertTrue(panel.copyClipNotes());
    clip.setStep(0, 1, StepData.empty());
    bridge.setStep(0, 1, false);
    assertTrue(panel.pasteClipNotes());
    assertTrue(bridge.getStep(0, 1), "pasteClipNotes must write to stored row 0");
    assertFalse(bridge.getStep(67, 1), "pasteClipNotes must not write to display row 67");

    // 2. duplicateTrackContent must copy step 1 to step 17 on stored row 0.
    panel.duplicateTrackContent();
    assertEquals(32, clip.getStepCount());
    assertTrue(bridge.getStep(0, 17), "duplicateTrackContent must write to stored row 0 step 17");
    assertFalse(bridge.getStep(67, 17), "duplicateTrackContent must not touch display row 67");

    // 3. adjustClipLength(-1) halves stepCount to 16 and clears stored row 0 step 17 in bridge.
    panel.adjustClipLength(-1);
    assertEquals(16, clip.getStepCount());
    assertFalse(bridge.getStep(0, 17), "adjustClipLength(-1) must clear stored row 0 step 17");

    // 4. transposeTrack(+2) moves MIDI 60 -> 62 on stored row 0 and updates bridge row 0.
    assertTrue(panel.transposeTrack(2));
    assertEquals(1, clip.getRowCount());
    assertEquals(62, clip.getRowYNote(0));
    assertTrue(bridge.getStep(0, 1), "transposeTrack must sync stored row 0");
    assertEquals(62, bridge.getPitch(0, 1));
    assertFalse(bridge.getStep(65, 1), "transposeTrack must not write to display row 65");
  }

  @Test
  void shiftClickOnMutePadClearsStoredClipRowAndAllocatedEngineRow() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("MUTE_CLEAR");
    ClipModel clip = new ClipModel("Sparse", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 2, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);
    bridge.setStep(67, 2, true); // sentinel on display row 67

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setProjectModel(project);
    panel.setScrollOffset(60); // visible row 7 -> modelRow 67 -> MIDI 60 -> stored row 0
    panel.rebuildUIComponents();

    javax.swing.JButton muteBtn = panel.getPads()[7][16];
    for (java.awt.event.ActionListener al : muteBtn.getActionListeners()) {
      al.actionPerformed(
          new java.awt.event.ActionEvent(
              muteBtn,
              java.awt.event.ActionEvent.ACTION_PERFORMED,
              "clear",
              java.awt.event.ActionEvent.SHIFT_MASK));
    }

    assertFalse(clip.getStep(0, 2).active(), "Shift-click mute must clear stored clip row 0");
    assertFalse(bridge.getStep(0, 2), "Shift-click mute must clear allocated engine row 0");
    assertTrue(bridge.getStep(67, 2), "Shift-click mute must not clear unrelated display row 67");
  }

  @Test
  void transposeTrackPreservesNoteRowSoundOverridesAutomationMuteAndRawNotes() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("TRANSPOSE_KEEP");
    ClipModel clip = new ClipModel("WithProps", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, StepData.of(true, 0.9f, 1.0f, 1.0f, 60));
    org.deluge.model.NoteRowModel rowModel = clip.getOrCreateRow(0);
    rowModel.setMuted(true);
    rowModel.setLoopLengthIfIndependent(192);
    rowModel.getNotes().add(new org.deluge.model.NoteModel(12, 12, 0.8f, 1.0f, 0));
    int expectedRawNotes = rowModel.getNotes().size();
    clip.setRowAutomation(0, "lpfFrequency", 3, 0.42f);
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setProjectModel(project);

    assertTrue(panel.transposeTrack(2));

    org.deluge.model.NoteRowModel after = clip.getNoteRowsMap().get(0);
    org.junit.jupiter.api.Assertions.assertNotNull(
        after, "Stored NoteRowModel 0 must remain attached after transposeTrack");
    assertEquals(62, after.getPitch(), "NoteRowModel pitch must move to 62");
    assertTrue(after.isMuted(), "NoteRowModel mute flag must be preserved");
    assertEquals(192, after.getLoopLengthIfIndependent(), "Independent loop length must stay 192");
    assertEquals(
        expectedRawNotes, after.getNotes().size(), "Raw NoteModel list must remain intact");
    assertEquals(
        0.42f,
        after.getRowAutomation().get("lpfFrequency")[3],
        1e-6f,
        "Per-row automation must be preserved");
    assertEquals(62, clip.getStep(0, 0).pitch());
  }

  @Test
  void midiInputRecordingMonitorsNoteImmediatelyAndReleasesOnNoteOff() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    org.deluge.project.PreferencesManager.set("midi.grid.mode", "false");
    org.deluge.ui.SwingDelugeApp oldApp = org.deluge.ui.SwingDelugeApp.mainInstance;
    boolean oldRecordMode = org.deluge.ui.SwingGridPanel.isLiveRecordModeActive;
    try {
      ProjectModel project = new ProjectModel();
      SynthTrackModel synth = new SynthTrackModel("MIDI_MON");
      ClipModel clip = new ClipModel("Clip", 8, 16);
      for (int r = 0; r < 8; r++) {
        clip.setRowYNote(r, 60 + r);
      }
      synth.addClip(clip);
      project.addTrack(synth);

      org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
      org.deluge.ui.EngineSyncCoordinator sync =
          new org.deluge.ui.EngineSyncCoordinator(null, bridge);
      sync.pushModelToBridge(project);

      org.deluge.engine.FirmwareAudioEngine fwEngine = new org.deluge.engine.FirmwareAudioEngine();
      final int[] triggered = {-1, -1};
      final int[] released = {-1};
      org.deluge.engine.FirmwareSound mockSound =
          new org.deluge.engine.FirmwareSound() {
            @Override
            public void triggerNote(int note, int velocity) {
              triggered[0] = note;
              triggered[1] = velocity;
            }

            @Override
            public void releaseNote(int note) {
              released[0] = note;
            }
          };
      fwEngine.sounds.add(mockSound);
      bridge.setGlobalObject(org.deluge.BridgeContract.G_FIRMWARE_ENGINE, fwEngine);

      org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
      panel.setScaleModeEnabled(false);
      panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
      panel.setEditedModelTrack(0);
      panel.setBaseTrackId(0);
      panel.setActiveClipId(0);
      panel.setProjectModel(project);
      panel.updatePlayhead(3);

      java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
      org.deluge.ui.SwingDelugeApp dummyApp =
          (org.deluge.ui.SwingDelugeApp)
              unsafe.allocateInstance(org.deluge.ui.SwingDelugeApp.class);
      java.lang.reflect.Field clipPanelField =
          org.deluge.ui.SwingDelugeApp.class.getDeclaredField("clipPanel");
      clipPanelField.setAccessible(true);
      clipPanelField.set(dummyApp, panel);
      java.lang.reflect.Field currentProjectField =
          org.deluge.ui.SwingDelugeApp.class.getDeclaredField("currentProject");
      currentProjectField.setAccessible(true);
      currentProjectField.set(dummyApp, project);
      org.deluge.ui.SwingDelugeApp.mainInstance = dummyApp;

      org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = true;
      bridge.setGlobalInt(org.deluge.BridgeContract.G_PLAY, 1L);

      org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);

      org.deluge.shadow.midi.MidiMsg noteOn = new org.deluge.shadow.midi.MidiMsg();
      noteOn.status = 0x90;
      noteOn.data1 = 0x90;
      noteOn.data2 = 64;
      noteOn.data3 = 100;
      router.handleMidiMessage(noteOn);

      assertEquals(
          64, triggered[0], "Recorded MIDI NoteOn must trigger live monitoring immediately");
      assertEquals(100, triggered[1]);
      assertTrue(clip.getStep(4, 3).active(), "Recorded MIDI NoteOn must write to stored row 4");

      org.deluge.shadow.midi.MidiMsg noteOff = new org.deluge.shadow.midi.MidiMsg();
      noteOff.status = 0x80;
      noteOff.data1 = 0x80;
      noteOff.data2 = 64;
      noteOff.data3 = 0;
      router.handleMidiMessage(noteOff);

      assertEquals(64, released[0], "MIDI NoteOff during recording must release monitored note");
    } finally {
      org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = oldRecordMode;
      org.deluge.ui.SwingDelugeApp.mainInstance = oldApp;
    }
  }

  @Test
  void pureFirmwareEngineSyncFromBridgeReadsTrackMuteFromAllocatedStartRow() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();

    SynthTrackModel synth0 = new SynthTrackModel("T0_MUTED");
    synth0.setMuted(true);
    ClipModel clip0 = new ClipModel("Clip 0", 8, 16);
    org.deluge.engine.FirmwareSound sound0 = new org.deluge.engine.FirmwareSound();
    clip0.setSound(sound0);
    synth0.addClip(clip0);
    project.addTrack(synth0);

    SynthTrackModel synth1 = new SynthTrackModel("T1_UNMUTED");
    synth1.setMuted(false);
    ClipModel clip1 = new ClipModel("Clip 1", 8, 16);
    org.deluge.engine.FirmwareSound sound1 = new org.deluge.engine.FirmwareSound();
    clip1.setSound(sound1);
    synth1.addClip(clip1);
    project.addTrack(synth1);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.engine.PureFirmwareEngine engine = new org.deluge.engine.PureFirmwareEngine();
    engine.setProject(project);
    engine.syncFromBridge(bridge);
    javax.swing.SwingUtilities.invokeAndWait(() -> {});

    assertTrue(sound0.muted, "Track 0 FirmwareSound must be muted from startRow 0");
    assertFalse(sound1.muted, "Track 1 FirmwareSound must read startRow 8 and stay unmuted");
  }

  @Test
  void pushModelToBridgeAndRestoreEditedClipDoNotMixStepAutomationAcrossClips() {
    System.setProperty("chuck.audio.dummy", "true");
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("AUTO_ISOLATION");
    int[] cMajorPitches = {60, 62, 64, 65, 67, 69, 71, 72};

    ClipModel clip0 = new ClipModel("Clip 0", 8, 16);
    ClipModel clip1 = new ClipModel("Clip 1 Active", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip0.setRowYNote(r, cMajorPitches[r]);
      clip1.setRowYNote(r, cMajorPitches[r]);
    }
    clip0.setAutomation(org.deluge.model.AutomationParam.A_LPF_FREQ, 2, 0.25f);
    clip1.setAutomation(org.deluge.model.AutomationParam.A_LPF_FREQ, 4, 0.85f);
    synth.addClip(clip0);
    synth.addClip(clip1);
    synth.setActiveClipIndex(1);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    assertEquals(
        0.0,
        bridge.getStepFilter(0, 2),
        1e-6,
        "Clip 0 automation at step 2 must not leak into Clip 1 primary stepFilter");
    assertEquals(
        0.85,
        bridge.getStepFilter(0, 4),
        1e-6,
        "Active Clip 1 automation at step 4 must be present in primary stepFilter");

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setViewMode(org.deluge.ui.SwingGridPanel.GridViewMode.CLIP);
    panel.setActiveClipId(1);
    panel.setProjectModel(project);
    panel.setScrollOffset(59);

    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});
    controller.handleStepToggled(0, 5); // allocates 9th row and calls restoreEditedClipInBridge()

    assertEquals(0.0, bridge.getStepFilter(0, 2), 1e-6);
    assertEquals(0.85, bridge.getStepFilter(0, 4), 1e-6);
  }

  @Test
  void midiInputKitNoteOffReleasesDrumVoiceAtNote60() {
    System.setProperty("chuck.audio.dummy", "true");
    org.deluge.project.PreferencesManager.set("midi.grid.mode", "false");
    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();

    org.deluge.engine.FirmwareAudioEngine fwEngine = new org.deluge.engine.FirmwareAudioEngine();
    final int[] triggered = {-1, -1};
    final int[] released = {-1};
    org.deluge.engine.FirmwareKit mockKit = new org.deluge.engine.FirmwareKit();
    org.deluge.engine.FirmwareSound mockDrum2 =
        new org.deluge.engine.FirmwareSound() {
          @Override
          public void triggerNote(int note, int velocity) {
            triggered[0] = note;
            triggered[1] = velocity;
          }

          @Override
          public void releaseNote(int note) {
            released[0] = note;
          }
        };
    mockKit.drumSounds.set(2, mockDrum2);
    fwEngine.sounds.add(mockKit);
    bridge.setGlobalObject(org.deluge.BridgeContract.G_FIRMWARE_ENGINE, fwEngine);

    org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);
    router.setActiveTrack(0);

    org.deluge.shadow.midi.MidiMsg noteOn = new org.deluge.shadow.midi.MidiMsg();
    noteOn.status = 0x90;
    noteOn.data1 = 0x90;
    noteOn.data2 = 38; // drum 2 (38 - 36)
    noteOn.data3 = 110;
    router.handleMidiMessage(noteOn);

    assertEquals(60, triggered[0], "Kit MIDI NoteOn must trigger drum 2 at note 60");
    assertEquals(110, triggered[1]);

    org.deluge.shadow.midi.MidiMsg noteOff = new org.deluge.shadow.midi.MidiMsg();
    noteOff.status = 0x80;
    noteOff.data1 = 0x80;
    noteOff.data2 = 38;
    noteOff.data3 = 0;
    router.handleMidiMessage(noteOff);

    assertEquals(60, released[0], "Kit MIDI NoteOff must release drum 2 at note 60");
  }

  @Test
  void keyboardMouseAdapterArmedStepDoesNotMarkOrdinaryNotesAsFillOnly() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    int oldLockTrack = org.deluge.ui.SwingGridPanel.lockArmedTrack;
    int oldLockStep = org.deluge.ui.SwingGridPanel.lockArmedStep;
    try {
      ProjectModel project = new ProjectModel();
      SynthTrackModel synth = new SynthTrackModel("KEY_ARM");
      ClipModel clip = new ClipModel("Clip", 1, 16);
      clip.setRowYNote(0, 60);
      synth.addClip(clip);
      project.addTrack(synth);

      org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
      org.deluge.ui.EngineSyncCoordinator sync =
          new org.deluge.ui.EngineSyncCoordinator(null, bridge);
      sync.pushModelToBridge(project);

      org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
      panel.setScaleModeEnabled(false);
      panel.setEditedModelTrack(0);
      panel.setBaseTrackId(0);
      panel.setProjectModel(project);

      org.deluge.ui.SwingGridPanel.lockArmedTrack = 0;
      org.deluge.ui.SwingGridPanel.lockArmedStep = 2;

      Class<?> adapterClass = Class.forName("org.deluge.ui.KeyboardMouseAdapter");
      java.lang.reflect.Constructor<?> ctor =
          adapterClass.getDeclaredConstructor(org.deluge.ui.SwingGridPanel.class, int.class);
      ctor.setAccessible(true);
      java.awt.event.MouseAdapter adapter =
          (java.awt.event.MouseAdapter) ctor.newInstance(panel, 60);

      javax.swing.JButton dummyBtn = new javax.swing.JButton();
      dummyBtn.setSize(40, 40);
      adapter.mousePressed(
          new java.awt.event.MouseEvent(
              dummyBtn,
              java.awt.event.MouseEvent.MOUSE_PRESSED,
              System.currentTimeMillis(),
              0,
              10,
              10,
              1,
              false,
              java.awt.event.MouseEvent.BUTTON1));

      StepData step = clip.getStep(0, 2);
      assertTrue(step.active(), "Armed-step keyboard press must activate step");
      assertEquals(0.0f, step.fill(), 1e-6f, "Ordinary keyboard step must have zero fill");
      assertEquals(0, step.fillMode(), "Ordinary keyboard step must have fillMode OFF (0)");
    } finally {
      org.deluge.ui.SwingGridPanel.lockArmedTrack = oldLockTrack;
      org.deluge.ui.SwingGridPanel.lockArmedStep = oldLockStep;
    }
  }

  @Test
  void resolveTrackEngineStartReturnsMinusOneForZeroVoiceMidiTrack() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synthA = new SynthTrackModel("SynthA");
    synthA.addClip(new ClipModel("ClipA", 8, 16));
    org.deluge.model.MidiTrackModel midi = new org.deluge.model.MidiTrackModel("Midi1");
    midi.addClip(new ClipModel("MidiClip", 8, 16));
    SynthTrackModel synthB = new SynthTrackModel("SynthB");
    synthB.addClip(new ClipModel("ClipB", 8, 16));
    project.addTrack(synthA);
    project.addTrack(midi);
    project.addTrack(synthB);

    // Fallback layout path (bridge == null)
    assertEquals(0, org.deluge.ui.EngineSyncCoordinator.resolveTrackEngineStart(null, project, 0));
    assertEquals(
        -1,
        org.deluge.ui.EngineSyncCoordinator.resolveTrackEngineStart(null, project, 1),
        "Zero-voice MIDI track must resolve to -1 in fallback layout path");
    assertEquals(8, org.deluge.ui.EngineSyncCoordinator.resolveTrackEngineStart(null, project, 2));

    // Live coordinator path
    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    assertEquals(
        0, org.deluge.ui.EngineSyncCoordinator.resolveTrackEngineStart(bridge, project, 0));
    assertEquals(
        -1,
        org.deluge.ui.EngineSyncCoordinator.resolveTrackEngineStart(bridge, project, 1),
        "Zero-voice MIDI track must resolve to -1 in live coordinator path");
    assertEquals(
        8, org.deluge.ui.EngineSyncCoordinator.resolveTrackEngineStart(bridge, project, 2));
  }

  @Test
  void rotateRowPreservesOffGridRawNotesWithoutDuplicatingEvents() throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("RotSynth");
    ClipModel clip = new ClipModel("Clip", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    org.deluge.model.NoteRowModel row = clip.getNoteRowsMap().get(0);
    row.getNotes().clear();
    org.deluge.model.NoteModel offGridNote = new org.deluge.model.NoteModel();
    offGridNote.setPos(12);
    offGridNote.setLength(18);
    offGridNote.setVelocity(100);
    offGridNote.setProbability(100);
    offGridNote.setLift(73);
    row.getNotes().add(offGridNote);
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setActiveClipId(0);
    panel.setProjectModel(project);

    java.lang.reflect.Method m =
        org.deluge.ui.ClipGridPanel.class.getDeclaredMethod("rotateRow", int.class, boolean.class);
    m.setAccessible(true);
    m.invoke(panel, 127 - 60, true);

    assertEquals(1, row.getNotes().size(), "Rotating off-grid note must not create duplicate note");
    assertEquals(36, row.getNotes().get(0).getPos(), "Off-grid note must shift from tick 12 to 36");
    assertEquals(73, row.getNotes().get(0).getLift(), "Note lift property must be preserved");
    assertTrue(!clip.getStep(0, 0).active(), "Grid step 0 must clear after rotating right");
    assertTrue(clip.getStep(0, 1).active(), "Grid step 1 must activate after rotating right");
  }

  @Test
  void transposeRowPreservesOffGridRawNotesWithoutDuplicatingEvents() throws Exception {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("TrspSynth");
    ClipModel clip = new ClipModel("Clip", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    org.deluge.model.NoteRowModel row = clip.getNoteRowsMap().get(0);
    row.getNotes().clear();
    org.deluge.model.NoteModel offGridNote = new org.deluge.model.NoteModel();
    offGridNote.setPos(12);
    offGridNote.setLength(18);
    offGridNote.setVelocity(100);
    offGridNote.setProbability(100);
    row.getNotes().add(offGridNote);
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setActiveClipId(0);
    panel.setProjectModel(project);

    java.lang.reflect.Method m =
        org.deluge.ui.ClipGridPanel.class.getDeclaredMethod(
            "transposeRow", int.class, boolean.class);
    m.setAccessible(true);
    m.invoke(panel, 127 - 60, true);

    assertEquals(
        1, row.getNotes().size(), "Transposing off-grid note must not create duplicate note");
    assertEquals(12, row.getNotes().get(0).getPos(), "Off-grid note timing must remain at tick 12");
    assertEquals(61, row.getPitch(), "NoteRowModel pitch must move to 61");
    assertEquals(61, clip.getStep(0, 0).pitch(), "Grid step pitch must move to 61");
  }

  @Test
  void switchToTrackEditSelectsOverflowedTrackEvenWhenEngineBaseIsMinusOne() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract(44100, 2);
    org.deluge.ui.SwingDelugeApp app = new org.deluge.ui.SwingDelugeApp(bridge, null);
    try {
      ProjectModel project = new ProjectModel();
      // Fill 128 bridge rows with 16 x 8-voice synth tracks
      for (int i = 0; i < 16; i++) {
        SynthTrackModel s = new SynthTrackModel("Synth" + i);
        s.addClip(new ClipModel("Clip" + i, 8, 16));
        project.addTrack(s);
      }
      // Track 16 overflows the 128-row bridge limit (engineStart == -1)
      SynthTrackModel overflow = new SynthTrackModel("OverflowSynth");
      overflow.addClip(new ClipModel("OverflowClip0", 8, 16));
      overflow.addClip(new ClipModel("OverflowClip1", 8, 16));
      project.addTrack(overflow);

      app.loadProject(project);
      assertEquals(-1, app.getSyncCoordinator().getTrackEngineStart(16));

      app.switchToTrackEdit(16, 1);
      org.deluge.ui.SwingGridPanel clipPanel = app.getClipPanel();
      assertEquals(
          16, clipPanel.getEditedModelTrack(), "switchToTrackEdit must select overflowed track 16");
      assertEquals(1, clipPanel.getActiveClipId(), "switchToTrackEdit must select clip 1");
      assertEquals(
          -1,
          clipPanel.getBaseTrackId(),
          "Overflowed track must record sentinel baseTrackId -1 without failing");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  void switchToTrackEditSetsMinusOneBaseTrackIdForZeroVoiceMidiTrackFollowedBySynth()
      throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract(44100, 2);
    org.deluge.ui.SwingDelugeApp app = new org.deluge.ui.SwingDelugeApp(bridge, null);
    try {
      ProjectModel project = new ProjectModel();
      org.deluge.model.MidiTrackModel midi = new org.deluge.model.MidiTrackModel("Midi0");
      midi.addClip(new ClipModel("MidiClip", 8, 24));
      SynthTrackModel synth = new SynthTrackModel("Synth1");
      synth.addClip(new ClipModel("SynthClip", 8, 16));
      project.addTrack(midi);
      project.addTrack(synth);

      app.loadProject(project);
      app.switchToTrackEdit(0, 0);

      org.deluge.ui.SwingGridPanel clipPanel = app.getClipPanel();
      assertEquals(0, clipPanel.getEditedModelTrack());
      assertEquals(
          -1,
          clipPanel.getBaseTrackId(),
          "Zero-voice MIDI track must have baseTrackId -1 so it does not read following synth row");
      assertEquals(
          24,
          clipPanel.getTrackLength(),
          "MIDI track length must come from its own clip (24) instead of following synth (16)");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }

  @Test
  void duplicateStepPersistsClonedAutomationAndRestoresItOnResyncAndUndo() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("AutoCloneSynth");
    ClipModel clip = new ClipModel("Clip", 8, 16);
    for (int r = 0; r < 8; r++) {
      clip.setRowYNote(r, 60 + r);
    }
    clip.setStep(0, 1, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    clip.setAutomation(org.deluge.model.AutomationParam.A_LPF_FREQ, 1, 0.72f);
    clip.setAutomation(org.deluge.model.AutomationParam.A_DELAY, 1, 0.35f);
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setActiveClipId(0);
    panel.setProjectModel(project);

    // Clone from pitch 60 (modelRow 67, stored row 0) step 1 to pitch 63 (modelRow 64, stored row
    // 3) step 5
    int srcVisibleRow = (127 - 60) - panel.getScrollOffset();
    int dstVisibleRow = (127 - 63) - panel.getScrollOffset();
    org.deluge.ui.ClipEditorController controller =
        new org.deluge.ui.ClipEditorController(panel, () -> {}, () -> {});
    controller.duplicateStep(srcVisibleRow, 1, dstVisibleRow, 5);

    assertEquals(
        0.72f,
        clip.getRowAutomationValue(3, org.deluge.model.AutomationParam.A_LPF_FREQ, 5),
        1e-6f,
        "Cloned step filter automation must persist on destination row 3");
    assertEquals(
        0.35f,
        clip.getRowAutomationValue(3, org.deluge.model.AutomationParam.A_DELAY, 5),
        1e-6f,
        "Cloned step delay automation must persist on destination row 3");

    // Re-sync model to bridge (clears all rows then restores from model)
    sync.pushModelToBridge(project);
    assertEquals(
        0.72,
        bridge.getStepFilter(3, 5),
        1e-5,
        "pushModelToBridge must restore cloned filter automation to destination row 3");
    assertEquals(
        0.35,
        bridge.getStepDelay(3, 5),
        1e-5,
        "pushModelToBridge must restore cloned delay automation to destination row 3");

    // Undo must clear both step and cloned row automation
    project.getUndoRedoStack().undo();
    assertEquals(
        -1f,
        clip.getRowAutomationValue(3, org.deluge.model.AutomationParam.A_LPF_FREQ, 5),
        1e-6f,
        "Undo must restore destination row automation to unset (-1)");
  }

  @Test
  void refreshInPlaceShowsUnmuteLabelOnAllRowsOfMutedSynthTrack() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("MutedSynth");
    synth.setMuted(true);
    ClipModel clip = new ClipModel("Clip", 1, 16);
    clip.setRowYNote(0, 60);
    clip.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    synth.addClip(clip);
    project.addTrack(synth);

    org.deluge.BridgeContract bridge = new org.deluge.BridgeContract();
    org.deluge.ui.EngineSyncCoordinator sync =
        new org.deluge.ui.EngineSyncCoordinator(null, bridge);
    sync.pushModelToBridge(project);

    org.deluge.ui.ClipGridPanel panel = new org.deluge.ui.ClipGridPanel(bridge);
    panel.setScaleModeEnabled(false);
    panel.setEditedModelTrack(0);
    panel.setBaseTrackId(0);
    panel.setActiveClipId(0);
    panel.setProjectModel(project);
    panel.refreshInPlace();

    javax.swing.JButton[][] pads = panel.getPads();
    for (int v = 0; v < pads.length; v++) {
      if (pads[v][16] != null) {
        assertEquals(
            "UNMUTE",
            pads[v][16].getText(),
            "All visible rows of a muted synth track must show UNMUTE at row " + v);
      }
    }
  }
}
