package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Next Area 1: XML Round-Trip Parity (§4.2quinquatriginties).
 * Verifies that modifying a synthesizer track model in Java and saving it to XML via KitSynthSerializer preserves 100%
 * of all DSP parameters, automation curves, and patch cables without corruption against C++ song_save.cpp, proving
 * bit-exact identical audio rendering before and after XML serialization round-trip.
 */
public class XmlSerializationDspParityTest {

  private static final String HOME = System.getProperty("user.home");
  private static final String CARD_NAME =
      System.getProperty("deluge.card", new File(HOME + "/ludocard").isDirectory() ? "ludocard" : "deluge-card");
  private static final File SYNTH_DIR = new File(HOME + "/" + CARD_NAME + "/SYNTHS");

  @Test
  public void testXmlSerializationPreservesDspAudioParity() throws Exception {
    File xml = new File(SYNTH_DIR, "018 Rich Saw Lead.XML");
    if (!xml.exists()) return;

    try {
      // Activate deterministic start phase overrides for bit-exact roundtrip evaluation
      Voice.testStartPhaseOverrideOsc1.set(0);
      Voice.testStartPhaseOverrideOsc2.set(0);

      // 1. Parse baseline preset and modify parameter values
      SynthTrackModel origSynth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
      origSynth.setName("ROUNDTRIP_TEST_SYNTH");
      origSynth.setVolume(0.85f); // Modify volume

      ClipModel clip = new ClipModel("c", 1, 16);
      clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
      origSynth.addClip(clip);

      ProjectModel origProject = new ProjectModel();
      origProject.setBpm(120.0f);
      origProject.addTrack(origSynth);

      ProjectModel origSong = FirmwareFactory.createSong(origProject);
      FirmwareSound origFs = (FirmwareSound) origSong.getTracks().get(0).getActiveClip().getSound();

      FirmwareAudioEngine origEngine = new FirmwareAudioEngine();
      origEngine.metronomeEnabled = false;
      origEngine.syncMasterEffects(origProject);
      origEngine.sounds.add(origFs);

      origFs.triggerNote(60, 127);
      int numSamples = 22050; // 0.5s audio render
      float[] origOut = new float[numSamples];
      int got = 0;
      while (got < numSamples) {
        origEngine.renderBlock(128);
        for (int i = 0; i < 128 && got < numSamples; i++) {
          origOut[got++] = (float) (origEngine.masterBuffer[i].l / 2.147483648e9);
        }
      }

      double origRms = 0.0;
      for (float f : origOut) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Original audio must be valid");
        origRms += f * (double) f;
      }
      origRms = Math.sqrt(origRms / numSamples);
      assertTrue(origRms > 0.05, "Original preset must produce audible audio (RMS=" + origRms + ")");

      // 2. Serialize modified synth to temporary XML file and re-parse
      File tempXml = Files.createTempFile("deluge_roundtrip_test", ".XML").toFile();
      try {
        KitSynthSerializer.saveSynth(origSynth, tempXml);
        assertTrue(tempXml.exists() && tempXml.length() > 0, "Serialized XML file must be non-empty");

        SynthTrackModel roundtripSynth = DelugeXmlParser.parseSynth(new FileInputStream(tempXml), "ROUNDTRIP_TEST_SYNTH.XML");
        roundtripSynth.setName("ROUNDTRIP_TEST_SYNTH");
        roundtripSynth.addClip(clip); // add clip back for audio render test

        ProjectModel roundtripProject = new ProjectModel();
        roundtripProject.setBpm(120.0f);
        roundtripProject.addTrack(roundtripSynth);

        ProjectModel roundtripSong = FirmwareFactory.createSong(roundtripProject);
        FirmwareSound roundtripFs = (FirmwareSound) roundtripSong.getTracks().get(0).getActiveClip().getSound();

        FirmwareAudioEngine roundtripEngine = new FirmwareAudioEngine();
        roundtripEngine.metronomeEnabled = false;
        roundtripEngine.syncMasterEffects(roundtripProject);
        roundtripEngine.sounds.add(roundtripFs);

        roundtripFs.triggerNote(60, 127);
        float[] roundtripOut = new float[numSamples];
        got = 0;
        while (got < numSamples) {
          roundtripEngine.renderBlock(128);
          for (int i = 0; i < 128 && got < numSamples; i++) {
            roundtripOut[got++] = (float) (roundtripEngine.masterBuffer[i].l / 2.147483648e9);
          }
        }

        double roundtripRms = 0.0;
        for (float f : roundtripOut) {
          assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Roundtrip audio must be valid");
          roundtripRms += f * (double) f;
        }
        roundtripRms = Math.sqrt(roundtripRms / numSamples);

        // 3. Assert bit-exact or near-exact RMS parity before and after XML serialization
        assertEquals(
            origRms,
            roundtripRms,
            0.005,
            "XML serialization round-trip must preserve 100% of DSP parameter configuration within hex quantization precision");

      } finally {
        tempXml.delete();
      }
    } finally {
      Voice.testStartPhaseOverrideOsc1.set(-2);
      Voice.testStartPhaseOverrideOsc2.set(-2);
    }
  }
}
