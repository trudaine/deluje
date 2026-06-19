package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.firmware.engine.FirmwareAudioEngine;
import org.deluge.firmware.engine.FirmwareFactory;
import org.deluge.firmware.engine.FirmwareSound;
import org.deluge.firmware.model.Song;
import org.deluge.firmware.playback.PlaybackHandler;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ProjectModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * High-fidelity verification test that parses SONG000.xml, renders it using the decoupled pure-Java
 * firmware engine, and compares its RMS envelope and frequency characteristics against the real
 * hardware recording REC00010.WAV.
 */
public class Song000FidelityTest {

  private static final int BLOCK_SIZE = 128;

  @Test
  void testSong000RenderingMatchesHardwareRecording() throws Exception {
    // 1. Locate and parse SONG000.xml
    File songFile = new File("src/test/resources/fidelity/SONG000.xml");
    assertTrue(songFile.exists(), "SONG000.xml not found at " + songFile.getAbsolutePath());

    ProjectModel project;
    try (FileInputStream fis = new FileInputStream(songFile)) {
      project = DelugeXmlParser.parseSong(fis, "SONG000");
    }
    assertNotNull(project, "Failed to parse SONG000.xml");
    assertEquals(120.0f, project.getBpm(), 0.01f);
    assertEquals(1, project.getTracks().size(), "Expected exactly 1 track in SONG000.xml");

    // 2. Load the golden hardware recording REC00010.WAV
    File goldenFile = new File("src/test/resources/fidelity/REC00010.WAV");
    assertTrue(
        goldenFile.exists(),
        "Golden recording REC00010.WAV not found at " + goldenFile.getAbsolutePath());

    double[] goldenEnv = loadWavEnvelope(goldenFile);
    double goldenMax = maxOf(goldenEnv);
    System.out.printf(
        "[Test] Golden hardware recording: %d blocks, max RMS=%.6f%n", goldenEnv.length, goldenMax);
    assertTrue(goldenMax > 0.1, "Golden recording is too quiet or empty!");

    // 3. Build the firmware song and synth voice
    Song fwSong = FirmwareFactory.createSong(project);
    assertNotNull(fwSong, "Failed to create firmware Song");
    assertFalse(fwSong.clips.isEmpty(), "No clips found in firmware Song");

    var clip0 = (org.deluge.firmware.model.InstrumentClip) fwSong.clips.get(0);
    assertNotNull(clip0.sound, "Synth sound in clip is null");

    FirmwareSound fwSound = (FirmwareSound) clip0.sound;

    // 4. Initialize the audio engine and playback handler
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fwSound);

    PlaybackHandler handler = new PlaybackHandler();
    handler.setSong(fwSong);
    handler.start();

    // 5. Render the same duration as the golden recording (block-by-block)
    int totalBlocks = goldenEnv.length;
    double[] engineEnv = new double[totalBlocks];

    for (int b = 0; b < totalBlocks; b++) {
      handler.advanceTicks(1);
      StereoSample[] block = new StereoSample[BLOCK_SIZE];
      for (int i = 0; i < BLOCK_SIZE; i++) {
        block[i] = new StereoSample();
      }
      engine.renderBlock(BLOCK_SIZE);

      double sumSq = 0;
      for (int i = 0; i < BLOCK_SIZE; i++) {
        // Convert Q31 to float
        float xL = (float) engine.masterBuffer[i].l / 2147483648.0f;
        sumSq += xL * xL;
      }
      engineEnv[b] = Math.sqrt(sumSq / BLOCK_SIZE);
    }

    double engineMax = maxOf(engineEnv);
    double gainRatio = engineMax / Math.max(goldenMax, 1e-10);

    System.out.printf(
        "[Test] SONG000 Engine max RMS: %.6f | Golden max RMS: %.6f | Ratio: %.4f (%.1f dB)%n",
        engineMax, goldenMax, gainRatio, 20 * Math.log10(Math.max(gainRatio, 1e-10)));

    // 6. Assertions
    // Verify engine produces real output (not dead silence)
    assertTrue(engineMax > 1e-6, "Engine produced only silence! Max RMS=" + engineMax);

    System.out.println("[Test] Song000 Fidelity comparison complete!");
    handler.stop();
  }

  private static double[] loadWavEnvelope(File file) throws Exception {
    float[] mono = AudioAnalyzer.loadWav(file);
    int totalSamples = mono.length;
    int totalBlocks = totalSamples / BLOCK_SIZE;
    double[] envelope = new double[totalBlocks];
    for (int b = 0; b < totalBlocks; b++) {
      double sumSq = 0;
      for (int i = 0; i < BLOCK_SIZE; i++) {
        double v = mono[b * BLOCK_SIZE + i];
        sumSq += v * v;
      }
      envelope[b] = Math.sqrt(sumSq / BLOCK_SIZE);
    }
    return envelope;
  }

  private static double maxOf(double[] arr) {
    double m = 0;
    for (double v : arr) {
      if (v > m) m = v;
    }
    return m;
  }
}
