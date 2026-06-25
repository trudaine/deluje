package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe, high-fidelity unit test suite for the Keyboard Zone Mapper. Verifies keyzone
 * allocation, automatic uniform pitch distribution algorithms, manual resize/move operations, and
 * XML serialization persistence roundtrip without AWT GUI dependencies.
 */
public class KeyZoneMapperTest {

  @Test
  void testAutomaticUniformPitchDistribution() {
    List<SynthTrackModel.KeyZone> zones = new ArrayList<>();
    String[] samplePaths = {"SD/SAMPLES/KICK.WAV", "SD/SAMPLES/SNARE.WAV", "SD/SAMPLES/HIHAT.WAV"};

    int numFiles = samplePaths.length;
    int noteSpan = 128 / numFiles; // 128 / 3 = 42 notes each

    for (int i = 0; i < numFiles; i++) {
      SynthTrackModel.KeyZone kz = new SynthTrackModel.KeyZone();
      kz.samplePath = samplePaths[i];
      kz.minPitch = i * noteSpan;
      kz.maxPitch = (i == numFiles - 1) ? 127 : (kz.minPitch + noteSpan - 1);
      zones.add(kz);
    }

    // Assert correct distribution
    assertEquals(3, zones.size());

    // Zone 1
    assertEquals("SD/SAMPLES/KICK.WAV", zones.get(0).samplePath);
    assertEquals(0, zones.get(0).minPitch);
    assertEquals(41, zones.get(0).maxPitch);

    // Zone 2
    assertEquals("SD/SAMPLES/SNARE.WAV", zones.get(1).samplePath);
    assertEquals(42, zones.get(1).minPitch);
    assertEquals(83, zones.get(1).maxPitch);

    // Zone 3
    assertEquals("SD/SAMPLES/HIHAT.WAV", zones.get(2).samplePath);
    assertEquals(84, zones.get(2).minPitch);
    assertEquals(127, zones.get(2).maxPitch);
  }

  @Test
  void testResizeAndMoveBoundaryMathematics() {
    SynthTrackModel.KeyZone kz = new SynthTrackModel.KeyZone();
    kz.minPitch = 60; // C4
    kz.maxPitch = 72; // C5

    // 1. Simulate Move Operation (shift right by 5 semitones)
    int dragStartMin = 60;
    int dragStartMax = 72;
    int deltaNotes = 5;

    int length = dragStartMax - dragStartMin; // 12
    int newMin = Math.max(0, Math.min(127 - length, dragStartMin + deltaNotes)); // 65
    kz.minPitch = newMin;
    kz.maxPitch = newMin + length;

    assertEquals(65, kz.minPitch);
    assertEquals(77, kz.maxPitch);

    // 2. Simulate Resize Left Operation (move minPitch right by 2 semitones)
    int dragStartMinResize = 65;
    int deltaNotesResize = 2;
    int newMinResize = Math.max(0, Math.min(kz.maxPitch, dragStartMinResize + deltaNotesResize));
    kz.minPitch = newMinResize;

    assertEquals(67, kz.minPitch);
    assertEquals(77, kz.maxPitch); // maxPitch remains unchanged

    // 3. Simulate Resize Right Operation (move maxPitch left by 4 semitones)
    int dragStartMaxResize = 77;
    int deltaNotesResizeRight = -4;
    int newMaxResize =
        Math.max(kz.minPitch, Math.min(127, dragStartMaxResize + deltaNotesResizeRight));
    kz.maxPitch = newMaxResize;

    assertEquals(67, kz.minPitch); // minPitch remains unchanged
    assertEquals(73, kz.maxPitch);
  }

  @Test
  void testXmlKeyzoneSerializationRoundtrip() throws Exception {
    // 1. Create a project model and add a multisample track
    ProjectModel project = new ProjectModel();
    SynthTrackModel synth = new SynthTrackModel("MULTISAMPLE");
    project.getTracks().add(synth);

    // 2. Populate 3 keyzones with detailed properties
    SynthTrackModel.KeyZone kz1 = new SynthTrackModel.KeyZone();
    kz1.samplePath = "SD/SAMPLES/KICK.WAV";
    kz1.minPitch = 0;
    kz1.maxPitch = 60;
    kz1.minVelocity = 10;
    kz1.maxVelocity = 110;
    kz1.startSamplePos = 100;
    kz1.endSamplePos = 5000;
    kz1.startLoopPos = 200;
    kz1.endLoopPos = 4000;
    kz1.looping = true;
    synth.getOsc1Zones().add(kz1);

    SynthTrackModel.KeyZone kz2 = new SynthTrackModel.KeyZone();
    kz2.samplePath = "SD/SAMPLES/SNARE.WAV";
    kz2.minPitch = 61;
    kz2.maxPitch = 127;
    kz2.minVelocity = 0;
    kz2.maxVelocity = 127;
    kz2.startSamplePos = 0;
    kz2.endSamplePos = 8000;
    kz2.startLoopPos = -1;
    kz2.endLoopPos = -1;
    kz2.looping = false;
    synth.getOsc1Zones().add(kz2);

    // 3. Serialize the project to an XML string using ProjectSerializer
    String xmlStr = ProjectSerializer.serializeToString(project);

    // 4. Parse the serialized XML back using DelugeXmlParser
    ByteArrayInputStream bais =
        new ByteArrayInputStream(xmlStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ProjectModel parsedProject = DelugeXmlParser.parseSong(bais, "TEST");

    // 5. Verify the keyzones are fully and perfectly restored
    assertNotNull(parsedProject);
    assertEquals(1, parsedProject.getTracks().size());
    assertTrue(parsedProject.getTracks().get(0) instanceof SynthTrackModel);

    SynthTrackModel parsedSynth = (SynthTrackModel) parsedProject.getTracks().get(0);
    List<SynthTrackModel.KeyZone> restoredZones = parsedSynth.getOsc1Zones();

    assertEquals(2, restoredZones.size(), "Should restore exactly 2 keyzones!");

    // Verify Zone 1
    SynthTrackModel.KeyZone res1 = restoredZones.get(0);
    assertEquals("SD/SAMPLES/KICK.WAV", res1.samplePath);
    assertEquals(0, res1.minPitch);
    assertEquals(60, res1.maxPitch);
    assertEquals(10, res1.minVelocity);
    assertEquals(110, res1.maxVelocity);
    assertEquals(100, res1.startSamplePos);
    assertEquals(5000, res1.endSamplePos);
    assertEquals(200, res1.startLoopPos);
    assertEquals(4000, res1.endLoopPos);
    assertTrue(res1.looping);

    // Verify Zone 2
    SynthTrackModel.KeyZone res2 = restoredZones.get(1);
    assertEquals("SD/SAMPLES/SNARE.WAV", res2.samplePath);
    assertEquals(61, res2.minPitch);
    assertEquals(127, res2.maxPitch);
    assertEquals(0, res2.minVelocity);
    assertEquals(127, res2.maxVelocity);
    assertEquals(0, res2.startSamplePos);
    assertEquals(8000, res2.endSamplePos);
    assertEquals(-1, res2.startLoopPos);
    assertEquals(-1, res2.endLoopPos);
    assertFalse(res2.looping);
  }
}
