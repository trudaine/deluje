package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.deluge.BridgeContract;
import org.deluge.model.*;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Verifies the 100% physical Deluge XML arrangement-specific mute/solo parity, and the playback
 * scheduler's dynamic track silencing logic.
 */
public class ArrangerMuteSoloParityTest {

  @Test
  public void testModelAndParserParity() throws Exception {
    ProjectModel project = new ProjectModel();

    // Setup 2 tracks: one synth track and one audio track
    SynthTrackModel synth = new SynthTrackModel("SYNTH 1");
    ClipModel synthClip = new ClipModel("CLIP 1", 8, 16);
    synth.addClip(synthClip);
    synth.setMutedInArrangement(true);
    synth.setSoloingInArrangement(false);
    project.addTrack(synth);

    AudioTrackModel audio = new AudioTrackModel("AUDIO 1");
    audio.setMutedInArrangement(false);
    audio.setSoloingInArrangement(true);
    // Add a mock clip to audio track so it serializes
    AudioTrackModel.AudioClip audioClip = new AudioTrackModel.AudioClip();
    audioClip.setTrackName("AUDIO 1");
    audioClip.setFilePath("sample.wav");
    audio.addAudioClip(audioClip);
    project.addTrack(audio);

    // 1. Serialize to XML String
    String xml = ProjectSerializer.serializeToString(project);
    assertNotNull(xml);

    // Verify correct attribute placement in XML using DOM parser
    javax.xml.parsers.DocumentBuilderFactory dbf =
        javax.xml.parsers.DocumentBuilderFactory.newInstance();
    javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
    org.w3c.dom.Document doc =
        db.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    // Verify <sound> track element carries isMutedInArrangement
    org.w3c.dom.NodeList soundNodes = doc.getElementsByTagName("sound");
    assertTrue(soundNodes.getLength() > 0);
    org.w3c.dom.Element soundElem = (org.w3c.dom.Element) soundNodes.item(0);
    assertEquals(
        "1",
        soundElem.getAttribute("isMutedInArrangement"),
        "sound track element must carry isMutedInArrangement=1");
    assertFalse(
        soundElem.hasAttribute("isSoloingInArrangement"),
        "sound track element must not carry isSoloingInArrangement");

    // Verify <audioTrack> track element carries isSoloingInArrangement
    org.w3c.dom.NodeList audioTrackNodes = doc.getElementsByTagName("audioTrack");
    assertTrue(audioTrackNodes.getLength() > 0);
    org.w3c.dom.Element audioTrackElem = (org.w3c.dom.Element) audioTrackNodes.item(0);
    assertEquals(
        "1",
        audioTrackElem.getAttribute("isSoloingInArrangement"),
        "audioTrack element must carry isSoloingInArrangement=1");
    assertFalse(
        audioTrackElem.hasAttribute("isMutedInArrangement"),
        "audioTrack element must not carry isMutedInArrangement");

    // Verify <instrumentClip> does NOT carry the attributes (was incorrect in previous versions)
    org.w3c.dom.NodeList instClipNodes = doc.getElementsByTagName("instrumentClip");
    assertTrue(instClipNodes.getLength() > 0);
    org.w3c.dom.Element instClipElem = (org.w3c.dom.Element) instClipNodes.item(0);
    assertFalse(
        instClipElem.hasAttribute("isMutedInArrangement"),
        "instrumentClip must not carry isMutedInArrangement");
    assertFalse(
        instClipElem.hasAttribute("isSoloingInArrangement"),
        "instrumentClip must not carry isSoloingInArrangement");

    // Verify <audioClip> does NOT carry the attributes
    org.w3c.dom.NodeList audioClipNodes = doc.getElementsByTagName("audioClip");
    assertTrue(audioClipNodes.getLength() > 0);
    org.w3c.dom.Element audioClipElem = (org.w3c.dom.Element) audioClipNodes.item(0);
    assertFalse(
        audioClipElem.hasAttribute("isMutedInArrangement"),
        "audioClip must not carry isMutedInArrangement");
    assertFalse(
        audioClipElem.hasAttribute("isSoloingInArrangement"),
        "audioClip must not carry isSoloingInArrangement");

    // 2. Parse back from XML String
    ProjectModel parsedProject =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "TEST");

    assertNotNull(parsedProject);
    List<TrackModel> parsedTracks = parsedProject.getTracks();
    assertEquals(2, parsedTracks.size());

    // Assert parity values are correctly loaded
    TrackModel parsedSynth = parsedTracks.get(0);
    assertTrue(
        parsedSynth.isMutedInArrangement(), "Parsed synth track must be muted in arrangement");
    assertFalse(
        parsedSynth.isSoloingInArrangement(),
        "Parsed synth track must not be soloed in arrangement");

    TrackModel parsedAudio = parsedTracks.get(1);
    assertFalse(
        parsedAudio.isMutedInArrangement(), "Parsed audio track must not be muted in arrangement");
    assertTrue(
        parsedAudio.isSoloingInArrangement(), "Parsed audio track must be soloed in arrangement");
  }

  @Test
  public void testArrangerPlaybackSchedulerMuteSoloSilencing() throws Exception {
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();

    // Setup 2 tracks
    SynthTrackModel track0 = new SynthTrackModel("TRACK 0");
    ClipModel clip0 = new ClipModel("CLIP 0", 8, 16);
    // Fill clip0 with active notes on row 0, steps 0 and 1
    clip0.setStep(0, 0, StepData.of(true, 1.0f, 0.9f, 1.0f, 60));
    clip0.setStep(0, 1, StepData.of(true, 1.0f, 0.9f, 1.0f, 60));
    track0.addClip(clip0);
    project.addTrack(track0);

    SynthTrackModel track1 = new SynthTrackModel("TRACK 1");
    ClipModel clip1 = new ClipModel("CLIP 1", 8, 16);
    // Fill clip1 with active notes on row 0, steps 0 and 1
    clip1.setStep(0, 0, StepData.of(true, 1.0f, 0.9f, 1.0f, 64));
    clip1.setStep(0, 1, StepData.of(true, 1.0f, 0.9f, 1.0f, 64));
    track1.addClip(clip1);
    project.addTrack(track1);

    // Place both clips at step 0 in Arranger timeline
    project.getArrangerTimeline().add(new ArrangerClip(0, clip0, 0, 8 * 24));
    project.getArrangerTimeline().add(new ArrangerClip(1, clip1, 0, 8 * 24));

    // Case A: Track 0 is muted in Arranger
    track0.setMutedInArrangement(true);
    track1.setMutedInArrangement(false);

    // Instantiate scheduler and trigger upcoming step 0 broadcast
    ArrangerPlaybackScheduler scheduler = new ArrangerPlaybackScheduler(bridge, project);
    scheduler.setArrangerModeActive(true);

    // We trigger the scheduler's normal tick flow by enabling play and setting current step
    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 0L);

    // Give the scheduler a tiny slice of time to poll and process
    Thread.sleep(100);

    // Note: updateBridgeUpcomingStep will write to step (currentStep + 1), which is step 1 (col 1)
    // Assert track 0 is completely silenced (bridge step is false)
    assertFalse(bridge.getStep(0, 1), "Track 0 must be silent on bridge due to Arranger mute");
    // Assert track 1 plays correctly (bridge step is true)
    assertTrue(bridge.getStep(8, 1), "Track 1 must be active on bridge");

    // Case B: Track 1 is soloed (which should mute Track 0 even if Track 0 mute is cleared)
    track0.setMutedInArrangement(false);
    track1.setSoloingInArrangement(true);

    // Add notes at step 2
    clip0.setStep(0, 2, StepData.of(true, 1.0f, 0.9f, 1.0f, 60));
    clip1.setStep(0, 2, StepData.of(true, 1.0f, 0.9f, 1.0f, 64));

    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 1L); // will write step 2 (col 2)
    Thread.sleep(100);

    // Assert track 0 is silenced because track 1 is soloed
    assertFalse(bridge.getStep(0, 2), "Track 0 must be silent because Track 1 is soloed");
    // Assert track 1 plays correctly
    assertTrue(bridge.getStep(8, 2), "Track 1 must be active because it is soloed");

    scheduler.shutdown();
  }
}
