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

  @Test
  public void testProceduralDeepHouseGroove() throws Exception {
    ProjectModel emptyProject = new ProjectModel();
    java.io.File file = new java.io.File("deep_house_groove.txt");
    if (!file.exists()) {
      file = new java.io.File("deluge/deep_house_groove.txt");
    }
    assertTrue(file.exists());

    // Load and execute the script
    var scriptingEngine = new ScriptingEngine();
    scriptingEngine.loadAndExecuteScript(file, emptyProject);

    // Verify track creation
    assertEquals(3, emptyProject.getTracks().size());

    // Track 0: Drums (Kit)
    var drums = emptyProject.getTracks().get(0);
    assertEquals("Drums", drums.getName());
    assertTrue(drums instanceof org.deluge.model.KitTrackModel);
    var drumTrack = (org.deluge.model.KitTrackModel) drums;
    var drumClip = drumTrack.getClips().get(0);
    assertTrue(drumClip.getStep(0, 0).active()); // Kick
    assertTrue(drumClip.getStep(1, 4).active()); // Clap
    assertTrue(drumClip.getStep(2, 3).active()); // Shuffle Hat

    // Track 1: Chords (Synth)
    var chords = emptyProject.getTracks().get(1);
    assertEquals("Chords", chords.getName());
    assertTrue(chords instanceof org.deluge.model.SynthTrackModel);
    var chordsTrack = (org.deluge.model.SynthTrackModel) chords;
    var chordsClip = chordsTrack.getClips().get(0);
    // Verify polyphonic Amin7 chord at step 0
    assertTrue(chordsClip.getStep(0, 0).active());
    assertEquals(45, chordsClip.getStep(0, 0).pitch());
    assertTrue(chordsClip.getStep(1, 0).active());
    assertEquals(48, chordsClip.getStep(1, 0).pitch());
    assertTrue(chordsClip.getStep(2, 0).active());
    assertEquals(52, chordsClip.getStep(2, 0).pitch());
    assertTrue(chordsClip.getStep(3, 0).active());
    assertEquals(55, chordsClip.getStep(3, 0).pitch());

    // Track 2: Bass (Synth)
    var bass = emptyProject.getTracks().get(2);
    assertEquals("Bass", bass.getName());
    assertTrue(bass instanceof org.deluge.model.SynthTrackModel);
    var bassTrack = (org.deluge.model.SynthTrackModel) bass;
    var bassClip = bassTrack.getClips().get(0);
    assertTrue(bassClip.getStep(0, 0).active());
    assertEquals(33, bassClip.getStep(0, 0).pitch());

    // Verify configured synth filters
    assertEquals(7000.0f, chordsTrack.getLpfFreq(), 0.01f); // 35% * 20000 = 7000
    assertEquals(3600.0f, bassTrack.getLpfFreq(), 0.01f); // 18% * 20000 = 3600

    // Verify global master parameters
    assertEquals(122.0f, emptyProject.getBpm());
    assertEquals(0.54f, emptyProject.getSwing(), 0.01f);
    assertEquals(0.86f, emptyProject.getReverbRoomSize(), 0.01f);
  }

  @Test
  public void testProceduralCinematicAmbient() throws Exception {
    ProjectModel emptyProject = new ProjectModel();
    java.io.File file = new java.io.File("cinematic_ambient.txt");
    if (!file.exists()) {
      file = new java.io.File("deluge/cinematic_ambient.txt");
    }
    assertTrue(file.exists());

    // Load and execute the script
    var scriptingEngine = new ScriptingEngine();
    scriptingEngine.loadAndExecuteScript(file, emptyProject);

    // Verify track creation
    assertEquals(4, emptyProject.getTracks().size());

    // Track 0: Percussion (Kit)
    var drums = emptyProject.getTracks().get(0);
    assertEquals("Percussion", drums.getName());
    assertTrue(drums instanceof org.deluge.model.KitTrackModel);
    var drumTrack = (org.deluge.model.KitTrackModel) drums;
    var drumClip = drumTrack.getClips().get(0);
    assertTrue(drumClip.getStep(0, 0).active()); // Kick
    assertTrue(drumClip.getStep(1, 6).active()); // Rimshot

    // Track 1: DronePad (Synth)
    var drone = emptyProject.getTracks().get(1);
    assertEquals("DronePad", drone.getName());
    assertTrue(drone instanceof org.deluge.model.SynthTrackModel);
    var droneTrack = (org.deluge.model.SynthTrackModel) drone;
    var droneClip = droneTrack.getClips().get(0);
    // Verify 5-note polyphonic Cmin9 chord at step 0
    assertTrue(droneClip.getStep(0, 0).active());
    assertEquals(48, droneClip.getStep(0, 0).pitch());
    assertTrue(droneClip.getStep(1, 0).active());
    assertEquals(51, droneClip.getStep(1, 0).pitch());
    assertTrue(droneClip.getStep(2, 0).active());
    assertEquals(55, droneClip.getStep(2, 0).pitch());
    assertTrue(droneClip.getStep(3, 0).active());
    assertEquals(58, droneClip.getStep(3, 0).pitch());
    assertTrue(droneClip.getStep(4, 0).active());
    assertEquals(62, droneClip.getStep(4, 0).pitch());

    // Track 2: Strings (Synth)
    var strings = emptyProject.getTracks().get(2);
    assertEquals("Strings", strings.getName());
    assertTrue(strings instanceof org.deluge.model.SynthTrackModel);
    var stringsTrack = (org.deluge.model.SynthTrackModel) strings;
    var stringsClip = stringsTrack.getClips().get(0);
    assertTrue(stringsClip.getStep(0, 0).active());
    assertEquals(79, stringsClip.getStep(0, 0).pitch());

    // Track 3: SubDrone (Synth)
    var sub = emptyProject.getTracks().get(3);
    assertEquals("SubDrone", sub.getName());
    assertTrue(sub instanceof org.deluge.model.SynthTrackModel);
    var subTrack = (org.deluge.model.SynthTrackModel) sub;
    var subClip = subTrack.getClips().get(0);
    assertTrue(subClip.getStep(0, 0).active());
    assertEquals(36, subClip.getStep(0, 0).pitch());

    // Verify configured synth filters
    assertEquals(5000.0f, droneTrack.getLpfFreq(), 0.01f); // 25% * 20000 = 5000
    assertEquals(9000.0f, stringsTrack.getLpfFreq(), 0.01f); // 45% * 20000 = 9000
    assertEquals(2400.0f, subTrack.getLpfFreq(), 0.01f); // 12% * 20000 = 2400

    // Verify global master parameters
    assertEquals(90.0f, emptyProject.getBpm());
    assertEquals(0.50f, emptyProject.getSwing(), 0.01f);
    assertEquals(0.92f, emptyProject.getReverbRoomSize(), 0.01f);
  }
}
