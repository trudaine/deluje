package org.deluge.project;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SongSection;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class ProjectSerializerTest {

  @Test
  void testSerializeProject() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(130.0f);
    model.setSwing(0.5f);

    // Set modeNotes (Major scale degree mask: 0, 2, 4, 5, 7, 9, 11)
    boolean[] mask = new boolean[12];
    mask[0] = true;
    mask[2] = true;
    mask[4] = true;
    mask[5] = true;
    mask[7] = true;
    mask[9] = true;
    mask[11] = true;
    model.setModeNotes(mask);

    // Add a SongSection
    SongSection section = new SongSection("Section 3");
    section.setNumRepeats(4);
    model.addSongSection(section);

    KitTrackModel kit = new KitTrackModel("DRUMS");
    SoundDrum sound = new SoundDrum("KICK");
    // Use a SAMPLES-relative path so the serializer preserves it on all platforms
    sound.setSamplePath("SAMPLES/DRUMS/Kick/kick.wav");
    kit.addDrum(sound);

    // Add a clip with a note
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("CLIP 1", 8, 16);
    clip.setStep(0, 0, org.deluge.model.StepData.of(true, 0.8f, 0.9f, 1.0f, 0));
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
    assertTrue(xmlContent.contains("presetName=\"DRUMS\""), "should contain DRUMS\n" + xmlContent);
    // Note: cloneSamples() rewrites paths to SAMPLES/... format
    assertTrue(
        xmlContent.contains("fileName=\"SAMPLES/DRUMS/Kick/kick.wav\""),
        "should contain fileName\n" + xmlContent);
    assertTrue(xmlContent.contains("<sound name=\"KICK\""));
    assertTrue(xmlContent.contains("presetName=\"LEAD\""));
    assertTrue(xmlContent.contains("type=\"square\""));

    // Assert modeNotes and sections XML serialization!
    assertTrue(xmlContent.contains("<modeNotes>"), "should contain modeNotes\n" + xmlContent);
    assertTrue(
        xmlContent.contains("<modeNote>0</modeNote>"), "should contain degree 0\n" + xmlContent);
    assertTrue(
        xmlContent.contains("<modeNote>2</modeNote>"), "should contain degree 2\n" + xmlContent);
    assertTrue(
        xmlContent.contains("<modeNote>4</modeNote>"), "should contain degree 4\n" + xmlContent);
    assertTrue(xmlContent.contains("<sections>"), "should contain sections\n" + xmlContent);
    assertTrue(
        xmlContent.contains("<section id=\"3\" numRepeats=\"4\""),
        "should contain section 3\n" + xmlContent);

    // Assertions for clips and notes
    assertTrue(xmlContent.contains("<sessionClips"));
    assertTrue(xmlContent.contains("<instrumentClip"));
    assertTrue(xmlContent.contains("<noteRows"));
    assertTrue(xmlContent.contains("<noteRow"));
    assertTrue(xmlContent.contains("noteDataWithLift="));

    // Parse back and verify roundtrip integrity of modeNotes and sections!
    ProjectModel parsed = org.deluge.xml.DelugeXmlParser.parseSong(tempXml);
    org.junit.jupiter.api.Assertions.assertNotNull(parsed);

    boolean[] parsedMask = parsed.getModeNotes();
    org.junit.jupiter.api.Assertions.assertNotNull(parsedMask);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[0]);
    org.junit.jupiter.api.Assertions.assertFalse(parsedMask[1]);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[2]);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[4]);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[5]);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[7]);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[9]);
    org.junit.jupiter.api.Assertions.assertTrue(parsedMask[11]);

    org.junit.jupiter.api.Assertions.assertEquals(1, parsed.getSongSections().size());
    SongSection parsedSection = parsed.getSongSections().get(0);
    org.junit.jupiter.api.Assertions.assertEquals("Section 3", parsedSection.getId());
    org.junit.jupiter.api.Assertions.assertEquals(4, parsedSection.getNumRepeats());
  }

  @Test
  void testSerializeTripletProject() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(120.0f);

    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setOsc1Type("SAWTOOTH");

    // Create a triplet clip with 12 steps!
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("CLIP 1", 8, 12);
    clip.setTripletMode(true);

    // Add an active step event at step index 1!
    clip.setStep(0, 1, org.deluge.model.StepData.of(true, 0.8f, 0.9f, 1.0f, 60));
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
    ProjectModel parsed = org.deluge.xml.DelugeXmlParser.parseSong(tempXml);

    org.junit.jupiter.api.Assertions.assertNotNull(parsed);
    org.junit.jupiter.api.Assertions.assertEquals(1, parsed.getTracks().size());

    org.deluge.model.TrackModel parsedTrack = parsed.getTracks().get(0);
    org.junit.jupiter.api.Assertions.assertEquals(1, parsedTrack.getClips().size());

    org.deluge.model.ClipModel parsedClip = parsedTrack.getClips().get(0);
    org.junit.jupiter.api.Assertions.assertTrue(
        parsedClip.isTripletMode(), "Parsed clip should be in triplet mode!");
    org.junit.jupiter.api.Assertions.assertEquals(
        12, parsedClip.getStepCount(), "Parsed clip should have exactly 12 steps!");

    org.deluge.model.StepData parsedStep = parsedClip.getStep(0, 1);
    org.junit.jupiter.api.Assertions.assertTrue(
        parsedStep.active(), "Parsed step 1 should be active!");
    // The Deluge format stores pitch in the noteRow y= attribute, not per-step.
    // Since the only active step on this row has pitch=60, the serializer writes y="60".
    org.junit.jupiter.api.Assertions.assertEquals(
        60, parsedStep.pitch(), "Parsed step 1 pitch should be 60 (row y= attribute)!");
  }

  @Test
  void testSerializeAudioTrack() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(125.0f);

    AudioTrackModel audioTrack = new AudioTrackModel("VOCALS");

    AudioTrackModel.AudioClip clip = new AudioTrackModel.AudioClip();
    clip.setTrackName("VOCALS");
    clip.setFilePath("SAMPLES/stems/vocals.wav");
    clip.setStartSamplePos(500);
    clip.setEndSamplePos(250000);
    clip.setAttack(0.02f);
    clip.setLength(768);
    clip.setVolume(0.88f);
    clip.setPan(-0.25f);
    clip.setLpfFrequency(18500.0f);
    clip.setLpfResonance(0.15f);

    audioTrack.addAudioClip(clip);
    model.addTrack(audioTrack);

    File tempXml = File.createTempFile("deluge_test_audio", ".xml");
    tempXml.deleteOnExit();

    ProjectSerializer.save(model, tempXml);

    String xmlContent = Files.readString(tempXml.toPath());

    // Verify XML tags and attributes!
    assertTrue(
        xmlContent.contains("<audioTrack name=\"VOCALS\""),
        "should contain audioTrack\n" + xmlContent);
    assertTrue(
        xmlContent.contains("<audioClip trackName=\"VOCALS\""),
        "should contain audioClip\n" + xmlContent);
    assertTrue(
        xmlContent.contains("filePath=\"SAMPLES/stems/vocals.wav\""),
        "should contain filePath\n" + xmlContent);
    assertTrue(
        xmlContent.contains("startSamplePos=\"500\""),
        "should contain startSamplePos\n" + xmlContent);
    assertTrue(
        xmlContent.contains("endSamplePos=\"250000\""),
        "should contain endSamplePos\n" + xmlContent);
    assertTrue(xmlContent.contains("length=\"768\""), "should contain length\n" + xmlContent);
    assertTrue(xmlContent.contains("<params"), "should contain params\n" + xmlContent);

    // Parse back and verify roundtrip integrity!
    ProjectModel parsed = org.deluge.xml.DelugeXmlParser.parseSong(tempXml);
    org.junit.jupiter.api.Assertions.assertNotNull(parsed);
    org.junit.jupiter.api.Assertions.assertEquals(1, parsed.getTracks().size());

    org.deluge.model.TrackModel parsedTrack = parsed.getTracks().get(0);
    assertTrue(parsedTrack instanceof AudioTrackModel, "Should be AudioTrackModel");

    AudioTrackModel parsedAudio = (AudioTrackModel) parsedTrack;
    org.junit.jupiter.api.Assertions.assertEquals("VOCALS", parsedAudio.getName());
    org.junit.jupiter.api.Assertions.assertEquals(1, parsedAudio.getAudioClips().size());

    AudioTrackModel.AudioClip parsedClip = parsedAudio.getAudioClips().get(0);
    org.junit.jupiter.api.Assertions.assertEquals("VOCALS", parsedClip.getTrackName());
    org.junit.jupiter.api.Assertions.assertEquals(
        "SAMPLES/stems/vocals.wav", parsedClip.getFilePath());
    org.junit.jupiter.api.Assertions.assertEquals(500, parsedClip.getStartSamplePos());
    org.junit.jupiter.api.Assertions.assertEquals(250000, parsedClip.getEndSamplePos());
    org.junit.jupiter.api.Assertions.assertEquals(768, parsedClip.getLength());
    org.junit.jupiter.api.Assertions.assertEquals(0.88f, parsedClip.getVolume(), 0.01f);
    org.junit.jupiter.api.Assertions.assertEquals(-0.25f, parsedClip.getPan(), 0.01f);
    org.junit.jupiter.api.Assertions.assertEquals(18500.0f, parsedClip.getLpfFrequency(), 10.0f);
    org.junit.jupiter.api.Assertions.assertEquals(0.15f, parsedClip.getLpfResonance(), 0.01f);
  }

  @Test
  void testExportMidiAndStems() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(120.0f);

    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setOsc1Type("SAWTOOTH");
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("CLIP 1", 8, 16);
    clip.setStep(0, 0, org.deluge.model.StepData.of(true, 0.8f, 0.9f, 1.0f, 60));
    synth.addClip(clip);
    model.addTrack(synth);

    // Test MIDI Export
    File tempMidi = File.createTempFile("deluge_export_test", ".mid");
    tempMidi.deleteOnExit();
    org.deluge.project.ExportHelper.exportMidi(model, tempMidi);
    assertTrue(
        tempMidi.exists() && tempMidi.length() > 10, "MIDI file should be exported successfully!");

    // Test WAV Stems Export (render 0.1 seconds to keep it super fast!)
    File tempDir = java.nio.file.Files.createTempDirectory("deluge_stems_test").toFile();
    tempDir.deleteOnExit();
    org.deluge.project.ExportHelper.exportStems(model, tempDir, 0.1, null);

    File masterWav = new File(tempDir, "Master_mix.wav");
    File trackWav = new File(tempDir, "Track_1_LEAD_stem.wav");
    assertTrue(masterWav.exists() && masterWav.length() > 1000, "Master mix stem should exist!");
    assertTrue(trackWav.exists() && trackWav.length() > 1000, "Lead track stem should exist!");
  }

  @Test
  void testAbletonExportMultiMode() throws Exception {
    ProjectModel model = new ProjectModel();
    model.setBpm(120.0f);

    SynthTrackModel synth = new SynthTrackModel("LEAD");
    synth.setOsc1Type("SAWTOOTH");
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("CLIP 1", 8, 16);
    clip.setStep(0, 0, org.deluge.model.StepData.of(true, 0.8f, 0.9f, 1.0f, 60));
    synth.addClip(clip);
    model.addTrack(synth);

    // Test Standalone Ableton Export
    File tempAls = File.createTempFile("deluge_ableton_test", ".als");
    tempAls.deleteOnExit();
    org.deluge.ableton.AbletonTrackExporter.exportProject(model, tempAls);
    assertTrue(
        tempAls.exists() && tempAls.length() > 50,
        "Standalone Ableton .als should be exported successfully!");

    // Test Stems Ableton Export (render 0.1 seconds to keep it super fast!)
    File tempAlsStems = File.createTempFile("deluge_ableton_stems_test", ".als");
    tempAlsStems.deleteOnExit();
    org.deluge.ableton.AbletonTrackExporter.exportStemsProject(model, tempAlsStems, null);
    assertTrue(
        tempAlsStems.exists() && tempAlsStems.length() > 50,
        "Stems Ableton .als should be exported successfully!");

    // Check that WAV stems were rendered in parent folder's Samples/Imported/
    File projectDir = tempAlsStems.getParentFile();
    File importedDir = new File(projectDir, "Samples/Imported");
    File stemFile = new File(importedDir, "Track_1_LEAD_stem.wav");
    assertTrue(
        stemFile.exists() && stemFile.length() > 1000,
        "WAV stem file should exist inside the Ableton project bundle!");
  }

  @Test
  void testExportBillieJeanStemsForAnalysis() throws Exception {
    File alsFile =
        new File(
            "/Users/ludo/Downloads/Michael Jackson - Billie Jean (Ableton Remake)/Project/Michael Jackson - Billie Jean.als");
    if (!alsFile.exists()) {
      System.out.println("Billie Jean ALS file not found!");
      return;
    }

    org.w3c.dom.Document doc = org.deluge.ableton.AbletonProjectManager.parseAlsToXml(alsFile);
    ProjectModel project = new ProjectModel();
    org.deluge.ableton.AbletonTrackMapper.importAbletonSet(doc, project, alsFile);

    File targetDir = new File("/Users/ludo/Downloads/BillieJean_Stems_Analysis");
    targetDir.mkdirs();

    System.out.println("==================================================");
    System.out.println("PROJECT TRACK & ARRANGEMENT DIAGNOSTICS:");
    System.out.println("==================================================");
    double bpm = project.getBpm();
    for (int i = 0; i < project.getTracks().size(); i++) {
      org.deluge.model.TrackModel track = project.getTracks().get(i);
      final int trackIdx = i;
      java.util.List<org.deluge.model.ArrangerClip> acList =
          project.getArrangerTimeline().stream().filter(ac -> ac.trackIndex() == trackIdx).toList();
      int totalNotes = 0;
      for (org.deluge.model.ClipModel clip : track.getClips()) {
        for (int r = 0; r < clip.getRowCount(); r++) {
          for (int s = 0; s < clip.getStepCount(); s++) {
            if (clip.getStep(r, s) != null && clip.getStep(r, s).active()) {
              totalNotes++;
            }
          }
        }
      }
      System.out.println(
          String.format(
              "  Track %d: [%s] '%s' - Arranger Clips: %d, Session Notes: %d",
              i + 1, track.getClass().getSimpleName(), track.getName(), acList.size(), totalNotes));

      // Print start and duration for arranger clips on this track
      for (var ac : acList) {
        double startSec = ac.startTicks() * (60.0 / (bpm * 96.0));
        double durSec = ac.durationTicks() * (60.0 / (bpm * 96.0));
        System.out.println(
            String.format(
                "    -> Arranger Clip: start=%d ticks (%.2f s), duration=%d ticks (%.2f s)",
                ac.startTicks(), startSec, ac.durationTicks(), durSec));
      }
    }
    System.out.println("==================================================");

    System.out.println("==================================================");
    System.out.println("RENDERING 80.0 SECONDS OF BILLIE JEAN STEMS...");
    System.out.println("==================================================");
    org.deluge.project.ExportHelper.exportStems(
        project,
        targetDir,
        80.0,
        "Michael Jackson - Billie Jean",
        (status, percent) -> {
          System.out.println(String.format("  [%d%%] %s", percent, status));
        });
    System.out.println("==================================================");
    System.out.println(
        "REPORTS: Render complete. Stems written to: " + targetDir.getAbsolutePath());
    System.out.println("==================================================");
  }
}
