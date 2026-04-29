package org.chuck.deluge.model;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class ProjectModelTest {

    @Test
    void testProjectInitialization() {
        ProjectModel project = new ProjectModel();
        project.setBpm(125.0f);
        
        assertEquals(0, project.getTracks().size(), "New project should have 0 tracks");
        assertEquals(125.0f, project.getBpm());
        
        KitTrackModel kit = new KitTrackModel("Drums");
        project.addTrack(kit);
        
        assertEquals(1, project.getTracks().size());
        assertEquals("Drums", project.getTracks().get(0).getName());
    }

    @Test
    void testTrackProperties() {
        SynthTrackModel synth = new SynthTrackModel("Synth 1");
        // EnvelopeModel is a record, so we replace it to "change" it
        EnvelopeModel newEnv = new EnvelopeModel(10.0f, 50.0f, 0.64f, 40.0f, "FILTER", 1.0f);
        synth.setEnv(0, newEnv);
        
        assertEquals(10.0f, synth.getEnv(0).attack());
        assertEquals(0.64f, synth.getEnv(0).sustain());
        assertEquals("FILTER", synth.getEnv(0).target());
    }

    @Test
    void testClipManagement() {
        KitTrackModel kit = new KitTrackModel("Drums");
        ClipModel clip = new ClipModel("Beat 1", 8, 16);
        kit.addClip(clip);
        
        assertEquals(1, kit.getClips().size());
        assertEquals("Beat 1", kit.getClips().get(0).getName());
        assertEquals(16, kit.getClips().get(0).getStepCount());
    }
}
