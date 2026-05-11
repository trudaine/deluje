package org.chuck.deluge.project;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class ProjectSerializerTest {

  @Test
  void testSerializeProject() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(130.0f);

    KitTrackModel kit = new KitTrackModel("DRUMS");
    SoundDrum sound = new SoundDrum("KICK");
    // Use a SAMPLES-relative path so the serializer preserves it on all platforms
    sound.setSamplePath("SAMPLES/DRUMS/Kick/kick.wav");
    kit.addDrum(sound);

    // Add a clip with a note
    org.chuck.deluge.model.ClipModel clip = new org.chuck.deluge.model.ClipModel("CLIP 1", 8, 16);
    clip.setStep(0, 0, org.chuck.deluge.model.StepData.of(true, 0.8f, 0.9f, 1.0f, 0));
    kit.addClip(clip);

    model.addTrack(kit);

    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setOsc1Type("SQUARE");
    model.addTrack(synth);

    File tempXml = File.createTempFile("deluge_test_save", ".xml");
    tempXml.deleteOnExit();

    ProjectSerializer.save(model, tempXml);

    String xmlContent = Files.readString(tempXml.toPath());

    // Basic assertions on output structure
    assertTrue(xmlContent.contains("<song"), "should contain <song\n" + xmlContent);
    assertTrue(xmlContent.contains("tempo=\"130.0\""), "should contain tempo\n" + xmlContent);
    assertTrue(xmlContent.contains("<kit>"), "should contain <kit>\n" + xmlContent);
    assertTrue(xmlContent.contains("<presetSlot>DRUMS</presetSlot>"), "should contain DRUMS\n" + xmlContent);
    // Note: cloneSamples() rewrites paths to SAMPLES/... format
    assertTrue(xmlContent.contains("fileName=\"SAMPLES/DRUMS/Kick/kick.wav\""), "should contain fileName\n" + xmlContent);
    assertTrue(xmlContent.contains("<sound>"));
    assertTrue(xmlContent.contains("<presetSlot>LEAD</presetSlot>"));
    assertTrue(xmlContent.contains("<osc1 type=\"square\""));

    // Assertions for clips and notes
    assertTrue(xmlContent.contains("<tracks"));
    assertTrue(xmlContent.contains("<track"));
    assertTrue(xmlContent.contains("<noteRows"));
    assertTrue(xmlContent.contains("<noteRow"));
    assertTrue(xmlContent.contains("<noteData"));
  }
}
