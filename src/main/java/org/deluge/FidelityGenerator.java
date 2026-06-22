package org.deluge;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.PlaybackHandler;
import org.deluge.playback.Song;
import org.deluge.xml.DelugeXmlParser;

/**
 * Diagnostic generator utility: renders the exact 5-note descending sequence (C3, B2, A2, G2, F2)
 * using the 000 Rich Saw Bass preset, applies master resample gain scaling and soft-clipping, and
 * saves the result to a WAV file.
 */
public class FidelityGenerator {

  private static final int BLOCK_SIZE = 128;
  private static final int SAMPLE_RATE = 44100;

  public static void main(String[] args) throws Exception {
    System.out.println("[Generator] Initializing Deluge engine...");
    org.deluge.firmware2.Functions.resetNoiseSeed();

    // 1. Load the default UI synth
    File synthFile = new File("src/main/resources/SYNTHS/000 Rich Saw Bass.XML");
    if (!synthFile.exists()) {
      System.err.println("Error: 000 Rich Saw Bass.XML not found!");
      System.exit(1);
    }
    System.out.println("[Generator] Loaded synth: " + synthFile.getName());
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(synthFile);

    // 2. Program the exact 5-note descending sequence:
    //    Note 1: Step 0  -> C3 (MIDI 48)
    //    Note 2: Step 4  -> B2 (MIDI 47)
    //    Note 3: Step 8  -> A2 (MIDI 45)
    //    Note 4: Step 12 -> G2 (MIDI 43)
    //    Note 5: Step 14 -> F2 (MIDI 41)
    ClipModel clip = new ClipModel("FidelityClip", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 48));
    clip.setStep(0, 4, StepData.of(true, 1.0f, 1.0f, 1.0f, 47));
    clip.setStep(0, 8, StepData.of(true, 1.0f, 1.0f, 1.0f, 45));
    clip.setStep(0, 12, StepData.of(true, 1.0f, 1.0f, 1.0f, 43));
    clip.setStep(0, 14, StepData.of(true, 1.0f, 1.0f, 1.0f, 41));
    synthModel.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synthModel);

    // 3. Build firmware song, engine, and playback handler
    Song fwSong = FirmwareFactory.createSong(project);
    var clip0 = (org.deluge.playback.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound fwSound = (FirmwareSound) clip0.sound;

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fwSound);

    PlaybackHandler handler = new PlaybackHandler();
    handler.setSong(fwSong);
    handler.start();

    // 4. Render 2.5 seconds of audio (~861 blocks)
    int totalBlocks = (int) (2.5 * SAMPLE_RATE / BLOCK_SIZE);
    List<float[]> leftSignal = new ArrayList<>();
    List<float[]> rightSignal = new ArrayList<>();

    // Offline fidelity render: force full-quality sinc by clearing any leftover CPU direness.
    org.deluge.engine.FirmwareAudioEngine.cpuDireness = 0;
    System.out.println("[Generator] Rendering audio blocks and applying resampler gain staging...");
    for (int b = 0; b < totalBlocks; b++) {
      handler.advanceTicks(1);
      engine.renderBlock(BLOCK_SIZE);

      float[] leftBlock = new float[BLOCK_SIZE];
      float[] rightBlock = new float[BLOCK_SIZE];

      // Apply monitorGainMul=24 and soft-clipping (exactly like the resampler does)
      for (int i = 0; i < BLOCK_SIZE; i++) {
        float xL = (float) engine.masterBuffer[i].l / 2147483648.0f;
        float xR = (float) engine.masterBuffer[i].r / 2147483648.0f;

        float boostedL = xL * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        float boostedR = xR * org.deluge.engine.JavaAudioDriver.monitorGainMul;

        leftBlock[i] = org.deluge.engine.JavaAudioDriver.softClip(boostedL);
        rightBlock[i] = org.deluge.engine.JavaAudioDriver.softClip(boostedR);
      }

      leftSignal.add(leftBlock);
      rightSignal.add(rightBlock);
    }

    // Flatten signal blocks
    int totalSamples = totalBlocks * BLOCK_SIZE;
    double[] left = new double[totalSamples];
    double[] right = new double[totalSamples];
    int idx = 0;
    for (int b = 0; b < totalBlocks; b++) {
      float[] lBlock = leftSignal.get(b);
      float[] rBlock = rightSignal.get(b);
      for (int i = 0; i < BLOCK_SIZE; i++) {
        left[idx] = lBlock[i];
        right[idx] = rBlock[i];
        idx++;
      }
    }

    // 5. Save the output to WAV
    File outputDir = new File("target/fidelity");
    outputDir.mkdirs();
    File outputFile = new File(outputDir, "engine_render_boosted.wav");
    System.out.println("[Generator] Saving generated audio to: " + outputFile.getAbsolutePath());
    writeWav(left, right, outputFile);
    System.out.println("[Generator] SUCCESS! WAV generated.");
  }

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
