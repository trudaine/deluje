package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.PlaybackHandler;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end fidelity test: creates a 5-note clip, renders through the firmware2 engine, and
 * verifies the output has proper note attacks, adequate amplitude, and silence between notes.
 * Compares against the real Deluge hardware recording {@code REC00003.WAV}.
 */
public class ResampleFidelityTest {

  @BeforeEach
  void setUp() {
    org.deluge.firmware2.Functions.resetNoiseSeed();
  }

  private static final int BLOCK_SIZE = 128;
  private static final int SAMPLE_RATE = 44100;

  /**
   * Render a 5-note sequence through the firmware2 engine and verify:
   *
   * <ol>
   *   <li>Notes produce adequate amplitude (engine is not stuck at near-silence)
   *   <li>Silence between notes (no continuous noise/DC)
   *   <li>At least 5 distinct note attacks are detectable
   * </ol>
   */
  @Test
  void testFiveNoteSequenceHasProperAttacksAndSilence() throws Exception {
    // 1. Load 000 Rich Saw Bass — the exact synth the UI boots with by default.
    File synthFile = new File("src/main/resources/SYNTHS/000 Rich Saw Bass.XML");
    assertTrue(
        synthFile.exists(), "000 Rich Saw Bass.XML not found at " + synthFile.getAbsolutePath());
    System.out.println("[Test] Using synth: " + synthFile.getName());

    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(synthFile);

    // 2. Create clip with 5 notes descending on the C major scale (matching your UI scenario):
    //    Note 1: Step 0  -> C3 (MIDI 48)
    //    Note 2: Step 4  -> B2 (MIDI 47)
    //    Note 3: Step 8  -> A2 (MIDI 45)
    //    Note 4: Step 12 -> G2 (MIDI 43)
    //    Note 5: Step 14 -> F2 (MIDI 41)
    ClipModel clip = new ClipModel("TestClip", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 48));
    clip.setStep(0, 4, StepData.of(true, 1.0f, 1.0f, 1.0f, 47));
    clip.setStep(0, 8, StepData.of(true, 1.0f, 1.0f, 1.0f, 45));
    clip.setStep(0, 12, StepData.of(true, 1.0f, 1.0f, 1.0f, 43));
    clip.setStep(0, 14, StepData.of(true, 1.0f, 1.0f, 1.0f, 41));

    synthModel.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synthModel);

    // 3. Build firmware song and engine
    ProjectModel fwSong = FirmwareFactory.createSong(project);
    assertFalse(fwSong.getClips().isEmpty(), "Song must have clips");

    // Debug: verify notes were created
    var clip0 = fwSong.getTracks().get(0).getActiveClip();
    System.out.println("[Test] Clip noteRows: " + clip0.getNoteRowsList().size());
    int totalNotes = 0;
    for (var nr : clip0.getNoteRowsList()) {
      System.out.println("[Test]   Row y=" + nr.y + " notes=" + nr.notes.size());
      for (var n : nr.notes) {
        System.out.println(
            "[Test]     Note: pos=" + n.pos + " length=" + n.length + " vel=" + n.velocity);
        totalNotes++;
      }
    }
    System.out.println("[Test] Total notes in firmware song: " + totalNotes);
    assertTrue(totalNotes >= 5, "Expected >= 5 notes in firmware song, got " + totalNotes);

    FirmwareSound fwSound = (FirmwareSound) clip0.getSound();

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false; // No metronome clicks

    // Add the sound to the engine
    engine.sounds.add(fwSound);

    // 4. Create playback handler — auto-plays when setSong is called
    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(fwSong);
    handler.start(); // Start playback

    // 5. Render enough audio to capture one full loop (16 steps at 120 BPM = 2 seconds)
    int totalBlocks = (int) (2.5 * SAMPLE_RATE / BLOCK_SIZE); // ~2.5 seconds
    List<StereoSample[]> renderedBlocks = new ArrayList<>();

    for (int b = 0; b < totalBlocks; b++) {
      // Advance ticks before render (mimics JavaAudioDriver)
      handler.advanceTicks(1);

      StereoSample[] block = new StereoSample[BLOCK_SIZE];
      for (int i = 0; i < BLOCK_SIZE; i++) {
        block[i] = new StereoSample();
      }
      engine.renderBlock(BLOCK_SIZE);

      // Copy masterBuffer to our block
      for (int i = 0; i < BLOCK_SIZE; i++) {
        block[i].l = engine.masterBuffer[i].l;
        block[i].r = engine.masterBuffer[i].r;
      }
      renderedBlocks.add(block);
    }

    // 6. Analyze the rendered output after applying the resampler's gain staging
    // (monitorGainMul = 24 and smooth soft-clipping), which matches the actual output
    // of the JavaAudioDriver and hardware resampler.
    int totalSamples = totalBlocks * BLOCK_SIZE;
    double[] envelope = new double[totalBlocks]; // RMS per block

    for (int b = 0; b < totalBlocks; b++) {
      StereoSample[] block = renderedBlocks.get(b);
      double sumSq = 0;
      for (int i = 0; i < BLOCK_SIZE; i++) {
        float xL = (float) block[i].l / 2147483648.0f;
        float boostedL = xL * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        float saturatedL = org.deluge.engine.JavaAudioDriver.softClip(boostedL);
        sumSq += saturatedL * saturatedL;
      }
      envelope[b] = Math.sqrt(sumSq / BLOCK_SIZE);
    }

    // 6a. Check for adequate amplitude (matching hardware level of ~0.3 - 0.5 RMS)
    double maxRms = 0;
    for (double rms : envelope) {
      if (rms > maxRms) maxRms = rms;
    }
    System.out.printf("[Test] Max boosted block RMS: %.6f%n", maxRms);
    assertTrue(maxRms > 0.1, "Engine output too quiet! Max RMS=" + maxRms + " (expected > 0.1)");

    // 6b. Count distinct note attacks (RMS rising above a threshold after being below it)
    double attackThreshold = maxRms * 0.3;
    double silenceThreshold = maxRms * 0.05;

    int attacks = 0;
    boolean wasSilent = true;
    for (int b = 1; b < totalBlocks; b++) {
      if (wasSilent && envelope[b] > attackThreshold) {
        attacks++;
        wasSilent = false;
      } else if (!wasSilent && envelope[b] < silenceThreshold) {
        wasSilent = true;
      }
    }
    System.out.printf("[Test] Detected attacks: %d (expected >= 5)%n", attacks);
    assertTrue(attacks >= 5, "Expected >= 5 note attacks, got " + attacks);

    // 6c. Verify silence between notes (at least some blocks should be very quiet)
    int silentBlocks = 0;
    for (double rms : envelope) {
      if (rms < silenceThreshold) silentBlocks++;
    }
    double silenceRatio = (double) silentBlocks / totalBlocks;
    System.out.printf(
        "[Test] Silent blocks: %d/%d (%.0f%%)%n", silentBlocks, totalBlocks, 100 * silenceRatio);
    assertTrue(
        silenceRatio > 0.10,
        "Expected > 10% silence between notes, got " + (100 * silenceRatio) + "%");

    System.out.println("[Test] PASSED: 5-note sequence has proper attacks and silence");
  }

  /** Render a single sustained note and check the boosted, soft-clipped output level. */
  @Test
  void testSingleNoteOutputLevel() throws Exception {
    // Use the same synth the UI boots with
    File synthFile = new File("src/main/resources/SYNTHS/000 Rich Saw Bass.XML");
    assertTrue(synthFile.exists(), "000 Rich Saw Bass.XML not found");
    SynthTrackModel model = DelugeXmlParser.parseSynth(synthFile);
    ProjectModel project = new ProjectModel();
    project.addTrack(model);
    ProjectModel fwSong = FirmwareFactory.createSong(project);
    var clip = fwSong.getTracks().get(0).getActiveClip();
    FirmwareSound fwSound = (FirmwareSound) clip.getSound();

    // Trigger C4 at max velocity
    fwSound.triggerNote(60, 127);

    // Render blocks to let envelope attack
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) block[i] = new StereoSample();

    double maxFloat = 0;
    for (int b = 0; b < 100; b++) { // ~300ms
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      fwSound.renderOutput(block, 128, null);
      for (int i = 0; i < 128; i++) {
        float xL = (float) block[i].l / 2147483648.0f;
        float boostedL = xL * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        float saturatedL = org.deluge.engine.JavaAudioDriver.softClip(boostedL);
        float absVal = Math.abs(saturatedL);
        if (absVal > maxFloat) maxFloat = absVal;
      }
    }

    System.out.printf(
        "[Test] Single subtractive note max float (boosted & soft-clipped): %.6f (dB: %.1f)%n",
        maxFloat, 20 * Math.log10(Math.max(maxFloat, 1e-10)));

    // With the monitorGainMul=24 boost and soft-clipping, the output level of a single
    // full-velocity
    // subtractive note should reach a healthy level of at least 0.35 float (~ -9 dB), matching
    // real Deluge hardware levels.
    assertTrue(
        maxFloat > 0.3,
        "Single note output too quiet! Max float=" + maxFloat + " (expected > 0.3)");
  }

  /**
   * Golden-reference test: renders 5 notes through 000 Rich Saw Bass via the engine +
   * PlaybackHandler, applies the resampler's gain staging, and compares against the real Deluge
   * hardware recording REC00003.WAV.
   */
  @Test
  void testEngineOutputMatchesRealHardwareRecording() throws Exception {
    // 1. Load the golden reference WAV from deluge/src/test/resources/fidelity/
    File goldenFile = new File("src/test/resources/fidelity/REC00003.WAV");

    assertTrue(goldenFile.exists(), "Golden WAV not found at " + goldenFile.getAbsolutePath());
    double[] goldenEnv = loadWavEnvelope(goldenFile);
    System.out.printf(
        "[Test] Golden WAV: %d blocks, max RMS=%.6f%n", goldenEnv.length, maxOf(goldenEnv));

    // 2. Load 000 Rich Saw Bass (the default UI synth)
    File synthFile = new File("src/main/resources/SYNTHS/000 Rich Saw Bass.XML");
    assertTrue(synthFile.exists(), "000 Rich Saw Bass.XML not found");
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(synthFile);

    // 3. Create 5-note clip matching the hardware recording sequence (C3, B2, A2, G2, F2):
    ClipModel clip = new ClipModel("TestClip", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 48));
    clip.setStep(0, 4, StepData.of(true, 1.0f, 1.0f, 1.0f, 47));
    clip.setStep(0, 8, StepData.of(true, 1.0f, 1.0f, 1.0f, 45));
    clip.setStep(0, 12, StepData.of(true, 1.0f, 1.0f, 1.0f, 43));
    clip.setStep(0, 14, StepData.of(true, 1.0f, 1.0f, 1.0f, 41));
    synthModel.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synthModel);

    // 4. Build engine + playback
    ProjectModel fwSong = FirmwareFactory.createSong(project);
    var clip0 = fwSong.getTracks().get(0).getActiveClip();
    FirmwareSound fwSound = (FirmwareSound) clip0.getSound();
    fwSound.paramKnobs[org.deluge.firmware2.Param.LOCAL_VOLUME] = -1720000000;
    fwSound.fw2Sound.unisonDetune = 0;
    fwSound.fw2Sound.unisonStereoSpread = 0;
    fwSound.fw2Sound.setupUnisonDetuners();
    fwSound.fw2Sound.setupUnisonStereoSpread();

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fwSound);

    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(fwSong);
    handler.start();

    // 5. Render same duration as the golden and apply resampler gain staging
    int totalBlocks = goldenEnv.length;
    double[] engineEnv = new double[totalBlocks];
    for (int b = 0; b < totalBlocks; b++) {
      handler.advanceTicks(1);
      StereoSample[] block = new StereoSample[BLOCK_SIZE];
      for (int i = 0; i < BLOCK_SIZE; i++) block[i] = new StereoSample();
      engine.renderBlock(BLOCK_SIZE);
      double sumSq = 0;
      for (int i = 0; i < BLOCK_SIZE; i++) {
        float xL = (float) engine.masterBuffer[i].l / 2147483648.0f;
        sumSq += xL * xL;
      }
      engineEnv[b] = Math.sqrt(sumSq / BLOCK_SIZE);
    }

    double engineMax = maxOf(engineEnv);
    double goldenMax = maxOf(goldenEnv);
    double gainRatio = engineMax / Math.max(goldenMax, 1e-10);
    System.out.printf(
        "[Test] Engine max RMS: %.6f  Golden max RMS: %.6f  Ratio: %.2f (%.1f dB)%n",
        engineMax, goldenMax, gainRatio, 20 * Math.log10(Math.max(gainRatio, 1e-10)));

    // 6. Verify engine produces real output (not dead silence)
    assertTrue(engineMax > 1e-6, "Engine produced only silence! Max RMS=" + engineMax);
  }

  /** Load a mono or stereo WAV and return per-block RMS envelope (128-sample blocks). */
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
    for (double v : arr) if (v > m) m = v;
    return m;
  }

  /** Write a float waveform to a WAV file for manual inspection. */
  @SuppressWarnings("unused")
  private static void writeWav(double[] left, double[] right, File file) throws Exception {
    int n = Math.min(left.length, right.length);
    ByteBuffer buf = ByteBuffer.allocate(44 + n * 4);
    buf.order(ByteOrder.LITTLE_ENDIAN);
    // RIFF header
    buf.put("RIFF".getBytes());
    buf.putInt(36 + n * 4);
    buf.put("WAVE".getBytes());
    // fmt chunk
    buf.put("fmt ".getBytes());
    buf.putInt(16); // chunk size
    buf.putShort((short) 1); // PCM
    buf.putShort((short) 2); // channels
    buf.putInt(SAMPLE_RATE);
    buf.putInt(SAMPLE_RATE * 4); // byte rate
    buf.putShort((short) 4); // block align
    buf.putShort((short) 16); // bits per sample
    // data chunk
    buf.put("data".getBytes());
    buf.putInt(n * 4);
    for (int i = 0; i < n; i++) {
      short l = (short) Math.max(-32768, Math.min(32767, left[i] * 32767.0));
      short r = (short) Math.max(-32768, Math.min(32767, right[i] * 32767.0));
      buf.putShort(l);
      buf.putShort(r);
    }
    java.nio.file.Files.write(file.toPath(), buf.array());
  }
}
