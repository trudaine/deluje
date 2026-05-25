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
    assertTrue(xmlContent.contains("<kit"), "should contain <kit\n" + xmlContent);
    assertTrue(
        xmlContent.contains("<presetSlot>DRUMS</presetSlot>"),
        "should contain DRUMS\n" + xmlContent);
    // Note: cloneSamples() rewrites paths to SAMPLES/... format
    assertTrue(
        xmlContent.contains("fileName=\"SAMPLES/DRUMS/Kick/kick.wav\""),
        "should contain fileName\n" + xmlContent);
    assertTrue(xmlContent.contains("<sound>"));
    assertTrue(xmlContent.contains("<presetSlot>LEAD</presetSlot>"));
    assertTrue(xmlContent.contains("<type>square</type>"));

    // Assertions for clips and notes
    assertTrue(xmlContent.contains("<tracks"));
    assertTrue(xmlContent.contains("<track"));
    assertTrue(xmlContent.contains("<noteRows"));
    assertTrue(xmlContent.contains("<noteRow"));
    assertTrue(xmlContent.contains("<noteData"));
  }

  @Test
  void testSerializeTripletProject() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(120.0f);

    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setOsc1Type("SAWTOOTH");

    // Create a triplet clip with 12 steps!
    org.chuck.deluge.model.ClipModel clip = new org.chuck.deluge.model.ClipModel("CLIP 1", 8, 12);
    clip.setTripletMode(true);

    // Add an active step event at step index 1!
    clip.setStep(0, 1, org.chuck.deluge.model.StepData.of(true, 0.8f, 0.9f, 1.0f, 60));
    synth.addClip(clip);
    model.addTrack(synth);

    File tempXml = File.createTempFile("deluge_test_triplet", ".xml");
    tempXml.deleteOnExit();

    ProjectSerializer.save(model, tempXml);

    String xmlContent = Files.readString(tempXml.toPath());

    // Assert JSave loop attributes!
    assertTrue(xmlContent.contains("triplet=\"1\""), "should contain triplet=\"1\"\n" + xmlContent);
    assertTrue(
        xmlContent.contains("length=\"384\""), "should contain length=\"384\"\n" + xmlContent);

    // Parse the file back and verify integrity!
    ProjectModel parsed = org.chuck.deluge.xml.DelugeXmlParser.parseSong(tempXml);

    org.junit.jupiter.api.Assertions.assertNotNull(parsed);
    org.junit.jupiter.api.Assertions.assertEquals(1, parsed.getTracks().size());

    org.chuck.deluge.model.TrackModel parsedTrack = parsed.getTracks().get(0);
    org.junit.jupiter.api.Assertions.assertEquals(1, parsedTrack.getClips().size());

    org.chuck.deluge.model.ClipModel parsedClip = parsedTrack.getClips().get(0);
    org.junit.jupiter.api.Assertions.assertTrue(
        parsedClip.isTripletMode(), "Parsed clip should be in triplet mode!");
    org.junit.jupiter.api.Assertions.assertEquals(
        12, parsedClip.getStepCount(), "Parsed clip should have exactly 12 steps!");

    org.chuck.deluge.model.StepData parsedStep = parsedClip.getStep(0, 1);
    org.junit.jupiter.api.Assertions.assertTrue(
        parsedStep.active(), "Parsed step 1 should be active!");
    org.junit.jupiter.api.Assertions.assertEquals(
        0, parsedStep.pitch(), "Parsed step 1 pitch should be 0 (row-level semitone instead)!");
  }
}
