package org.chuck.deluge.reproduce;

import java.io.File;
import org.chuck.deluge.engine.dsp.NativeWavExporter;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.playback.PlaybackHandler;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;

public class PlaySimulatedSequence {
  public static void main(String[] args) {
    try {
      System.out.println("=== STARTING PROGRAMMATIC SEQUENCE SIMULATION ===");

      // 1. Create default project model
      ProjectModel project = new ProjectModel();
      project.setBpm(120.0f);

      SynthTrackModel synth = new SynthTrackModel("Synth 1");
      synth.setOsc1Type("SAW");
      synth.setOsc2Type("NONE");
      synth.setOscMix(1.0f);
      synth.setLpfFreq(20000.0f);
      synth.setLpfRes(0.0f);
      synth.setVolume(0.5f);

      // Create clip: 8 rows, 16 steps
      ClipModel clip = new ClipModel("CLIP 1", 8, 16);

      // Program steps (using the exact same default click gate constant 0.9f!)
      // Row 7 (pitch 76), Row 5 (pitch 78), Row 3 (pitch 80), Row 1 (pitch 82)
      clip.setStep(7, 0, StepData.of(true, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, 76));
      clip.setStep(5, 4, StepData.of(true, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, 78));
      clip.setStep(3, 8, StepData.of(true, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, 80));
      clip.setStep(1, 12, StepData.of(true, 0.8f, StepData.DEFAULT_CLICK_GATE, 1.0f, 82));

      synth.addClip(clip);
      project.addTrack(synth);

      // 2. Build the song model and instantiate low-level engine
      Song song = FirmwareFactory.createSong(project);
      InstrumentClip fwClip = (InstrumentClip) song.clips.get(0);
      FirmwareSound sound = (FirmwareSound) fwClip.sound;

      FirmwareAudioEngine audioEngine = new FirmwareAudioEngine();
      audioEngine.sounds.add(sound);

      PlaybackHandler playbackHandler = new PlaybackHandler();
      playbackHandler.setSong(song);
      playbackHandler.start();

      // 3. Setup low-level WAV recorder
      String outputPath = "deluge/target/simulated_sequence.wav";
      File outDir = new File("deluge/target");
      if (!outDir.exists()) outDir.mkdirs();

      NativeWavExporter recorder = new NativeWavExporter(44100.0f);
      recorder.start(outputPath);

      // 4. Render loop (render blocks of 128 samples, advance ticks and capture!)
      // At 120 BPM, 1 loop (16 steps) is exactly 4.0 seconds = 176400 samples
      int targetSamples = 176400;
      int blockCount = targetSamples / 128;

      double ticksPerSec = (120.0 / 60.0) * 96.0;
      double ticksPerSample = ticksPerSec / 44100.0;
      double accumulatedTicks = 0.0;

      float[] leftBuf = new float[targetSamples];
      float[] rightBuf = new float[targetSamples];

      System.out.println("[SIMULATOR] Rendering sequence pattern in real-time block steps...");
      for (int b = 0; b < blockCount; b++) {
        // Advance transport tick playhead (equivalent to what JavaAudioDriver does live!)
        accumulatedTicks += ticksPerSample * 128.0;
        int toAdvance = (int) accumulatedTicks;
        if (toAdvance > 0) {
          playbackHandler.advanceTicks(toAdvance);
          accumulatedTicks -= toAdvance;
        }

        // Render audio buffer block
        audioEngine.renderBlock(128);

        // Export samples to wav buffer arrays (applying correct non-clipping gain shifts!)
        for (int i = 0; i < 128; i++) {
          var s = audioEngine.masterBuffer[i];
          int leftVal = s.l >> 16;
          int rightVal = s.r >> 16;

          float leftFloat = Math.max(-32768, Math.min(32767, leftVal)) / 32768.0f;
          float rightFloat = Math.max(-32768, Math.min(32767, rightVal)) / 32768.0f;

          int idx = b * 128 + i;
          if (idx < targetSamples) {
            leftBuf[idx] = leftFloat;
            rightBuf[idx] = rightFloat;
          }
        }
      }

      // Perform high-fidelity offline peak normalization
      float maxVal = 0.0f;
      for (int i = 0; i < targetSamples; i++) {
        maxVal = Math.max(maxVal, Math.abs(leftBuf[i]));
        maxVal = Math.max(maxVal, Math.abs(rightBuf[i]));
      }
      float scale = maxVal > 1e-6f ? (0.8f / maxVal) : 1.0f;
      System.out.printf(
          "[SIMULATOR] Peak-normalizing audio buffer: peak=%.6f, scale=%.2fx\n", maxVal, scale);

      for (int i = 0; i < targetSamples; i++) {
        recorder.record(leftBuf[i] * scale, rightBuf[i] * scale);
      }

      recorder.stop();
      playbackHandler.stop();

      System.out.println("\n=== SIMULATION COMPLETED EXCELLENTLY ===");
      System.out.println("  • Output Saved:  " + new File(outputPath).getAbsolutePath());
      System.out.println("  • Play Command:  afplay " + outputPath);
      System.out.println("==========================================");

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
