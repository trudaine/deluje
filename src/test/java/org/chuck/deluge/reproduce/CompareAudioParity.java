package org.chuck.deluge.reproduce;

import java.io.File;
import org.chuck.deluge.AudioAnalyzer;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * High-fidelity command-line comparative diagnostics tool. Reads your physical Deluge recording,
 * synthesizes our matching software buffer, auto-aligns their phases using cross-correlation, and
 * prints a multi-dimensional diff.
 */
public class CompareAudioParity {

  public static void main(String[] args) {
    if (args.length < 2) {
      System.out.println("Usage: java CompareAudioParity <patchType: A|C> <recordedWavPath>");
      System.out.println("  Patch A: Dry Sawtooth C4");
      System.out.println("  Patch C: ADSR Decay/Release C4");
      return;
    }

    String patchType = args[0].toUpperCase();
    String wavPath = args[1];

    try {
      System.out.println("=== STARTING AUDIO INSTRUMENTATION ANALYSIS ===");
      System.out.println("Patch Type Selected: " + patchType);
      System.out.println("Recorded Wave Path:  " + wavPath);

      File wavFile = new File(wavPath);
      if (!wavFile.exists()) {
        System.err.println("[ERROR] Recorded WAV file not found at path: " + wavPath);
        return;
      }

      // 1. Load Ground Truth hardware audio (channels averaged to mono)
      float[] hw = AudioAnalyzer.loadWav(wavFile);
      System.out.printf(
          "Loaded Hardware Recording: %d samples (%.2f seconds)\n", hw.length, hw.length / 44100.0);

      // 2. Synthesize matching software buffer
      float[] sw = renderSoftwareAudio(patchType, hw.length);
      System.out.printf(
          "Generated Software Render:  %d samples (%.2f seconds)\n",
          sw.length, sw.length / 44100.0);

      // 3. Find optimal phase-alignment lag via cross-correlation
      int maxLagSearch = Math.min(22050, hw.length / 4); // search window
      int bestLag = AudioAnalyzer.findBestLag(hw, sw, maxLagSearch);
      System.out.println("Optimal Phase-Alignment Lag (samples): " + bestLag);

      // 4. Align signals based on lag
      int startHw = Math.max(0, bestLag);
      int startSw = Math.max(0, -bestLag);
      int len = Math.min(hw.length - startHw, sw.length - startSw);
      if (len < 4410) {
        System.err.println("[ERROR] Aligned signal overlap duration is too short to analyze!");
        return;
      }

      float[] alignedHw = new float[len];
      System.arraycopy(hw, startHw, alignedHw, 0, len);
      float[] alignedSw = new float[len];
      System.arraycopy(sw, startSw, alignedSw, 0, len);

      // 5. Compute multi-dimensional analysis metrics
      double correlation = AudioAnalyzer.correlation(alignedHw, alignedSw);
      double hwRms = AudioAnalyzer.rms(alignedHw);
      double swRms = AudioAnalyzer.rms(alignedSw);
      double rmsRatio = swRms / (hwRms > 1e-9 ? hwRms : 1.0);
      double rmsError = AudioAnalyzer.rmsError(alignedHw, alignedSw);

      System.out.println("\n================= ANALYSIS REPORT =================");
      System.out.printf("  Wave Shape Cross-Correlation:   %.6f  (Target: >= 0.90)\n", correlation);
      System.out.printf("  Hardware Audio RMS Level:       %.6f\n", hwRms);
      System.out.printf("  Software Audio RMS Level:       %.6f\n", swRms);
      System.out.printf("  RMS Level Ratio (SW/HW):         %.4f\n", rmsRatio);
      System.out.printf("  Normalised RMS Error Score:     %.6f\n", rmsError);

      if (correlation >= 0.90) {
        System.out.println("\n  [VERDICT] PASS: High wave-shape parity achieved!");
      } else {
        System.out.println(
            "\n  [VERDICT] FAIL: Wave shape mismatch detected. Analyzing envelope & spectrum...");
        runDetailedDiagnosis(alignedHw, alignedSw, patchType);
      }
      System.out.println("===================================================\n");

    } catch (Exception e) {
      System.err.println("[CRITICAL] Comparison failed: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private static float[] renderSoftwareAudio(String patchType, int targetLength) {
    // Create basic default SAW patch
    SynthTrackModel model = new SynthTrackModel("Synth 1");
    model.setOsc1Type("SAW");
    model.setOsc2Type("NONE");
    model.setOscMix(1.0f);
    model.setLpfFreq(20000.0f);
    model.setLpfRes(0.0f);
    model.setVolume(0.5f);

    if ("C".equalsIgnoreCase(patchType)) {
      // ADSR Decay/Release timing test configurations
      model.setEnv(
          0, new org.chuck.deluge.model.EnvelopeModel(0.01f, 1.5f, 0.0f, 2.0f, "NONE", 0.0f));
    }

    ProjectModel project = new ProjectModel();
    project.addTrack(model);

    Song song = FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.model.InstrumentClip clip =
        (org.chuck.deluge.firmware.model.InstrumentClip) song.clips.get(0);
    FirmwareSound sound = (FirmwareSound) clip.sound;

    // Apply exact same safe output level limits as standard hardware test runs to avoid digital
    // clipping
    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 53687091; // Q31.ONE / 40
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = 53687091;

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(sound);

    int blockCount = targetLength / 128;
    float[] sw = new float[blockCount * 128];
    byte[] byteBuffer = new byte[sw.length * 4];

    // Trigger note at block 600 (approx 1.7 seconds buffer padding to clear JIT startup latency)
    int triggerBlock = 600;
    int durationBlocks = "C".equalsIgnoreCase(patchType) ? 5 : 1000; // short staccato vs long note
    int releaseBlock = triggerBlock + durationBlocks;

    for (int b = 0; b < blockCount; b++) {
      if (b == triggerBlock) {
        sound.triggerNote(72, 100); // C5 pitch (matches actual hardware C5 file fundamental!)
      }
      if (b == releaseBlock) {
        sound.releaseNote(72);
      }

      engine.renderBlock(128);

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];
        // Bit-accurate mapping to 16-bit PCM bytes (stop clipping by using proper shift by 16!)
        int leftVal = s.l >> 16;
        int rightVal = s.r >> 16;
        short left = (short) Math.max(-32768, Math.min(32767, leftVal));
        short right = (short) Math.max(-32768, Math.min(32767, rightVal));

        int idx = (b * 128 + i) * 4;
        byteBuffer[idx] = (byte) (left & 0xFF);
        byteBuffer[idx + 1] = (byte) ((left >> 8) & 0xFF);
        byteBuffer[idx + 2] = (byte) (right & 0xFF);
        byteBuffer[idx + 3] = (byte) ((right >> 8) & 0xFF);
      }
    }

    // Unpack byte PCM values back to normalised bipolar float scale
    for (int i = 0; i < sw.length; i++) {
      int idx = i * 4;
      int b0 = byteBuffer[idx] & 0xFF;
      int b1 = byteBuffer[idx + 1];
      short val = (short) (b0 | (b1 << 8));
      sw[i] = val / 32768.0f;
    }
    return sw;
  }

  private static void runDetailedDiagnosis(float[] hw, float[] sw, String patchType) {
    System.out.println("\n  [DIAGNOSTICS INITIALIZED] Analyzing specific discrepancies...");

    // 1. Check Envelope Decay Rate Envelope mismatches (for ADSR Patch C)
    if ("C".equalsIgnoreCase(patchType)) {
      double hwDecayRate = estimateDecayRate(hw);
      double swDecayRate = estimateDecayRate(sw);
      System.out.printf("    • Hardware Envelope Decay Rate: %.4f dB/sec\n", hwDecayRate);
      System.out.printf("    • Software Envelope Decay Rate: %.4f dB/sec\n", swDecayRate);
      System.out.printf(
          "    • Envelope Time Gap Factor:     %.2f%%\n",
          (swDecayRate / (hwDecayRate > 1e-5 ? hwDecayRate : 1.0)) * 100);
    }

    // 2. Estimate fundamental pitch correlation
    double hwFreq = estimateFundamentalFrequency(hw);
    double swFreq = estimateFundamentalFrequency(sw);
    System.out.printf("    • Hardware Signal Fundamental:  %.2f Hz\n", hwFreq);
    System.out.printf("    • Software Signal Fundamental:  %.2f Hz\n", swFreq);
    System.out.printf(
        "    • Pitch Mismatch Offset:        %.2f semitones\n",
        12.0 * Math.log(swFreq / (hwFreq > 1e-5 ? hwFreq : 1.0)) / Math.log(2.0));
  }

  private static double estimateDecayRate(float[] data) {
    // Basic logarithmic level slope estimator (RMS-based windows over time)
    int step = 2205; // 50ms windows
    double firstLevel = -99;
    double lastLevel = -99;
    int firstIdx = -1;
    int lastIdx = -1;

    for (int i = 0; i < data.length - step; i += step) {
      float[] window = new float[step];
      System.arraycopy(data, i, window, 0, step);
      double val = AudioAnalyzer.rms(window);
      if (val > 0.05) { // note is active
        double db = 20.0 * Math.log10(val);
        if (firstLevel == -99) {
          firstLevel = db;
          firstIdx = i;
        }
        lastLevel = db;
        lastIdx = i;
      }
    }
    if (lastIdx > firstIdx && firstIdx != -1) {
      double sec = (lastIdx - firstIdx) / 44100.0;
      return (firstLevel - lastLevel) / sec;
    }
    return 0.0;
  }

  private static double estimateFundamentalFrequency(float[] data) {
    // Autocorrelation-based pitch estimator
    int maxPeriod = 441; // 100 Hz limit
    int minPeriod = 44; // 1000 Hz limit
    int bestPeriod = -1;
    double bestCorr = -1;

    for (int period = minPeriod; period <= maxPeriod; period++) {
      double num = 0, den = 0;
      for (int i = 0; i < data.length - period; i++) {
        num += data[i] * data[i + period];
        den += data[i] * data[i];
      }
      double corr = den > 0 ? num / den : 0;
      if (corr > bestCorr) {
        bestCorr = corr;
        bestPeriod = period;
      }
    }
    return bestPeriod > 0 ? 44100.0 / bestPeriod : 0.0;
  }
}
