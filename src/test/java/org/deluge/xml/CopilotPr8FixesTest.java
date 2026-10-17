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
}
