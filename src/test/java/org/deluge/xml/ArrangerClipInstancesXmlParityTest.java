package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.deluge.model.ArrangerClip;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.Drum;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ExportHelper;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies C++ firmware parity for Arranger {@code clipInstances} / {@code trackInstances}
 * encoding/decoding ({@code output.cpp:279-304, 352, 356, 398-402}, {@code song.cpp:1149,
 * 1313-1332, 2088-2094, 2131-2258, 5106-5117}), default clip {@code section = 0} & {@code
 * kMaxNumSections = 24} clamping ({@code clip.cpp:51, 204-206, 666-719}, {@code
 * definitions_cxx.hpp:450}), {@code <audioClip>} session + arrangement-only serialization ({@code
 * audio_clip.cpp:1057-1095, 1172-1212}), MIDI export null-backing safety ({@code ExportHelper}),
 * and {@code ArrangementProjector} audio-clip rendering ({@code clip_instance.cpp:31-37}, {@code
 * arranger_view.cpp:2375-2439}).
 */
class ArrangerClipInstancesXmlParityTest {

  @Test
  void newlyCreatedSessionClipsDefaultToSectionZeroAndRoundTripArrangerPlacements()
      throws Exception {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

    SynthTrackModel lead = new SynthTrackModel("Lead");
    ClipModel sessionClip = new ClipModel("LeadSess", 8, 16);
    assertEquals(0, sessionClip.getSection(), "C clip.cpp:51 initializes section = 0");
    sessionClip.setStep(0, 0, StepData.of(true, 1.0f, 4.0f, 1.0f, 60));
    lead.addClip(sessionClip);

    ClipModel arrOnlyClip = new ClipModel("LeadArr", 8, 16);
    arrOnlyClip.setArrangementOnly(true);
    assertEquals(255, arrOnlyClip.getSection(), "C clip.cpp:204-206, song.cpp:2135 section == 255");
    arrOnlyClip.setStep(0, 4, StepData.of(true, 1.0f, 4.0f, 1.0f, 67));
    lead.addClip(arrOnlyClip);

    ClipModel orphanArrClip = new ClipModel("OrphanArr", 8, 16);
    orphanArrClip.setArrangementOnly(true);
    lead.addClip(orphanArrClip);

    project.addTrack(lead);
    project.addArrangerClip(new ArrangerClip(0, sessionClip, 0, 384));
    project.addArrangerClip(new ArrangerClip(0, arrOnlyClip, 384, 384));
    project.addArrangerClip(new ArrangerClip(0, null, null, 768, 192));

    String xml = ProjectSerializer.serializeToString(project);
    // Session clip index 0 -> 00000000; arrangement-only clip index 0 -> 80000000; null -> FFFFFFFF
    assertTrue(
        xml.contains(
            "clipInstances=\"0x00000000000001800000000000000180000001808000000000000300000000C0FFFFFFFF\""),
        "Expected exact C output.cpp:279-304 hex encoding in:\n" + xml);
    assertFalse(
        xml.contains("clipName=\"OrphanArr\""),
        "C song.cpp:1325 skips orphan arrangement-only clips with no ClipInstance");

    ProjectModel reparsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "ArrSong");
    List<ArrangerClip> timeline = reparsed.getArrangerTimeline();
    assertEquals(3, timeline.size(), "All 3 arranger instances (session, arr-only, null) restored");

    ArrangerClip first = timeline.get(0);
    assertNotNull(first.clip());
    assertFalse(first.clip().isArrangementOnly());
    assertEquals("LeadSess", first.clip().getName());
    assertEquals(0, first.clip().getSection());
    assertEquals(0, first.startTicks());
    assertEquals(384, first.durationTicks());

    ArrangerClip second = timeline.get(1);
    assertNotNull(second.clip());
    assertTrue(second.clip().isArrangementOnly());
    assertEquals("LeadArr", second.clip().getName());
    assertEquals(255, second.clip().getSection());
    assertEquals(384, second.startTicks());
    assertEquals(384, second.durationTicks());

    ArrangerClip third = timeline.get(2);
    assertNull(third.clip());
    assertNull(third.audioClip());
    assertEquals(768, third.startTicks());
    assertEquals(192, third.durationTicks());
  }

  @Test
  void heterogeneousSynthAudioKitSessionAndArrangementClipsRoundTripAndProjectorAndMidiExportWork(
      @TempDir Path tempDir) throws Exception {
    ProjectModel project = new ProjectModel();
    project.setBpm(124.0f);

    // Track 0: Synth (session idx 0, arrangement idx 0)
    SynthTrackModel synth = new SynthTrackModel("Bass");
    ClipModel synthSess = new ClipModel("BassSess", 8, 16);
    synthSess.setSection(3);
    synthSess.setStep(0, 0, StepData.of(true, 0.9f, 2.0f, 1.0f, 48));
    synth.addClip(synthSess);
    ClipModel synthArr = new ClipModel("BassArr", 8, 16);
    synthArr.setArrangementOnly(true);
    synthArr.setPlayDirection(ClipModel.PlayDirection.REVERSE);
    synthArr.setStep(0, 2, StepData.of(true, 0.9f, 2.0f, 1.0f, 55));
    synth.addClip(synthArr);
    project.addTrack(synth);

    // Track 1: AudioTrack (session idx 1, arrangement idx 1)
    AudioTrackModel audioTrack = new AudioTrackModel("Vox");
    AudioTrackModel.AudioClip audioSess = new AudioTrackModel.AudioClip();
    audioSess.setClipName("VoxHook");
    audioSess.setFilePath("SAMPLES/CLIPS/VOX1.WAV");
    audioSess.setLength(192);
    audioSess.setSection(5);
    audioSess.setTranspose(-2);
    audioSess.setCents(15);
    audioSess.setPitchSpeedIndependent(true);
    audioSess.setSequenceDirection(ClipModel.PlayDirection.PING_PONG);
    audioTrack.addAudioClip(audioSess);

    AudioTrackModel.AudioClip audioArr = new AudioTrackModel.AudioClip();
    audioArr.setClipName("VoxAdlib");
    audioArr.setFilePath("SAMPLES/CLIPS/VOX2.WAV");
    audioArr.setLength(96);
    audioArr.setArrangementOnly(true);
    audioArr.setTranspose(7);
    audioArr.setCents(-10);
    audioTrack.addAudioClip(audioArr);
    project.addTrack(audioTrack);

    // Track 2: Kit (session idx 2, arrangement idx 2) — placed AFTER AudioTrack to test interleaved
    // <instrumentClip> + <audioClip> indexing in both <sessionClips> and <arrangementOnlyTracks>!
    KitTrackModel kit = new KitTrackModel("Drums");
    List<Drum> drums = kit.getDrums();
    drums.add(new SoundDrum("Kick"));
    ClipModel kitSess = new ClipModel("DrumSess", 1, 16);
    kitSess.setIsKit(true);
    kitSess.setSection(23);
    kitSess.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
    kit.addClip(kitSess);
    ClipModel kitArr = new ClipModel("DrumFill", 1, 16);
    kitArr.setIsKit(true);
    kitArr.setArrangementOnly(true);
    kitArr.setStep(0, 8, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
    kit.addClip(kitArr);
    project.addTrack(kit);

    project.addArrangerClip(new ArrangerClip(0, synthSess, 0, 384));
    project.addArrangerClip(new ArrangerClip(0, synthArr, 384, 384));
    project.addArrangerClip(new ArrangerClip(1, null, audioSess, 0, 384));
    project.addArrangerClip(new ArrangerClip(1, null, audioArr, 384, 192));
    project.addArrangerClip(new ArrangerClip(2, kitSess, 0, 384));
    project.addArrangerClip(new ArrangerClip(2, kitArr, 384, 384));
    project.addArrangerClip(new ArrangerClip(2, null, null, 768, 96));

    File xmlFile = tempDir.resolve("heterogeneous_arranger.xml").toFile();
    ProjectSerializer.save(project, xmlFile);
    ProjectModel reparsed = SongXmlParser.parseSong(xmlFile);

    assertEquals(7, reparsed.getArrangerTimeline().size());

    ArrangerClip reAudioSessAc = reparsed.getArrangerTimeline().get(2);
    assertEquals(1, reAudioSessAc.trackIndex());
    assertNull(reAudioSessAc.clip());
    assertNotNull(reAudioSessAc.audioClip());
    assertEquals("VoxHook", reAudioSessAc.audioClip().getClipName());
    assertFalse(reAudioSessAc.audioClip().isArrangementOnly());
    assertEquals(5, reAudioSessAc.audioClip().getSection());
    assertEquals(-2, reAudioSessAc.audioClip().getTranspose());
    assertEquals(15, reAudioSessAc.audioClip().getCents());
    assertTrue(reAudioSessAc.audioClip().isPitchSpeedIndependent());
    assertEquals(
        ClipModel.PlayDirection.PING_PONG, reAudioSessAc.audioClip().getSequenceDirection());

    ArrangerClip reAudioArrAc = reparsed.getArrangerTimeline().get(3);
    assertEquals(1, reAudioArrAc.trackIndex());
    assertNull(reAudioArrAc.clip());
    assertNotNull(reAudioArrAc.audioClip());
    assertEquals("VoxAdlib", reAudioArrAc.audioClip().getClipName());
    assertTrue(reAudioArrAc.audioClip().isArrangementOnly());
    assertEquals(255, reAudioArrAc.audioClip().getSection());
    assertEquals(7, reAudioArrAc.audioClip().getTranspose());
    assertEquals(-10, reAudioArrAc.audioClip().getCents());

    ArrangerClip reSynthArrAc = reparsed.getArrangerTimeline().get(1);
    assertEquals(ClipModel.PlayDirection.REVERSE, reSynthArrAc.clip().getPlayDirection());

    ArrangerClip reKitSessAc = reparsed.getArrangerTimeline().get(4);
    assertEquals("DrumSess", reKitSessAc.clip().getName());
    assertEquals(23, reKitSessAc.clip().getSection());

    ArrangerClip reKitArrAc = reparsed.getArrangerTimeline().get(5);
    assertEquals("DrumFill", reKitArrAc.clip().getName());
    assertTrue(reKitArrAc.clip().isArrangementOnly());

    // Verify ExportHelper.exportMidi handles AudioClip + 0xFFFFFFFF ArrangerClips cleanly.
    File midiFile = tempDir.resolve("export_test.mid").toFile();
    ExportHelper.exportMidi(reparsed, midiFile);
    assertTrue(midiFile.exists() && midiFile.length() > 0);
  }

  @Test
  void hardwareValidationGuardsOutOfBoundsWrongOutputDuplicateArrOnlyAndSectionClamp()
      throws Exception {
    String hardwareXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0" inputTickMagnitude="1">
          <instruments>
            <sound presetName="Lead" presetFolder="SYNTHS"
                clipInstances="0x000000000000006000000000000000600000006000000001000000C00000006000000009000001200000006080000000000001800000006080000000000001E000000060FFFFFFFF" />
            <sound presetName="Pad" presetFolder="SYNTHS"
                clipInstances="0x000000000000006000000001000000600000006080000000" />
          </instruments>
          <sessionClips>
            <instrumentClip clipName="LeadS0" instrumentPresetName="Lead" instrumentPresetFolder="SYNTHS"
                length="96" section="99" />
            <instrumentClip clipName="PadS1" instrumentPresetName="Pad" instrumentPresetFolder="SYNTHS"
                length="96" section="15" />
          </sessionClips>
          <arrangementOnlyClips>
            <instrumentClip clipName="LeadArr0" instrumentPresetName="Lead" instrumentPresetFolder="SYNTHS"
                length="96" section="4" />
          </arrangementOnlyClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(hardwareXml.getBytes(StandardCharsets.UTF_8)), "Guards");

    // LeadS0 section="99" clamped to kMaxNumSections - 1 = 23 (clip.cpp:716-719); PadS1 stays 15
    assertEquals(23, parsed.getTracks().get(0).getClips().get(0).getSection());
    assertEquals(15, parsed.getTracks().get(1).getClips().get(0).getSection());
    // LeadArr0 section="4" forced to 255 on arrangementOnlyClips load (song.cpp:2131-2136)
    assertEquals(255, parsed.getTracks().get(0).getClips().get(1).getSection());

    // Expected surviving instances:
    // Track 0:
    //  - pos 0x000 -> session[0] (LeadS0): VALID
    //  - pos 0x060 -> session[1] (PadS1 belongs to Pad, not Lead): SKIPPED (E451)
    //  - pos 0x0C0 -> session[9] (out of bounds): SKIPPED (E248)
    //  - pos 0x120 -> arrOnly[0] (LeadArr0 first claim): VALID
    //  - pos 0x180 -> arrOnly[0] (LeadArr0 duplicate claim): SKIPPED (E042)
    //  - pos 0x1E0 -> 0xFFFFFFFF (unbacked): VALID
    // Track 1:
    //  - pos 0x000 -> session[1] (PadS1): VALID
    //  - pos 0x060 -> arrOnly[0] (LeadArr0 belongs to Lead + already claimed): SKIPPED (E451/E042)
    List<ArrangerClip> tl = parsed.getArrangerTimeline();
    assertEquals(4, tl.size());
    assertEquals(0, tl.get(0).startTicks());
    assertEquals("LeadS0", tl.get(0).clip().getName());
    assertEquals(0x120, tl.get(1).startTicks());
    assertEquals("LeadArr0", tl.get(1).clip().getName());
    assertEquals(0x1E0, tl.get(2).startTicks());
    assertNull(tl.get(2).clip());
    assertEquals(1, tl.get(3).trackIndex());
    assertEquals("PadS1", tl.get(3).clip().getName());
  }

  @Test
  void unsortedTimelineSerializesMonotonicallyAndParserGuardsMinPosOverflowCvChannelAndLegacyTag()
      throws Exception {
    // 1. Out-of-order insertion in ProjectModel must serialize strictly sorted by startTicks
    //    (C output.cpp:356, 398-402 requires monotonic non-overlapping pos >= minPos).
    ProjectModel unsorted = new ProjectModel();
    SynthTrackModel lead = new SynthTrackModel("Lead");
    ClipModel s0 = new ClipModel("Bar1Clip", 8, 16);
    ClipModel arr0 = new ClipModel("Bar4ArrClip", 8, 16);
    arr0.setArrangementOnly(true);
    lead.addClip(s0);
    lead.addClip(arr0);
    unsorted.addTrack(lead);
    // Insert Bar 4 BEFORE Bar 1 to mimic dragging an arranged clip earlier in UI:
    unsorted.addArrangerClip(new ArrangerClip(0, arr0, 384, 192));
    unsorted.addArrangerClip(new ArrangerClip(0, s0, 0, 192));

    String sortedXml = ProjectSerializer.serializeToString(unsorted);
    ProjectModel reloadedUnsorted =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(sortedXml.getBytes(StandardCharsets.UTF_8)), "SortedReload");
    assertEquals(2, reloadedUnsorted.getArrangerTimeline().size());
    assertEquals(0, reloadedUnsorted.getArrangerTimeline().get(0).startTicks());
    assertEquals("Bar1Clip", reloadedUnsorted.getArrangerTimeline().get(0).clip().getName());
    assertEquals(384, reloadedUnsorted.getArrangerTimeline().get(1).startTicks());
    assertEquals("Bar4ArrClip", reloadedUnsorted.getArrangerTimeline().get(1).clip().getName());

    // 2. <cvChannel> inside <instruments> before <sound>, legacy trackInstances attribute
    //    (output.cpp:352), and output.cpp:398-402 guards (length == 0, pos + length overflow,
    //    and overlapping pos < minPos) rejected BEFORE claiming arrangementOnlyClips[0] (E042).
    //    Entries on Lead:
    //      a) pos=0x00000000, len=0x00000000, code=0x80000000 -> length <= 0 -> skipped BEFORE E042
    //      b) pos=0x7FFFFFF0, len=0x00000020, code=0x80000000 -> pos > MAX - len -> skipped
    //      c) pos=0x00000060, len=0x00000060, code=0x00000000 -> valid ([0x60, 0xC0)), minPos=0xC0
    //      d) pos=0x00000090, len=0x00000060, code=0x80000000 -> pos < minPos -> skipped BEFORE
    // E042
    //      e) pos=0x000000C0, len=0x00000060, code=0x80000000 -> valid claim of Arr0!
    String edgeXml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0" inputTickMagnitude="1">
          <instruments>
            <cvChannel channel="0" />
            <sound presetName="Lead" presetFolder="SYNTHS"
                trackInstances="0x0000000000000000800000007FFFFFF00000002080000000000000600000006000000000000000900000006080000000000000C00000006080000000" />
          </instruments>
          <sessionClips>
            <instrumentClip clipName="LeadSess" instrumentPresetName="Lead" instrumentPresetFolder="SYNTHS"
                length="96" section="2" />
          </sessionClips>
          <arrangementOnlyClips>
            <instrumentClip clipName="LeadArr" instrumentPresetName="Lead" instrumentPresetFolder="SYNTHS"
                length="96" />
          </arrangementOnlyClips>
        </song>
        """;

    ProjectModel edgeParsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(edgeXml.getBytes(StandardCharsets.UTF_8)), "EdgeGuards");
    assertEquals(1, edgeParsed.getTracks().size(), "<cvChannel> does not create a TrackModel");
    List<ArrangerClip> edgeTl = edgeParsed.getArrangerTimeline();
    assertEquals(2, edgeTl.size());
    assertEquals(0x60, edgeTl.get(0).startTicks());
    assertEquals("LeadSess", edgeTl.get(0).clip().getName());
    assertEquals(0xC0, edgeTl.get(1).startTicks());
    assertEquals("LeadArr", edgeTl.get(1).clip().getName());
  }

  @Test
  void sameNamedTracksDualMidiCvClipsCaselessAudioTrackAndSessionMidiExportIsolation(
      @TempDir Path tempDir) throws Exception {
    // Same-named <audioTrack>, <kit>, and <sound> ("Lead"), plus two <midi> tracks sharing
    // name "MIDI" on channels 0 and 1, plus CV clips in both <sessionClips> and
    // <arrangementOnlyClips>, plus caseless <audioClip trackName="lead"> and an unmatched
    // <audioClip trackName="MissingAudio"> that must NOT steal "Lead".
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song firmwareVersion="4.1.0" inputTickMagnitude="1">
          <instruments>
            <cvChannel channel="0" />
            <audioTrack name="Lead"
                clipInstances="0x000000000000006000000002000000600000006080000001" />
            <kit presetName="Lead" presetFolder="KITS"
                clipInstances="0x000000000000006080000002">
              <soundSources><sound name="Kick"><osc1 type="square" /></sound></soundSources>
            </kit>
            <sound presetName="Lead" presetFolder="SYNTHS"
                clipInstances="0x000000000000006000000003000000600000006080000003" />
            <midi name="MIDI" channel="0"
                clipInstances="0x000000000000006080000004" />
            <midi name="MIDI" channel="1"
                clipInstances="0x000000000000006080000005" />
          </instruments>
          <sessionClips>
            <instrumentClip clipName="CvSess" cvChannel="0" length="96" />
            <audioClip clipName="OrphanAudio" trackName="MissingAudio" filePath="SAMPLES/NOPE.WAV" length="96" />
            <audioClip clipName="LeadAudioSess" trackName="lead" filePath="SAMPLES/LEAD1.WAV" length="96" section="3" />
            <instrumentClip clipName="LeadSynthSess" instrumentPresetName="Lead" instrumentPresetFolder="SYNTHS"
                length="96" section="1" />
          </sessionClips>
          <arrangementOnlyClips>
            <instrumentClip clipName="CvArr" cvChannel="0" length="96" />
            <audioClip clipName="LeadAudioArr" trackName="LEAD" filePath="SAMPLES/LEAD2.WAV" length="96" isPlaying="1" />
            <instrumentClip clipName="LeadKitArr" trackName="Lead" instrumentPresetName="Lead" instrumentPresetFolder="KITS"
                length="96" />
            <instrumentClip clipName="LeadSynthArr" trackName="Lead" instrumentPresetName="Lead" instrumentPresetFolder="SYNTHS"
                length="96" />
            <instrumentClip clipName="MidiCh0Arr" trackName="MIDI" midiChannel="0" length="96" />
            <instrumentClip clipName="MidiCh1Arr" trackName="MIDI" midiChannel="1" length="96" />
          </arrangementOnlyClips>
        </song>
        """;

    ProjectModel parsed =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "SameNamed");

    AudioTrackModel audioLead = (AudioTrackModel) parsed.getTracks().get(0);
    assertEquals(2, audioLead.getAudioClips().size(), "MissingAudio clip must NOT steal audioLead");
    assertEquals("LeadAudioSess", audioLead.getAudioClips().get(0).getClipName());
    assertEquals("LeadAudioArr", audioLead.getAudioClips().get(1).getClipName());
    assertFalse(
        audioLead.getAudioClips().get(1).isPlaying(),
        "Arrangement-only AudioClip must not stay active/playing in session");

    KitTrackModel kitLead = (KitTrackModel) parsed.getTracks().get(1);
    assertEquals(1, kitLead.getClips().size());
    assertEquals("LeadKitArr", kitLead.getClips().get(0).getName());
    assertFalse(kitLead.getClips().get(0).isActiveInSession());

    SynthTrackModel synthLead = (SynthTrackModel) parsed.getTracks().get(2);
    assertEquals(
        2, synthLead.getClips().size(), "CV session/arr clips must NOT steal synthLead track");
    assertEquals("LeadSynthSess", synthLead.getClips().get(0).getName());
    assertEquals("LeadSynthArr", synthLead.getClips().get(1).getName());
    assertFalse(synthLead.getClips().get(1).isActiveInSession());

    // All 7 valid arranger instances resolve across same-named tracks & dual MIDI channels:
    assertEquals(7, parsed.getArrangerTimeline().size());
    assertEquals("MidiCh0Arr", parsed.getArrangerTimeline().get(5).clip().getName());
    assertEquals(3, parsed.getArrangerTimeline().get(5).trackIndex());
    assertEquals("MidiCh1Arr", parsed.getArrangerTimeline().get(6).clip().getName());
    assertEquals(4, parsed.getArrangerTimeline().get(6).trackIndex());

    // Section 255 <-> arrangementOnly sync invariant on ClipModel + session MIDI export isolation:
    ClipModel secSync = new ClipModel("SecTest", 8, 16);
    secSync.setSection(255);
    assertTrue(secSync.isArrangementOnly());
    assertFalse(secSync.isActiveInSession());
    secSync.setSection(4);
    assertFalse(secSync.isArrangementOnly());

    ProjectModel sessionOnlyExport = new ProjectModel();
    SynthTrackModel exportSynth = new SynthTrackModel("Solo");
    ClipModel sessExportClip = new ClipModel("SessOnly", 8, 16);
    sessExportClip.setStep(0, 0, StepData.of(true, 1.0f, 4.0f, 1.0f, 60));
    exportSynth.addClip(sessExportClip);
    ClipModel arrUnusedClip = new ClipModel("HiddenArr", 8, 16);
    arrUnusedClip.setArrangementOnly(true);
    arrUnusedClip.setStep(0, 0, StepData.of(true, 1.0f, 4.0f, 1.0f, 72));
    exportSynth.addClip(arrUnusedClip);
    sessionOnlyExport.addTrack(exportSynth);

    File sessionMidi = tempDir.resolve("session_only.mid").toFile();
    ExportHelper.exportMidi(sessionOnlyExport, sessionMidi);
    javax.sound.midi.Sequence seq = javax.sound.midi.MidiSystem.getSequence(sessionMidi);
    boolean saw60 = false;
    boolean saw72 = false;
    for (javax.sound.midi.Track tr : seq.getTracks()) {
      for (int e = 0; e < tr.size(); e++) {
        javax.sound.midi.MidiMessage msg = tr.get(e).getMessage();
        if (msg instanceof javax.sound.midi.ShortMessage sm
            && sm.getCommand() == javax.sound.midi.ShortMessage.NOTE_ON
            && sm.getData2() > 0) {
          if (sm.getData1() == 60) saw60 = true;
          if (sm.getData1() == 72) saw72 = true;
        }
      }
    }
    assertTrue(saw60, "Session clip note 60 must be exported");
    assertFalse(saw72, "Arrangement-only clip note 72 must NOT leak into session fallback export");
  }
}
