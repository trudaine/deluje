package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScriptingEngineTest {

  @Test
  void testRecordSaveLoadAndExecute() throws Exception {
    // 1. Setup original project
    ProjectModel originalProject = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("synth");
    ClipModel clip = new ClipModel("clip", 8, 16);
    track.addClip(clip);
    originalProject.addTrack(track);

    // 2. Setup scripting engine and record actions
    ScriptingEngine engine = new ScriptingEngine();
    engine.startRecording();

    // Action A: Toggle a step (row 0, step 4)
    StepData oldStep = clip.getStep(0, 4);
    StepData newStep = StepData.of(true, 0.85f, 1.0f, 1.0f, 62);
    clip.setStep(0, 4, newStep);
    Consequence stepAction =
        new Consequence.StepConsequence(originalProject, 0, 0, 0, 4, oldStep, newStep);
    engine.record(stepAction);

    // Action B: Change synth parameter
    track.setLpfFreq(12000.0f);
    Consequence paramAction =
        new Consequence.SynthParamConsequence(
            originalProject, 0, "lpfCutoff", 100.0f, 60.0f, System.currentTimeMillis());
    engine.record(paramAction);

    // Action C: Change project BPM
    float oldBpm = originalProject.getBpm();
    originalProject.setBpm(134.0f);
    Consequence bpmAction =
        new Consequence.ProjectParamConsequence(originalProject, "bpm", oldBpm, 134.0f);
    engine.record(bpmAction);

    engine.stopRecording();

    // Verify recording list
    List<Consequence> recorded = engine.getRecordedActions();
    assertEquals(3, recorded.size());

    // 3. Save script to temporary file
    File tempFile = File.createTempFile("macro_test", ".txt");
    tempFile.deleteOnExit();
    engine.saveScript(tempFile);

    // 4. Load and execute script on a clean new project
    ProjectModel cleanProject = new ProjectModel();
    SynthTrackModel cleanTrack = new SynthTrackModel("synth");
    ClipModel cleanClip = new ClipModel("clip", 8, 16);
    cleanTrack.addClip(cleanClip);
    cleanProject.addTrack(cleanTrack);

    // Assert clean project starts with default states
    assertEquals(120.0f, cleanProject.getBpm());
    assertFalse(cleanClip.getStep(0, 4).active());
    assertEquals(20000.0f, cleanTrack.getLpfFreq());

    // Run playback
    ScriptingEngine playbackEngine = new ScriptingEngine();
    List<Consequence> executed = playbackEngine.loadAndExecuteScript(tempFile, cleanProject);

    // 5. Verify clean project matches the original recorded states!
    assertEquals(3, executed.size());
    assertEquals(134.0f, cleanProject.getBpm());
    assertTrue(cleanClip.getStep(0, 4).active());
    assertEquals(62, cleanClip.getStep(0, 4).pitch());
    assertEquals(12000.0f, cleanTrack.getLpfFreq());
  }

  @Test
  void testProceduralSongCreation() throws Exception {
    // 1. Create a completely empty project (zero tracks)
    ProjectModel emptyProject = new ProjectModel();
    assertEquals(0, emptyProject.getTracks().size());

    // 2. Play back the Techno Creator script
    File scriptFile = new File("/Users/ludo/a/chuckjava/deluge/techno_creator.txt");
    ScriptingEngine engine = new ScriptingEngine();
    engine.loadAndExecuteScript(scriptFile, emptyProject);

    // 3. Assert project has been built from scratch!
    assertEquals(2, emptyProject.getTracks().size());

    // Track 0: Drums (Kit)
    var drums = emptyProject.getTracks().get(0);
    assertEquals("Drums", drums.getName());
    assertTrue(drums instanceof KitTrackModel);
    // Verify Kick on step 0, 4, 8, 12
    var drumClip = drums.getClips().get(0);
    assertTrue(drumClip.getStep(0, 0).active());
    assertTrue(drumClip.getStep(0, 4).active());
    assertTrue(drumClip.getStep(0, 8).active());
    assertTrue(drumClip.getStep(0, 12).active());

    // Track 1: Bass (Synth)
    var bass = emptyProject.getTracks().get(1);
    assertEquals("Bass", bass.getName());
    assertTrue(bass instanceof SynthTrackModel);
    var bassTrack = (SynthTrackModel) bass;
    // Verify rolling bass steps
    var bassClip = bassTrack.getClips().get(0);
    assertTrue(bassClip.getStep(0, 2).active());
    assertEquals(36, bassClip.getStep(0, 2).pitch());
    assertTrue(bassClip.getStep(1, 7).active());
    assertEquals(39, bassClip.getStep(1, 7).pitch());
    assertTrue(bassClip.getStep(2, 15).active());
    assertEquals(43, bassClip.getStep(2, 15).pitch());

    // Verify configured synth filters
    assertEquals(9000.0f, bassTrack.getLpfFreq(), 0.01f); // 45% * 20000 = 9000
    assertEquals(25.0f, bassTrack.getLpfRes(), 0.01f);

    // Verify global master parameters
    assertEquals(130.0f, emptyProject.getBpm());
    assertEquals(0.52f, emptyProject.getSwing(), 0.01f);
    assertEquals(0.82f, emptyProject.getReverbRoomSize(), 0.01f);
  }
}
