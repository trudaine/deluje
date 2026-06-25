package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Guards the arrangementOnlyTracks serializer gap (C song.cpp:1286-1297): arrangement-only clips
 * must be written in their own block (not as session clips) and round-trip back through the parser.
 */
public class ArrangementOnlyRoundTripTest {

  @Test
  void arrangementOnlyClipsRoundTrip() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("LeadSynth");
    synth.setOsc1Type("SAW");

    ClipModel session = new ClipModel("sess", 1, 16);
    session.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(session);

    ClipModel arr = new ClipModel("arrOnly", 1, 16);
    arr.setStep(0, 4, StepData.of(true, 1.0f, 16.0f, 1.0f, 67));
    arr.setArrangementOnly(true);
    synth.addClip(arr);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);

    assertTrue(xml.contains("arrangementOnlyTracks"), "no <arrangementOnlyTracks> block written");
    assertTrue(
        xml.contains("trackName=\"LeadSynth\""),
        "arrangement-only clip missing trackName for parser match");
    // The arrangement-only block must appear AFTER sessionClips, and the session clip stays in
    // sessionClips (exactly one instrumentClip before the arrangementOnlyTracks block).
    int sess = xml.indexOf("sessionClips");
    int arrIdx = xml.indexOf("arrangementOnlyTracks");
    assertTrue(sess >= 0 && arrIdx > sess, "arrangementOnlyTracks not after sessionClips");
    int sessionClipsCount = xml.substring(sess, arrIdx).split("<instrumentClip", -1).length - 1;
    assertEquals(1, sessionClipsCount, "session block should contain only the 1 session clip");

    // Re-parse and confirm both clips return, with the arrangement-only flag preserved.
    ProjectModel reparsed =
        DelugeXmlParser.parseSong(
            new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "rt");
    TrackModel t = reparsed.getTracks().get(0);
    long arrCount = t.getClips().stream().filter(ClipModel::isArrangementOnly).count();
    long sessCount = t.getClips().stream().filter(c -> !c.isArrangementOnly()).count();
    System.out.printf("[arrOnly round-trip] session=%d arrangementOnly=%d%n", sessCount, arrCount);
    assertEquals(1, arrCount, "arrangement-only clip did not round-trip");
    assertTrue(sessCount >= 1, "session clip lost");
  }
}
