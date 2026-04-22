package org.chuck.deluge.project;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class ProjectSerializerTest {

  @Test
  void testSerializeProject() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(130.0f);

    KitTrackModel kit = new KitTrackModel("DRUMS");
    KitTrackModel.KitSound sound = new KitTrackModel.KitSound("KICK");
    sound.setSamplePath("/tmp/kick.wav");
    kit.addSound(sound);

    // Add a clip with a note
    org.chuck.deluge.model.ClipModel clip = new org.chuck.deluge.model.ClipModel("CLIP 1", 8, 16);
    clip.setStep(0, 0, new org.chuck.deluge.model.StepData(true, 0.8f, 0.9f, 1.0f, 0));
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
    assertTrue(xmlContent.contains("<song"));
    assertTrue(xmlContent.contains("tempo=\"130.0\""));
    assertTrue(xmlContent.contains("<kit>"));
    assertTrue(xmlContent.contains("<presetSlot>DRUMS</presetSlot>"));
    assertTrue(xmlContent.contains("fileName=\"/tmp/kick.wav\""));
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
