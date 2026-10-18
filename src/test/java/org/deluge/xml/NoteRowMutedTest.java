package org.deluge.xml;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.junit.jupiter.api.Test;

/** A note row's "muted" (C note_row.cpp:3220-3221, written at :3571-3573) survives save/load. */
class NoteRowMutedTest {

  private static ProjectModel roundTrip(ProjectModel p) throws Exception {
    String xml = ProjectSerializer.serializeToString(p);
    return DelugeXmlParser.parseSong(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt");
  }

  @Test
  void mutedRowIsSavedAndReloaded() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("S");
    ClipModel clip = new ClipModel("c", 2, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    clip.setStep(1, 4, StepData.of(true, 1.0f, 16.0f, 1.0f, 62));
    clip.getOrCreateRow(1).setMuted(true);
    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);
    assertEquals(1, xml.split("muted=\"1\"", -1).length - 1, "only the muted row carries it");

    ClipModel back = roundTrip(project).getTracks().get(0).getClips().get(0);
    assertTrue(back.getOrCreateRow(1).isMuted());
    assertFalse(back.getOrCreateRow(0).isMuted());
  }
}
