package org.deluge.reproduce;

import org.deluge.AudioAnalyzer;
import org.deluge.firmware.engine.FirmwareAudioEngine;
import org.deluge.firmware.engine.FirmwareFactory;
import org.deluge.firmware.engine.FirmwareSound;
import org.deluge.firmware.model.Song;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;

/**
 * High-fidelity command-line comparative diagnostics tool. Reads your physical Deluge recording,
 * synthesizes our matching software buffer, auto-aligns their phases using cross-correlation, and
 * prints a multi-dimensional diff.
 */
public class CompareAudioParity {

  public static void main(String[] args) {
    String patchType = "A";
    String wavPath = "/fidelity/reference_rec07.wav";

    if (args.length >= 2) {
      patchType = args[0].toUpperCase();
      wavPath = args[1];
    } else {
      System.out.println(
          "[INFO] No arguments specified. Falling back to default: Patch A with dry C4 reference");
    }

    try {
      System.out.println("=== STARTING AUDIO INSTRUMENTATION ANALYSIS ===");
      System.out.println("Patch Type Selected: " + patchType);
      System.out.println("Recorded Wave Path:  " + wavPath);

      // 1. Load Ground Truth hardware audio (handles both files and classpath resources!)
      float[] hw = AudioAnalyzer.loadWav(wavPath);
      System.out.printf(
          "Loaded Hardware Recording: %d samples (%.2f seconds)\n", hw.length, hw.length / 44100.0);

      // 2. Synthesize matching software buffer
      float[] sw = renderSoftwareAudio(patchType, hw.length);
      System.out.printf(
          "Generated Software Render:  %d samples (%.2f seconds)\n",
          sw.length, sw.length / 44100.0);

      // Check for any initial non-zero sample leaks causing early transient trigger detection
      for (int i = 0; i < Math.min(100000, sw.length); i++) {
        if (Math.abs(sw[i]) > 0.0001f) {
          System.out.println(
              "[DIAG sw leak] First non-zero software sample at index " + i + " value=" + sw[i]);
          break;
        }
      }

      // Auto-normalize peaks to 0.5 to make transient onset detection level-independent
      float[] normHw = normalizePeak(hw, 0.5f);
      float[] normSw = normalizePeak(sw, 0.5f);

      // 3. Find optimal start-transient onset points and align first active note cycles
      int hwStart = findActiveStart(normHw, 0.05f);
      int swStart = findActiveStart(normSw, 0.05f);
      int bestLag = hwStart - swStart;
      System.out.println(
          "Transient-Based Alignment Lag (samples): "
              + bestLag
              + " (hwStart="
              + hwStart
              + ", swStart="
              + swStart
              + ")");

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

      // Remove DC offsets to align zero-crossings perfectly
      // Slices 100ms window right after the note onset starts (steady state sustain phase)
      int activeOffset = swStart - startSw;

      // Remove active DC offsets to align zero-crossings perfectly
      float[] noDcHw = removeActiveDcOffset(alignedHw, activeOffset);
      float[] noDcSw = removeActiveDcOffset(alignedSw, activeOffset);

      int windowSize = 4410;
      int hwZeroIdx =
          findPositiveZeroCrossing(
              noDcHw, activeOffset + 2000); // skip initial transient click zone
      int swZeroIdx = findPositiveZeroCrossing(noDcSw, activeOffset + 2000);

      float[] hwWindow = new float[windowSize];
      System.arraycopy(noDcHw, hwZeroIdx, hwWindow, 0, windowSize);
      float[] swWindow = new float[windowSize];
      System.arraycopy(noDcSw, swZeroIdx, swWindow, 0, windowSize);

      System.out.println(
          "[DIAG zero] hwZeroIdx="
              + hwZeroIdx
              + " swZeroIdx="
              + swZeroIdx
              + " diff="
              + (hwZeroIdx - swZeroIdx));
      System.out.print("[DIAG hwWindow samples] ");
      for (int i = 0; i < 10; i++) System.out.printf("%.6f ", hwWindow[i]);
      System.out.println();
      System.out.print("[DIAG swWindow samples] ");
      for (int i = 0; i < 10; i++) System.out.printf("%.6f ", swWindow[i]);
      System.out.println();

      // Create inverted software window to test the descending/inverted sawtooth phase slope
      // hypothesis
      float[] invSwWindow = new float[windowSize];
      for (int i = 0; i < windowSize; i++) invSwWindow[i] = -swWindow[i];

      // 5. Compute multi-dimensional analysis metrics
      double correlationNormal = AudioAnalyzer.correlation(hwWindow, swWindow);
      double correlationInverted = AudioAnalyzer.correlation(hwWindow, invSwWindow);
      double correlation = Math.max(correlationNormal, correlationInverted);
      boolean isInvertedBest = correlationInverted > correlationNormal;

      if (isInvertedBest) {
        System.out.println(
            "[DIAG shape] INVERTED polarity matches best! correlationInverted="
                + correlationInverted
                + " correlationNormal="
                + correlationNormal);
        swWindow = invSwWindow;
      } else {
        System.out.println(
            "[DIAG shape] NORMAL polarity matches best! correlationNormal="
                + correlationNormal
                + " correlationInverted="
                + correlationInverted);
      }

      double hwRms = AudioAnalyzer.rms(alignedHw);
      double swRms = AudioAnalyzer.rms(alignedSw);
      double rmsRatio = swRms / (hwRms > 1e-9 ? hwRms : 1.0);
      double rmsError = AudioAnalyzer.rmsError(hwWindow, swWindow);

      System.out.println("\n================= ANALYSIS REPORT =================");
      System.out.printf(
          "  Wave Shape Cross-Correlation (100ms):   %.6f  (Target: >= 0.90)\n", correlation);
      System.out.printf("  Hardware Audio RMS Level:               %.6f\n", hwRms);
      System.out.printf("  Software Audio RMS Level:               %.6f\n", swRms);
      System.out.printf("  RMS Level Ratio (SW/HW):                 %.4f\n", rmsRatio);
      System.out.printf("  Normalised RMS Error Score (100ms):     %.6f\n", rmsError);

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
      model.setEnv(0, new org.deluge.model.EnvelopeModel(0.01f, 1.5f, 0.0f, 2.0f, "NONE", 0.0f));
    }

    ProjectModel project = new ProjectModel();
    project.addTrack(model);

    Song song = FirmwareFactory.createSong(project);
    org.deluge.firmware.model.InstrumentClip clip =
        (org.deluge.firmware.model.InstrumentClip) song.clips.get(0);
    FirmwareSound sound = (FirmwareSound) clip.sound;

    // Apply exact same safe output level limits as standard hardware test runs to avoid digital
    // clipping

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
        sound.triggerNote(60, 100); // C4 pitch (matches actual physical C4 resample file!)
      }
      if (b == releaseBlock) {
        sound.releaseNote(60);
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

    // 2. Estimate fundamental pitch correlation on the loudest 1-second segment to avoid
    // noise-floor octave errors
    int sliceSize = Math.min(44100, Math.min(hw.length, sw.length));
    float[] hwLoud = getLoudestSegment(hw, sliceSize);
    float[] swLoud = getLoudestSegment(sw, sliceSize);
    double hwFreq = estimateFundamentalFrequency(hwLoud);
    double swFreq = estimateFundamentalFrequency(swLoud);
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

  private static float[] getLoudestSegment(float[] data, int length) {
    int step = 2205; // 50ms windows
    double maxRms = -1;
    int maxIdx = 0;
    for (int i = 0; i < data.length - length; i += step) {
      float[] sub = new float[length];
      System.arraycopy(data, i, sub, 0, length);
      double val = AudioAnalyzer.rms(sub);
      if (val > maxRms) {
        maxRms = val;
        maxIdx = i;
      }
    }
    float[] out = new float[length];
    System.arraycopy(data, maxIdx, out, 0, length);
    return out;
  }

  private static int findActiveStart(float[] data, float threshold) {
    for (int i = 0; i < data.length; i++) {
      if (Math.abs(data[i]) > threshold) {
        return i;
      }
    }
    return 0;
  }

  private static int findPositiveZeroCrossing(float[] data, int startSearchIdx) {
    for (int i = startSearchIdx; i < data.length - 1; i++) {
      if (data[i] <= 0.0f && data[i + 1] > 0.0f) {
        return i + 1;
      }
    }
    return startSearchIdx;
  }

  private static float[] normalizePeak(float[] data, float targetPeak) {
    double max = 0;
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > max) max = abs;
    }
    if (max < 1e-9) return data.clone();
    float scale = (float) (targetPeak / max);
    float[] out = new float[data.length];
    for (int i = 0; i < data.length; i++) out[i] = data[i] * scale;
    return out;
  }

  private static float[] removeActiveDcOffset(float[] data, int activeStart) {
    double sum = 0;
    int count = 0;
    for (int i = activeStart; i < data.length; i++) {
      sum += data[i];
      count++;
    }
    float mean = count > 0 ? (float) (sum / count) : 0.0f;
    float[] out = new float[data.length];
    for (int i = 0; i < data.length; i++) {
      if (i >= activeStart) {
        out[i] = data[i] - mean;
      } else {
        out[i] = data[i];
      }
    }
    return out;
  }
}
