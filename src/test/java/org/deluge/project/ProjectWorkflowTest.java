package org.deluge.project;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import org.deluge.model.*;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ProjectWorkflowTest {

  @Test
  public void testSongKitSynthLoadSaveWorkflow(@TempDir java.nio.file.Path tempDir)
      throws Exception {
    // 1. Load an existing song XML preset
    InputStream songStream = getClass().getResourceAsStream("/SONGS/Dx7C.xml");
    assertNotNull(songStream, "Song Dx7C.xml not found");
    ProjectModel project = DelugeXmlParser.parseSong(songStream, "Dx7C");
    assertNotNull(project);

    // Verify initial tracks
    List<TrackModel> tracks = project.getTracks();
    assertTrue(tracks.size() >= 2);
    assertTrue(tracks.get(0) instanceof SynthTrackModel);
    assertTrue(tracks.get(1) instanceof SynthTrackModel);

    // 2. Load a kit preset and add it as a new track
    InputStream kitStream = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(kitStream, "TR-808 Kit not found");
    KitTrackModel kit = DelugeXmlParser.parseKit(kitStream, "000 TR-808");
    assertNotNull(kit);
    assertEquals("000 TR-808", kit.getName());

    // Add default clip to kit and append track to project
    ClipModel kitClip = new ClipModel("CLIP 1", kit.getDrums().size(), 16);
    kit.addClip(kitClip);
    project.addTrack(kit);

    // 3. Load a synth preset and add it as a new track
    InputStream synthStream = getClass().getResourceAsStream("/SYNTHS/056 FM Bell Modulation.XML");
    assertNotNull(synthStream, "FM Bell Synth not found");
    SynthTrackModel synth = DelugeXmlParser.parseSynth(synthStream, "056 FM Bell Modulation");
    assertNotNull(synth);
    assertEquals("056 FM Bell Modulation", synth.getName());

    // Add default clip to synth and append track to project
    ClipModel synthClip = new ClipModel("CLIP 1", 8, 16);
    synth.addClip(synthClip);
    project.addTrack(synth);

    // 4. Modify note events inside a track (e.g. the newly added synth track)
    // Add note triggers
    synthClip.setStep(0, 0, StepData.of(true, 0.8f, 0.9f, 1.0f, 60));
    synthClip.setStep(0, 4, StepData.of(true, 0.8f, 0.9f, 1.0f, 64));
    synthClip.setStep(0, 8, StepData.of(true, 0.8f, 0.9f, 1.0f, 67));

    // Verify notes are set
    assertTrue(synthClip.getStep(0, 0).active());
    assertTrue(synthClip.getStep(0, 4).active());
    assertTrue(synthClip.getStep(0, 8).active());
    assertFalse(synthClip.getStep(0, 1).active()); // inactive/null

    // Remove a note
    synthClip.setStep(0, 8, StepData.of(false, 0.0f, 0.0f, 0.0f, 0));
    assertFalse(synthClip.getStep(0, 8).active());

    // 5. Remove a clip from a track
    int initialClipsSize = synth.getClips().size();
    ClipModel extraClip = new ClipModel("CLIP 2", 8, 16);
    synth.addClip(extraClip);
    assertEquals(initialClipsSize + 1, synth.getClips().size());
    synth.removeClip(extraClip);
    assertEquals(initialClipsSize, synth.getClips().size());

    // 6. Save the modified project to a temp XML file and read it back
    File savedFile = tempDir.resolve("saved_project_workflow.xml").toFile();
    ProjectSerializer.save(project, savedFile);

    // Verify file exists and is populated
    assertTrue(savedFile.exists());
    assertTrue(savedFile.length() > 0);

    // Read it back
    ProjectModel reloadedProject = DelugeXmlParser.parseSong(savedFile);
    assertNotNull(reloadedProject);

    // Assert reloaded project integrity
    List<TrackModel> reloadedTracks = reloadedProject.getTracks();
    // dx7c original (2 tracks) + kit (1 track) + synth (1 track) = 4 tracks
    assertEquals(4, reloadedTracks.size());

    // Assert the third track is our Kit
    assertTrue(reloadedTracks.get(2) instanceof KitTrackModel);
    KitTrackModel reloadedKit = (KitTrackModel) reloadedTracks.get(2);
    assertEquals("000 TR-808", reloadedKit.getName());
    assertEquals(1, reloadedKit.getClips().size());

    // Assert the fourth track is our Synth
    assertTrue(reloadedTracks.get(3) instanceof SynthTrackModel);
    SynthTrackModel reloadedSynth = (SynthTrackModel) reloadedTracks.get(3);
    assertEquals("056 FM Bell Modulation", reloadedSynth.getName());
    assertEquals(1, reloadedSynth.getClips().size());

    ClipModel reloadedSynthClip = reloadedSynth.getClips().get(0);
    assertTrue(reloadedSynthClip.getStep(0, 0).active());
    assertTrue(reloadedSynthClip.getStep(0, 4).active());
    // Note 8 was deleted, so it should not be active
    StepData step8 = reloadedSynthClip.getStep(0, 8);
    assertTrue(step8 == null || !step8.active());
  }
}
