package org.deluge.xml;

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
}
