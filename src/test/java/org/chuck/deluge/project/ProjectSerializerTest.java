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
    assertTrue(xmlContent.contains("<instrument name=\"DRUMS\" type=\"kit\""));
    assertTrue(xmlContent.contains("fileName=\"/tmp/kick.wav\""));
    assertTrue(xmlContent.contains("<instrument name=\"LEAD\" type=\"synth\""));
    assertTrue(xmlContent.contains("<osc1 type=\"square\""));
  }
}
