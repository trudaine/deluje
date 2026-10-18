package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/**
 * A clip's play direction is written as C sequenceDirectionModeToString does (util/functions.cpp):
 * "pingpong", not the enum name "ping_pong", which C stringToSequenceDirectionMode reads as
 * FORWARD.
 */
class ClipDirectionXmlTest {

  @Test
  void pingPongIsSavedAsTheCStringAndReloads() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("S");
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setPlayDirection(ClipModel.PlayDirection.PING_PONG);
    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    String xml = ProjectSerializer.serializeToString(project);
    assertTrue(xml.contains("sequenceDirection=\"pingpong\""), "C token");
    assertFalse(xml.contains("ping_pong"));
    ProjectModel back =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt");
    assertEquals(
        ClipModel.PlayDirection.PING_PONG,
        back.getTracks().get(0).getClips().get(0).getPlayDirection());
  }
}
