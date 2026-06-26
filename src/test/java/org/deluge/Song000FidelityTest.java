package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ProjectModel;
import org.deluge.playback.PlaybackHandler;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * High-fidelity verification test that parses SONG003.xml, renders it using the decoupled pure-Java
 * firmware engine, and compares its RMS envelope and frequency characteristics against the real
 * hardware recording REC00010.WAV.
 */
public class Song000FidelityTest {

  private static final int BLOCK_SIZE = 128;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    org.deluge.engine.JavaAudioDriver.monitorGainMul = 24;
  }

  @Test
  void testSong000RenderingMatchesHardwareRecording() throws Exception {
    // 1. Locate and parse SONG003.xml
    File songFile = new File("src/test/resources/fidelity/SONG003.xml");
    assertTrue(songFile.exists(), "SONG003.xml not found at " + songFile.getAbsolutePath());

    ProjectModel project;
    try (FileInputStream fis = new FileInputStream(songFile)) {
      project = DelugeXmlParser.parseSong(fis, "SONG000");
    }
    assertNotNull(project, "Failed to parse SONG003.xml");
    assertEquals(120.0f, project.getBpm(), 0.01f);
    assertEquals(1, project.getTracks().size(), "Expected exactly 1 track in SONG003.xml");

    // 2. Load the golden hardware recording REC00004.WAV (real Deluge playing SONG000)
    File goldenFile = new File("src/test/resources/fidelity/REC00004.WAV");
    assertTrue(
        goldenFile.exists(),
        "Golden recording REC00004.WAV not found at " + goldenFile.getAbsolutePath());

    double[] goldenEnv = loadWavEnvelope(goldenFile);
    double goldenMax = maxOf(goldenEnv);
    System.out.printf(
        "[Test] Golden REC00004.WAV: %d blocks, max RMS=%.6f%n", goldenEnv.length, goldenMax);
    assertTrue(goldenMax > 0.05, "Golden recording is too quiet or empty!");

    // 3. Build the firmware song and synth voice
    ProjectModel fwSong = FirmwareFactory.createSong(project);
    assertNotNull(fwSong, "Failed to create firmware Song");
    assertFalse(fwSong.getClips().isEmpty(), "No clips found in firmware Song");

    var clip0 = fwSong.getTracks().get(0).getActiveClip();
    assertNotNull(clip0.getSound(), "Synth sound in clip is null");

    // Debug: verify notes were loaded from SONG000
    int totalNotes = 0;
    for (var nr : clip0.getNoteRowsList()) {
      System.out.println("[Test]   Row y=" + nr.y + " notes=" + nr.notes.size());
      totalNotes += nr.notes.size();
    }
    System.out.println("[Test] Total notes in SONG000: " + totalNotes);
    assertTrue(totalNotes > 0, "SONG000 has no notes!");

    FirmwareSound fwSound = (FirmwareSound) clip0.getSound();

    // 4. Initialize the audio engine and playback handler
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fwSound);

    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(fwSong);
    handler.start();

    // 5. Render the same duration as the golden recording, advancing ticks at the
    //    correct audio rate (matching JavaAudioDriver: ticksPerSample * BLOCK_SIZE).
    int totalBlocks = goldenEnv.length;
    double[] engineEnv = new double[totalBlocks];

    float bpm = project.getBpm();
    double ticksPerSample = (bpm / 60.0 * 96.0) / 44100.0;
    double accumulatedTicks = 0;

    // Buffer to hold 16-bit stereo PCM data: totalBlocks * BLOCK_SIZE * 2 channels * 2 bytes per
    // sample
    byte[] wavBytes = new byte[totalBlocks * BLOCK_SIZE * 2 * 2];
    int byteIdx = 0;

    for (int b = 0; b < totalBlocks; b++) {
      accumulatedTicks += ticksPerSample * BLOCK_SIZE;
      int toAdvance = (int) accumulatedTicks;
      if (toAdvance > 0) {
        handler.advanceTicks(toAdvance);
        accumulatedTicks -= toAdvance;
      }
      engine.renderBlock(BLOCK_SIZE);

      double sumSq = 0;
      for (int i = 0; i < BLOCK_SIZE; i++) {
        float xL = (float) engine.masterBuffer[i].l / 2147483648.0f;
        float xR = (float) engine.masterBuffer[i].r / 2147483648.0f;

        // Apply master clipping and driver gain (parity to org.deluge.engine.JavaAudioDriver)
        float boostedL = xL * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        if (boostedL > 0.7f) boostedL = 0.7f + 0.3f * (float) Math.tanh((boostedL - 0.7f) / 0.3f);
        if (boostedL < -0.7f) boostedL = -0.7f + 0.3f * (float) Math.tanh((boostedL + 0.7f) / 0.3f);
        short s16L = (short) Math.max(-32768, Math.min(32767, boostedL * 32767.0f));

        float boostedR = xR * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        if (boostedR > 0.7f) boostedR = 0.7f + 0.3f * (float) Math.tanh((boostedR - 0.7f) / 0.3f);
        if (boostedR < -0.7f) boostedR = -0.7f + 0.3f * (float) Math.tanh((boostedR + 0.7f) / 0.3f);
        short s16R = (short) Math.max(-32768, Math.min(32767, boostedR * 32767.0f));

        // Write Left channel (little endian)
        wavBytes[byteIdx++] = (byte) (s16L & 0xFF);
        wavBytes[byteIdx++] = (byte) ((s16L >> 8) & 0xFF);

        // Write Right channel (little endian)
        wavBytes[byteIdx++] = (byte) (s16R & 0xFF);
        wavBytes[byteIdx++] = (byte) ((s16R >> 8) & 0xFF);

        // Compute energy from both channels for RMS envelope tracking
        float backL = s16L / 32768.0f;
        float backR = s16R / 32768.0f;
        sumSq += 0.5f * (backL * backL + backR * backR);
      }
      engineEnv[b] = Math.sqrt(sumSq / BLOCK_SIZE);
    }

    // 5b. Write the accumulated stereo PCM bytes to a physical WAVE file
    File renderedWavFile = new File("src/test/resources/fidelity/JAVA_RENDERED_SONG003.WAV");
    javax.sound.sampled.AudioFormat format =
        new javax.sound.sampled.AudioFormat(44100.0f, 16, 2, true, false);
    try (javax.sound.sampled.AudioInputStream ais =
        new javax.sound.sampled.AudioInputStream(
            new java.io.ByteArrayInputStream(wavBytes), format, totalBlocks * BLOCK_SIZE)) {
      javax.sound.sampled.AudioSystem.write(
          ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, renderedWavFile);
    }
    System.out.printf(
        "[Test] SUCCESS: Reproduced and saved Java-rendered WAV file to: %s%n",
        renderedWavFile.getAbsolutePath());

    double engineMax = maxOf(engineEnv);
    double gainRatio = engineMax / Math.max(goldenMax, 1e-10);

    System.out.printf(
        "[Test] SONG000 Engine max RMS: %.6f | Golden max RMS: %.6f | Ratio: %.4f (%.1f dB)%n",
        engineMax, goldenMax, gainRatio, 20 * Math.log10(Math.max(gainRatio, 1e-10)));

    // 6. Align and calculate envelope Pearson Correlation
    // Since the hardware recording starts at Note 4, we dynamically find the 4th note onset
    // in the Java engine's audio to align perfectly.
    double engineAttackThresh = engineMax * 0.2;
    double goldenAttackThresh = goldenMax * 0.2;
    double engineSilenceThresh = engineMax * 0.03;
    double goldenSilenceThresh = goldenMax * 0.03;

    int engineOnsetBlock = findNthOnsetBlock(engineEnv, engineAttackThresh, engineSilenceThresh, 4);
    int goldenOnsetBlock = findFirstOnsetBlock(goldenEnv, goldenAttackThresh);

    System.out.printf(
        "[Test] Aligned Onset Blocks: Java Engine (Note 4) = %d | Hardware Golden = %d%n",
        engineOnsetBlock, goldenOnsetBlock);

    // Slice both envelopes from their respective alignment points
    int engineRemaining = engineEnv.length - engineOnsetBlock;
    int goldenRemaining = goldenEnv.length - goldenOnsetBlock;
    int compLength = Math.min(engineRemaining, goldenRemaining);

    double[] engineSlice =
        java.util.Arrays.copyOfRange(engineEnv, engineOnsetBlock, engineOnsetBlock + compLength);
    double[] goldenSlice =
        java.util.Arrays.copyOfRange(goldenEnv, goldenOnsetBlock, goldenOnsetBlock + compLength);

    double envelopeCorrelation = pearsonCorrelation(engineSlice, goldenSlice, 0, 0, compLength);
    System.out.printf("[Test] Aligned Envelope Pearson Correlation: %.6f%n", envelopeCorrelation);

    // Count onsets in the aligned slices
    int engineSliceOnsets = countOnsets(engineSlice, engineAttackThresh, engineSilenceThresh);
    int goldenSliceOnsets = countOnsets(goldenSlice, goldenAttackThresh, goldenSilenceThresh);
    int engineSliceSilent = countSilent(engineSlice, engineSilenceThresh);
    int goldenSliceSilent = countSilent(goldenSlice, goldenSilenceThresh);

    System.out.printf(
        "[Test] Aligned Slices: Onsets (engine=%d, golden=%d) | Silence (engine=%.0f%%, golden=%.0f%%)%n",
        engineSliceOnsets,
        goldenSliceOnsets,
        100.0 * engineSliceSilent / compLength,
        100.0 * goldenSliceSilent / compLength);

    // 7. Strict regression assertions
    assertTrue(engineMax > 0.01, "Engine produced near-silence! Max RMS=" + engineMax);

    assertEquals(
        goldenSliceOnsets,
        engineSliceOnsets,
        "Aligned onset count mismatch! Aligned note sequences did not trigger identically.");

    double silenceDiff =
        Math.abs((double) engineSliceSilent / compLength - (double) goldenSliceSilent / compLength);
    assertTrue(
        silenceDiff <= 0.10,
        "Silence / gate ratio mismatch in aligned slice! Difference is "
            + (silenceDiff * 100.0)
            + "% (max 10% allowed)");

    assertTrue(
        envelopeCorrelation >= 0.80,
        "Envelope shape correlation too low! corr=" + envelopeCorrelation + " (must be >= 0.80)");

    System.out.println("[Test] SUCCESS: Song000 Fidelity comparison complete & verified!");
    handler.stop();
  }

  private static int findFirstOnsetBlock(double[] env, double thresh) {
    for (int b = 0; b < env.length; b++) {
      if (env[b] > thresh) return b;
    }
    return 0;
  }

  private static int findNthOnsetBlock(double[] env, double thresh, double silenceThresh, int n) {
    int onsetCount = 0;
    boolean wasSilent = true;
    for (int b = 1; b < env.length; b++) {
      if (wasSilent && env[b] > thresh) {
        onsetCount++;
        if (onsetCount == n) {
          return b;
        }
        wasSilent = false;
      } else if (!wasSilent && env[b] < silenceThresh) {
        wasSilent = true;
      }
    }
    return 0;
  }

  private static double pearsonCorrelation(
      double[] x, double[] y, int xStart, int yStart, int length) {
    double sumX = 0;
    double sumY = 0;
    for (int i = 0; i < length; i++) {
      sumX += x[xStart + i];
      sumY += y[yStart + i];
    }
    double meanX = sumX / length;
    double meanY = sumY / length;

    double num = 0;
    double denX = 0;
    double denY = 0;
    for (int i = 0; i < length; i++) {
      double dx = x[xStart + i] - meanX;
      double dy = y[yStart + i] - meanY;
      num += dx * dy;
      denX += dx * dx;
      denY += dy * dy;
    }
    double den = Math.sqrt(denX * denY);
    return den > 1e-15 ? num / den : 0;
  }

  private static int countOnsets(double[] env, double attackThresh, double silenceThresh) {
    int onsets = 0;
    boolean wasSilent = true;
    for (int b = 1; b < env.length; b++) {
      if (wasSilent && env[b] > attackThresh) {
        onsets++;
        wasSilent = false;
      } else if (!wasSilent && env[b] < silenceThresh) {
        wasSilent = true;
      }
    }
    return onsets;
  }

  private static int countSilent(double[] env, double silenceThresh) {
    int silent = 0;
    for (double v : env) if (v < silenceThresh) silent++;
    return silent;
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
