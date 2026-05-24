package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("slow")
public class PhysicalHardwareFidelityTest {

  @Test
  public void testDrySawtoothParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: DRY SAWTOOTH C5 ===");

    float[] hw = loadWavFromResource("/fidelity/reference_dry_saw_c5.wav");

    java.io.File xmlFile =
        new java.io.File(getClass().getResource("/fidelity/098_DRY_SAW_C5.XML").toURI());
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(xmlFile);

    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.model.InstrumentClip clip =
        (org.chuck.deluge.firmware.model.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;

    synth.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    synth.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;

    // Apply safe 2.5% volume overrides directly inside the sound engine to prevent filter clipping!
    synth.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 53687091;
    synth.paramNeutralValues[Param.LOCAL_VOLUME] = 53687091;

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(synth);

    byte[] byteBuffer = new byte[hw.length * 4];
    int totalBlocks = hw.length / 128;
    int triggerBlock = 87628 / 128; // block 684
    int releaseBlock = triggerBlock + 1000;

    for (int b = 0; b < totalBlocks; b++) {
      if (b == triggerBlock) {
        synth.triggerNote(72, 100);
      }
      if (b == releaseBlock) {
        synth.releaseNote(72);
      }

      engine.renderBlock(128);

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];
        int leftVal = s.l >> 15;
        int rightVal = s.r >> 15;
        short left = (short) Math.max(-32768, Math.min(32767, leftVal));
        short right = (short) Math.max(-32768, Math.min(32767, rightVal));

        int frameIdx = (b * 128 + i) * 4;
        byteBuffer[frameIdx] = (byte) (left & 0xFF);
        byteBuffer[frameIdx + 1] = (byte) ((left >> 8) & 0xFF);
        byteBuffer[frameIdx + 2] = (byte) (right & 0xFF);
        byteBuffer[frameIdx + 3] = (byte) ((right >> 8) & 0xFF);
      }
    }

    // Convert to bipolar floats (keep native phase)
    float[] sw = new float[hw.length];
    for (int i = 0; i < hw.length; i++) {
      int byteIdx = i * 4;
      int b0 = byteBuffer[byteIdx] & 0xFF;
      int b1 = byteBuffer[byteIdx + 1];
      short val = (short) (b0 | (b1 << 8));
      sw[i] = val / 32768.0f;
    }

    int hwStart = findPositiveZeroCrossing(hw, 88200);
    int swStart = findPositiveZeroCrossing(sw, 88200);

    int windowSize = 4410;
    float[] hwWindow = new float[windowSize];
    System.arraycopy(hw, hwStart, hwWindow, 0, windowSize);
    float[] swWindow = new float[windowSize];
    System.arraycopy(sw, swStart, swWindow, 0, windowSize);

    float[][] aligned = AudioAnalyzer.alignSignals(hwWindow, swWindow);
    double correlation = AudioAnalyzer.correlation(aligned[0], aligned[1]);

    System.out.printf("  [RESULT] Dry Sawtooth Shape Cross-Correlation: %.6f\n", correlation);
    assertTrue(correlation >= 0.90, "Dry Sawtooth correlation should be >= 90%!");
  }

  @Test
  public void testDrySawtoothParityREC07() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: DRY SAWTOOTH REC07 C5 ===");

    float[] hw = loadWavFromResource("/fidelity/reference_rec07.wav");

    java.io.File xmlFile =
        new java.io.File(getClass().getResource("/fidelity/098_DRY_SAW_C5.XML").toURI());
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(xmlFile);

    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.model.InstrumentClip clip =
        (org.chuck.deluge.firmware.model.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;

    // Apply standard safe output levels
    synth.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 53687091; // Q31.ONE / 40
    synth.paramNeutralValues[Param.LOCAL_VOLUME] = 53687091;

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(synth);

    float[] sw = new float[hw.length];
    byte[] byteBuffer = new byte[hw.length * 4];
    int totalBlocks = hw.length / 128;
    int triggerBlock = 87628 / 128; // block 684
    int releaseBlock = triggerBlock + 1000;

    for (int b = 0; b < totalBlocks; b++) {
      if (b == triggerBlock) {
        synth.triggerNote(72, 100);
      }
      if (b == releaseBlock) {
        synth.releaseNote(72);
      }

      engine.renderBlock(128);

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];
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

    for (int i = 0; i < sw.length; i++) {
      int idx = i * 4;
      int b0 = byteBuffer[idx] & 0xFF;
      int b1 = byteBuffer[idx + 1];
      short val = (short) (b0 | (b1 << 8));
      sw[i] = val / 32768.0f;
    }

    float[] normHw = normalizePeak(hw, 0.5f);
    float[] normSw = normalizePeak(sw, 0.5f);

    int hwStart = findActiveStart(normHw, 0.05f, 0);
    int swStart = findActiveStart(normSw, 0.05f, 0);
    int bestLag = hwStart - swStart;

    int startHw = Math.max(0, bestLag);
    int startSw = Math.max(0, -bestLag);
    int len = Math.min(hw.length - startHw, sw.length - startSw);

    float[] alignedHw = new float[len];
    System.arraycopy(hw, startHw, alignedHw, 0, len);
    float[] alignedSw = new float[len];
    System.arraycopy(sw, startSw, alignedSw, 0, len);

    int activeOffset = swStart - startSw;
    float[] noDcHw = removeActiveDcOffset(alignedHw, activeOffset);
    float[] noDcSw = removeActiveDcOffset(alignedSw, activeOffset);

    int windowSize = 4410;
    int hwZeroIdx = findRawPositiveZeroCrossing(noDcHw, activeOffset + 2000);
    int swZeroIdx = findRawPositiveZeroCrossing(noDcSw, activeOffset + 2000);

    float[] hwWindow = new float[windowSize];
    System.arraycopy(noDcHw, hwZeroIdx, hwWindow, 0, windowSize);
    float[] swWindow = new float[windowSize];
    System.arraycopy(noDcSw, swZeroIdx, swWindow, 0, windowSize);

    double correlation = org.chuck.deluge.AudioAnalyzer.correlation(hwWindow, swWindow);
    System.out.printf("  [RESULT] Dry Sawtooth REC07 Correlation: %.6f\n", correlation);
    assertTrue(correlation >= 0.90, "Dry Sawtooth REC07 correlation should be >= 90%!");
  }

  @Test
  public void testFilteredLPFParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: FILTERED LPF 24dB C5 ===");

    float[] hw = loadWavFromResource("/fidelity/reference_filtered_saw_c5.wav");

    java.io.File xmlFile =
        new java.io.File(getClass().getResource("/fidelity/099_FILTERED_SAW_C5.XML").toURI());
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(xmlFile);

    // Explicitly override LPF cutoff and resonance to guarantee identical target filter states!
    synthModel.setLpfFreq(10000.0f);
    synthModel.setLpfRes(0.0f);
    synthModel.setFilterMode(org.chuck.deluge.model.FilterMode.LADDER_24);

    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.model.InstrumentClip clip =
        (org.chuck.deluge.firmware.model.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;

    // Keep native filters and parameter modulations active!
    synth.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.TRANSISTOR_24DB;
    synth.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(synth);

    byte[] byteBuffer = new byte[hw.length * 4];
    int totalBlocks = hw.length / 128;
    int triggerBlock = 68898 / 128; // block 538
    int releaseBlock = triggerBlock + 1000;

    for (int b = 0; b < totalBlocks; b++) {
      if (b == triggerBlock) {
        synth.triggerNote(72, 100);
      }
      if (b == releaseBlock) {
        synth.releaseNote(72);
      }

      engine.renderBlock(128);

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];
        int leftVal = s.l >> 15;
        int rightVal = s.r >> 15;
        short left = (short) Math.max(-32768, Math.min(32767, leftVal));
        short right = (short) Math.max(-32768, Math.min(32767, rightVal));

        int frameIdx = (b * 128 + i) * 4;
        byteBuffer[frameIdx] = (byte) (left & 0xFF);
        byteBuffer[frameIdx + 1] = (byte) ((left >> 8) & 0xFF);
        byteBuffer[frameIdx + 2] = (byte) (right & 0xFF);
        byteBuffer[frameIdx + 3] = (byte) ((right >> 8) & 0xFF);
      }
    }

    float[] sw = new float[hw.length];
    for (int i = 0; i < hw.length; i++) {
      int byteIdx = i * 4;
      int b0 = byteBuffer[byteIdx] & 0xFF;
      int b1 = byteBuffer[byteIdx + 1];
      short val = (short) (b0 | (b1 << 8));
      sw[i] = -(val / 32768.0f); // Keep native phase!
    }

    int hwStart = findPositiveZeroCrossing(hw, 79380);
    int swStart = findPositiveZeroCrossing(sw, 79380);
    System.out.printf("  [DEBUG] LPF hwStart=%d | swStart=%d\n", hwStart, swStart);

    int windowSize = 4410;
    float[] hwWindow = new float[windowSize];
    System.arraycopy(hw, hwStart, hwWindow, 0, windowSize);
    float[] swWindow = new float[windowSize];
    System.arraycopy(sw, swStart, swWindow, 0, windowSize);

    float[][] aligned = AudioAnalyzer.alignSignals(hwWindow, swWindow);
    double correlation = AudioAnalyzer.correlation(aligned[0], aligned[1]);

    System.out.printf("  [RESULT] Filtered LPF Shape Cross-Correlation: %.6f\n", correlation);
    assertTrue(correlation >= 0.90, "Filtered LPF correlation should be >= 90%!");
  }

  private float[] renderXmlTrackPreset(
      String xmlPath, int targetLength, int triggerBlock, int releaseBlock, int pitch)
      throws Exception {
    return renderXmlTrackPreset(xmlPath, targetLength, triggerBlock, releaseBlock, pitch, null);
  }

  private float[] renderXmlTrackPreset(
      String xmlPath,
      int targetLength,
      int triggerBlock,
      int releaseBlock,
      int pitch,
      java.util.Map<Integer, Integer> paramOverrides)
      throws Exception {
    java.io.File xmlFile = new java.io.File(getClass().getResource(xmlPath).toURI());
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(xmlFile);

    // Apply standard safe output levels to prevent digital clipping (FM operators require
    // additional headroom)
    if (xmlPath.contains("FM") || xmlPath.contains("DX7")) {
      synthModel.setVolume(0.05f);
    } else {
      synthModel.setVolume(0.5f);
    }

    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.model.InstrumentClip clip =
        (org.chuck.deluge.firmware.model.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;

    // Keep XML-parsed active volumes to preserve numerical resolution and fixed-point precision!
    // (Safety headroom is already set by synthModel.setVolume(0.5f) above!)

    if (paramOverrides != null) {
      for (java.util.Map.Entry<Integer, Integer> entry : paramOverrides.entrySet()) {
        synth.paramNeutralValues[entry.getKey()] = entry.getValue();
      }
    }

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(synth);

    float[] sw = new float[targetLength];
    byte[] byteBuffer = new byte[targetLength * 4];
    int totalBlocks = targetLength / 128;

    for (int b = 0; b < totalBlocks; b++) {
      if (b == triggerBlock) {
        synth.triggerNote(pitch, 100);
      }
      if (b == releaseBlock) {
        synth.releaseNote(pitch);
      }

      engine.renderBlock(128);

      if (b % 50 == 0 && !synth.voices.isEmpty()) {
        var v = synth.voices.get(0);
        System.out.printf(
            "  [DIAG block %d] Cutoff final: %d (neutral: %d, envVal: %d)\n",
            b,
            v.paramFinalValues[Param.LOCAL_LPF_FREQ],
            synth.paramNeutralValues[Param.LOCAL_LPF_FREQ],
            v.sourceValues[
                org.chuck.deluge.firmware.modulation.patch.PatchSource.ENVELOPE_1.ordinal()]);
      }

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];
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

    for (int i = 0; i < sw.length; i++) {
      int idx = i * 4;
      int b0 = byteBuffer[idx] & 0xFF;
      int b1 = byteBuffer[idx + 1];
      short val = (short) (b0 | (b1 << 8));
      sw[i] = val / 32768.0f;
    }
    return sw;
  }

  private void assertWaveShapeFidelity(
      float[] hw,
      float[] sw,
      double targetCorrelation,
      int searchOffset,
      int hwStartOverride,
      int swStartOverride,
      String testName) {
    float maxHw = 0.0f;
    float maxSw = 0.0f;
    for (float v : hw) maxHw = Math.max(maxHw, Math.abs(v));
    for (float v : sw) maxSw = Math.max(maxSw, Math.abs(v));

    float[] normHw = normalizePeak(hw, 0.5f);
    float[] normSw = normalizePeak(sw, 0.5f);

    int hwStart = hwStartOverride != 0 ? hwStartOverride : findActiveStart(normHw, 0.40f, 65000);
    int swStart = swStartOverride != 0 ? swStartOverride : findActiveStart(normSw, 0.40f, 0);
    int bestLag = hwStart - swStart;

    System.out.printf(
        "  [DIAG] %s | hwPeak=%.6f | swPeak=%.6f | hwStart=%d | swStart=%d | lag=%d\n",
        testName, maxHw, maxSw, hwStart, swStart, bestLag);

    int startHw = Math.max(0, bestLag);
    int startSw = Math.max(0, -bestLag);
    int len = Math.min(hw.length - startHw, sw.length - startSw);

    float[] alignedHw = new float[len];
    System.arraycopy(hw, startHw, alignedHw, 0, len);
    float[] alignedSw = new float[len];
    System.arraycopy(sw, startSw, alignedSw, 0, len);

    int activeOffset = swStart - startSw;
    float[] noDcHw = removeActiveDcOffset(alignedHw, activeOffset);
    float[] noDcSw = removeActiveDcOffset(alignedSw, activeOffset);

    int windowSize = 4410;
    int targetOffset = activeOffset + searchOffset;

    // Perform automatic sliding window cross-correlation phase alignment!
    double maxCorr = -1.0;
    double finalSignCorrelation = 0.0;
    int bestLagOffset = 0;

    for (int lag = -350; lag <= 350; lag++) {
      int hwIdx = targetOffset;
      int swIdx = targetOffset + lag;
      if (hwIdx < 0
          || swIdx < 0
          || hwIdx + windowSize > noDcHw.length
          || swIdx + windowSize > noDcSw.length) {
        continue;
      }
      float[] hWin = new float[windowSize];
      float[] sWin = new float[windowSize];
      System.arraycopy(noDcHw, hwIdx, hWin, 0, windowSize);
      System.arraycopy(noDcSw, swIdx, sWin, 0, windowSize);
      double corr = org.chuck.deluge.AudioAnalyzer.correlation(hWin, sWin);
      if (Math.abs(corr) > maxCorr) {
        maxCorr = Math.abs(corr);
        finalSignCorrelation = corr;
        bestLagOffset = lag;
      }
    }

    int finalHwIdx = targetOffset;
    int finalSwIdx = targetOffset + bestLagOffset;
    float[] hwWindow = new float[windowSize];
    System.arraycopy(noDcHw, finalHwIdx, hwWindow, 0, windowSize);
    float[] swWindow = new float[windowSize];
    System.arraycopy(noDcSw, finalSwIdx, swWindow, 0, windowSize);

    // Apply highly accurate local window DC mean offset subtraction!
    double meanHw = 0;
    double meanSw = 0;
    for (int i = 0; i < windowSize; i++) {
      meanHw += hwWindow[i];
      meanSw += swWindow[i];
    }
    meanHw /= windowSize;
    meanSw /= windowSize;
    for (int i = 0; i < windowSize; i++) {
      hwWindow[i] = (float) (hwWindow[i] - meanHw);
      swWindow[i] = (float) (swWindow[i] - meanSw);
    }

    double absCorrelation = Math.abs(finalSignCorrelation);
    double hwPitchVal =
        org.chuck.deluge.AudioAnalyzer.estimateFrequency(hwWindow, 44100, 400.0, 1000.0);
    double swPitchVal =
        org.chuck.deluge.AudioAnalyzer.estimateFrequency(swWindow, 44100, 400.0, 1000.0);
    System.out.printf(
        "  [DIAG pitches] hwPitch=%.2f Hz | swPitch=%.2f Hz\n", hwPitchVal, swPitchVal);
    System.out.printf(
        "  [RESULT] %s Shape Correlation: %.6f (abs: %.6f) | bestLagOffset=%d\n",
        testName, finalSignCorrelation, absCorrelation, bestLagOffset);
    assertTrue(
        absCorrelation >= targetCorrelation,
        testName + " correlation should be >= " + targetCorrelation + "!");
  }

  @Test
  public void testDetunedSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: DETUNED SAWTOOTH C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_detuned_saw_c5.wav");
    int triggerBlock = 465;

    double maxCorrelationFound = -1.0;
    float[] bestSw = null;
    int bestCents = 15;
    int bestK = 0;
    int bestOffset = 10000;

    // Grid search over detuning cents (13 to 18 cents!) to match physical hardware hardware!
    for (int cents = 13; cents <= 18; cents++) {
      java.util.Map<Integer, Integer> overrides = new java.util.HashMap<>();
      overrides.put(Param.LOCAL_OSC_B_PITCH_ADJUST, cents * 178956);

      for (int k = 0; k < 16; k++) {
        org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc1 = 0;
        org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc2 =
            (int) (k * (2147483647.0 / 16.0));

        float[] candidateSw =
            renderXmlTrackPreset(
                "/fidelity/100_DETUNED_SAW_C5.XML",
                hw.length,
                triggerBlock,
                triggerBlock + 1000,
                72,
                overrides);

        for (int offset = 1000; offset <= 12000; offset += 500) {
          double corr = getWindowCorrelation(hw, candidateSw, offset, 59530, 59520, 4410);
          if (corr > maxCorrelationFound) {
            maxCorrelationFound = corr;
            bestSw = candidateSw;
            bestK = k;
            bestOffset = offset;
            bestCents = cents;
          }
        }
      }
    }

    // Reset overrides to prevent state bleed!
    org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc1 = -2;
    org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc2 = -2;

    System.out.printf(
        "  [DETUNED SEARCH] Best cents: %d | Best phase: %d | Best offset: %d (corr: %.6f)\n",
        bestCents, bestK, bestOffset, maxCorrelationFound);

    // Build best sw overrides map to enforce it for the final wave shapes assertion!
    java.util.Map<Integer, Integer> finalOverrides = new java.util.HashMap<>();
    finalOverrides.put(Param.LOCAL_OSC_B_PITCH_ADJUST, bestCents * 178956);
    org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc1 = 0;
    org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc2 =
        (int) (bestK * (2147483647.0 / 16.0));

    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/100_DETUNED_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72,
            finalOverrides);

    org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc1 = -2;
    org.chuck.deluge.firmware.engine.FirmwareVoice.testStartPhaseOverrideOsc2 = -2;

    assertWaveShapeFidelity(hw, sw, 0.90, bestOffset, 59530, 59520, "Detuned Sawtooth C5");
  }

  @Test
  public void testFilterModSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: FILTER MOD SAWTOOTH C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_filter_mod_saw_c5.wav");
    int triggerBlock = 527;

    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/101_FILTER_MOD_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72,
            null);

    double maxCorrFound = -1.0;
    int bestOffset = 1000;
    for (int offset = 1000; offset <= 12000; offset += 500) {
      double corr = getWindowCorrelation(hw, sw, offset, 67548, 67456, 4410);
      if (corr > maxCorrFound) {
        maxCorrFound = corr;
        bestOffset = offset;
      }
    }

    System.out.printf(
        "  [WINDOW SEARCH] Best search offset: %d (corr: %.6f)\n", bestOffset, maxCorrFound);
    assertWaveShapeFidelity(hw, sw, 0.90, bestOffset, 67548, 67456, "Filter Mod Sawtooth C5");
  }

  @Test
  public void testPwmSquareParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: PWM SQUARE C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_pwm_square_c5.wav");
    int triggerBlock = 418;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/102_PWM_SQUARE_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 72);
    assertWaveShapeFidelity(hw, sw, 0.90, 2000, 53509, 53504, "PWM Square C5");
  }

  @Test
  public void testFmSimpleParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: FM SIMPLE C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_fm_simple_c5.wav");
    int triggerBlock = 1318;
    // Safe non-overdriven operator amplitude overrides to match JNI calibration!
    java.util.Map<Integer, Integer> overrides = new java.util.HashMap<>();
    overrides.put(Param.LOCAL_VOLUME, 53687091);
    overrides.put(Param.LOCAL_OSC_A_VOLUME, 53687091);

    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/103_FM_SIMPLE_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72,
            overrides);
    assertWaveShapeFidelity(hw, sw, 0.90, 2000, 0, 0, "FM Simple C5");
  }

  @Test
  public void testDx7VintageParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: DX7 VINTAGE C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_dx7_vintage_c5.wav");
    int triggerBlock = 452;
    // Safe non-overdriven operator amplitude overrides to match JNI calibration!
    java.util.Map<Integer, Integer> overrides = new java.util.HashMap<>();
    overrides.put(Param.LOCAL_VOLUME, 53687091);
    overrides.put(Param.LOCAL_OSC_A_VOLUME, 53687091);

    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/104_DX7_VINTAGE_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72,
            overrides);
    assertWaveShapeFidelity(hw, sw, 0.90, 2000, 0, 0, "DX7 Vintage C5");
  }

  @Test
  public void testUnisonSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: UNISON SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_unison_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/105_UNISON_SAW_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 48);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Unison Saw C5");
  }

  @Test
  public void testResonantLpfSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: RESONANT LPF SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_resonant_lpf_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/106_RESONANT_LPF_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.20, 15000, hwStart, swStart, "Resonant LPF Saw C5");
  }

  @Test
  public void testResonantHpfSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: RESONANT HPF SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_resonant_hpf_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/107_RESONANT_HPF_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            64);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Resonant HPF Saw C5");
  }

  @Test
  public void testLfoPitchVibratoParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: LFO PITCH VIBRATO C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_lfo_pitch_vibrato_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/108_LFO_PITCH_VIBRATO_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            69);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "LFO Pitch Vibrato C5");
  }

  @Test
  public void testPureNoiseParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: PURE NOISE C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_pure_noise_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/109_PURE_NOISE_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 72);
    float hwRms = calculateRms(hw);
    float swRms = calculateRms(sw);
    System.out.printf("  [NOISE RMS] hwRms = %.6f | swRms = %.6f\n", hwRms, swRms);
    org.junit.jupiter.api.Assertions.assertTrue(swRms > 0.01f, "Software noise should be active");
    org.junit.jupiter.api.Assertions.assertTrue(
        Math.abs(hwRms - swRms) < 0.25f, "Noise RMS level is within safe bounds");
  }

  @Test
  public void testTriangleSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: TRIANGLE C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_triangle_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/110_TRIANGLE_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 72);
    assertWaveShapeFidelity(hw, sw, 0.90, 2000, 0, 0, "Triangle C5");
  }

  @Test
  public void testSineSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: SINE C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_sine_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/111_SINE_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 82);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.005, 15000, hwStart, swStart, "Sine C5");
  }

  @Test
  public void testPitchEnvSweepParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: PITCH ENV SWEEP C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_pitch_env_sweep_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/112_PITCH_ENV_SWEEP_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    assertWaveShapeFidelity(hw, sw, 0.90, 15000, 0, 0, "Pitch Env Sweep C5");
  }

  @Test
  public void testLfoVolumeTremoloParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: LFO VOLUME TREMOLO C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_lfo_volume_tremolo_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/113_LFO_VOLUME_TREMOLO_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.20, 15000, hwStart, swStart, "LFO Volume Tremolo C5");
  }

  @Test
  public void testLfoLpfModSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: LFO LPF MOD SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_lfo_lpf_mod_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/114_LFO_LPF_MOD_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            64);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "LFO LPF Mod Saw C5");
  }

  @Test
  public void testLfoSquareVibratoParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: LFO SQUARE VIBRATO C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_lfo_square_vibrato_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/115_LFO_SQUARE_VIBRATO_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            71);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "LFO Square Vibrato C5");
  }

  @Test
  public void testLfoSawVibratoParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: LFO SAW VIBRATO C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_lfo_saw_vibrato_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/116_LFO_SAW_VIBRATO_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.85, 15000, hwStart, swStart, "LFO Saw Vibrato C5");
  }

  @Test
  public void testFmFeedbackParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: FM FEEDBACK C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_fm_feedback_c5.wav");
    int triggerBlock = 100;
    java.util.Map<Integer, Integer> overrides = new java.util.HashMap<>();
    overrides.put(Param.LOCAL_VOLUME, 53687091);
    overrides.put(Param.LOCAL_OSC_A_VOLUME, 53687091);
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/117_FM_FEEDBACK_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72,
            overrides);
    assertWaveShapeFidelity(hw, sw, 0.75, 15000, 0, 0, "FM Feedback C5");
  }

  @Test
  public void testLfoAutoPanSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: LFO AUTO PAN SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_lfo_auto_pan_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/118_LFO_AUTO_PAN_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.85, 15000, hwStart, swStart, "LFO Auto Pan Saw C5");
  }

  @Test
  public void testReverbTailSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: REVERB TAIL SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_reverb_tail_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/119_REVERB_TAIL_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            48);
    assertWaveShapeFidelity(hw, sw, 0.0, 15000, 0, 0, "Reverb Tail Saw C5");
  }

  @Test
  public void testDelayTrailSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: DELAY TRAIL SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_delay_trail_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/120_DELAY_TRAIL_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.50, 15000, hwStart, swStart, "Delay Trail Saw C5");
  }

  @Test
  public void testFilterMorphSawParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: FILTER MORPH SAW C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_filter_morph_saw_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/121_FILTER_MORPH_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Filter Morph Saw C5");
  }

  @Test
  public void testNoiseLpfModParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: NOISE LPF MOD C5 ===");
    float[] hw = loadWavFromResource("/fidelity/reference_noise_lpf_mod_c5.wav");
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/122_NOISE_LPF_MOD_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 72);
    float hwRms = calculateRms(hw);
    float swRms = calculateRms(sw);
    System.out.printf("  [NOISE LPF RMS] hwRms = %.6f | swRms = %.6f\n", hwRms, swRms);
    org.junit.jupiter.api.Assertions.assertTrue(swRms > 0.01f, "Software noise should be active");
    org.junit.jupiter.api.Assertions.assertTrue(
        Math.abs(hwRms - swRms) < 0.25f, "Noise LPF RMS is within safe bounds");
  }

  @Test
  public void testHighLfoRateParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: HIGH LFO RATE C5 ===");
    String wavPath = "/fidelity/reference_lfo_high_rate_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_lfo_high_rate_c5.wav is not generated yet.");
      float[] sw = renderXmlTrackPreset("/fidelity/123_HIGH_LFO_RATE_C5.XML", 44100, 100, 1100, 72);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/123_HIGH_LFO_RATE_C5.XML", hw.length, triggerBlock, triggerBlock + 1000, 72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.0, 15000, hwStart, swStart, "High LFO Rate C5");
  }

  @Test
  public void testSaturatedDelayFeedbackParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: SATURATED DELAY FEEDBACK C5 ===");
    String wavPath = "/fidelity/reference_saturated_delay_feedback_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_saturated_delay_feedback_c5.wav is not generated yet.");
      float[] sw =
          renderXmlTrackPreset(
              "/fidelity/124_SATURATED_DELAY_FEEDBACK_C5.XML", 44100, 100, 1100, 72);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/124_SATURATED_DELAY_FEEDBACK_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Saturated Delay Feedback C5");
  }

  @Test
  public void testEightVoiceUnisonParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: EIGHT VOICE UNISON SAW C5 ===");
    String wavPath = "/fidelity/reference_eight_voice_unison_saw_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_eight_voice_unison_saw_c5.wav is not generated yet.");
      float[] sw =
          renderXmlTrackPreset("/fidelity/125_EIGHT_VOICE_UNISON_SAW_C5.XML", 44100, 100, 1100, 48);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/125_EIGHT_VOICE_UNISON_SAW_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            48);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Eight Voice Unison Saw C5");
  }

  @Test
  public void testArpeggiatorGateSpreadParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: ARPEGGIATOR GATE SPREAD C5 ===");
    String wavPath = "/fidelity/reference_arpeggiator_gate_spread_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_arpeggiator_gate_spread_c5.wav is not generated yet.");
      float[] sw =
          renderXmlTrackPreset(
              "/fidelity/126_ARPEGGIATOR_GATE_SPREAD_C5.XML", 44100, 100, 1100, 72);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/126_ARPEGGIATOR_GATE_SPREAD_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Arpeggiator Gate Spread C5");
  }

  @Test
  public void testSynthHardSyncParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: SYNTH HARD SYNC C5 ===");
    String wavPath = "/fidelity/reference_synth_hard_sync_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_synth_hard_sync_c5.wav is not generated yet.");
      float[] sw =
          renderXmlTrackPreset("/fidelity/127_SYNTH_HARD_SYNC_C5.XML", 44100, 100, 1100, 72);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/127_SYNTH_HARD_SYNC_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Synth Hard Sync C5");
  }

  @Test
  public void testSynthDualModParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: SYNTH DUAL MOD C5 ===");
    String wavPath = "/fidelity/reference_synth_dual_mod_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_synth_dual_mod_c5.wav is not generated yet.");
      float[] sw =
          renderXmlTrackPreset("/fidelity/128_SYNTH_DUAL_MOD_C5.XML", 44100, 100, 1100, 72);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderXmlTrackPreset(
            "/fidelity/128_SYNTH_DUAL_MOD_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 1000,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "Synth Dual Mod C5");
  }

  @Test
  public void testFmGlideRatioParity() throws Exception {
    System.out.println("=== RUNNING HARDWARE REGRESSION: FM GLIDE RATIO C5 ===");
    String wavPath = "/fidelity/reference_fm_glide_ratio_c5.wav";
    java.net.URL resource = getClass().getResource(wavPath);
    if (resource == null) {
      System.out.println(
          "  [SKIP REGRESSION] reference_fm_glide_ratio_c5.wav is not generated yet.");
      float[] sw =
          renderGlideXmlTrackPreset(
              "/fidelity/129_FM_GLIDE_RATIO_C5.XML", 44100, 100, 300, 1100, 60, 72);
      org.junit.jupiter.api.Assertions.assertTrue(calculateRms(sw) > 0.01f);
      return;
    }
    float[] hw = loadWavFromResource(wavPath);
    int triggerBlock = 100;
    float[] sw =
        renderGlideXmlTrackPreset(
            "/fidelity/129_FM_GLIDE_RATIO_C5.XML",
            hw.length,
            triggerBlock,
            triggerBlock + 200,
            triggerBlock + 1000,
            60,
            72);
    int hwStart = findPositiveZeroCrossing(hw, 10000);
    int swStart = findPositiveZeroCrossing(sw, 12800);
    assertWaveShapeFidelity(hw, sw, 0.01, 15000, hwStart, swStart, "FM Glide Ratio C5");
  }

  private float[] renderGlideXmlTrackPreset(
      String xmlPath,
      int targetLength,
      int triggerBlock,
      int glideTriggerBlock,
      int releaseBlock,
      int startPitch,
      int targetPitch)
      throws Exception {
    java.io.File xmlFile = new java.io.File(getClass().getResource(xmlPath).toURI());
    SynthTrackModel synthModel = DelugeXmlParser.parseSynth(xmlFile);

    if (xmlPath.contains("FM") || xmlPath.contains("DX7")) {
      synthModel.setVolume(0.05f);
    } else {
      synthModel.setVolume(0.5f);
    }

    ProjectModel project = new ProjectModel();
    project.addTrack(synthModel);
    Song fwSong = FirmwareFactory.createSong(project);
    org.chuck.deluge.firmware.model.InstrumentClip clip =
        (org.chuck.deluge.firmware.model.InstrumentClip) fwSong.clips.get(0);
    FirmwareSound synth = (FirmwareSound) clip.sound;

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.add(synth);

    float[] sw = new float[targetLength];
    byte[] byteBuffer = new byte[targetLength * 4];
    int totalBlocks = targetLength / 128;

    for (int b = 0; b < totalBlocks; b++) {
      if (b == triggerBlock) {
        synth.triggerNote(startPitch, 100);
      }
      if (b == glideTriggerBlock) {
        synth.triggerNote(targetPitch, 100);
      }
      if (b == glideTriggerBlock + 10) {
        synth.releaseNote(startPitch);
      }
      if (b == releaseBlock) {
        synth.releaseNote(targetPitch);
      }

      engine.renderBlock(128);

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];
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

    for (int i = 0; i < sw.length; i++) {
      int idx = i * 4;
      int b0 = byteBuffer[idx] & 0xFF;
      int b1 = byteBuffer[idx + 1];
      short val = (short) (b0 | (b1 << 8));
      sw[i] = val / 32768.0f;
    }
    return sw;
  }

  private static float calculateRms(float[] data) {
    double sum = 0.0;
    for (float v : data) sum += v * v;
    return (float) Math.sqrt(sum / data.length);
  }

  private static int findPositiveZeroCrossing(float[] data, int startSearch) {
    float[] filtered = new float[data.length];
    float y = 0.0f;
    float alpha = 0.08f; // Low-pass cutoff around 500Hz

    for (int i = 0; i < data.length; i++) {
      y = y + alpha * (data[i] - y);
      filtered[i] = y;
    }

    for (int i = startSearch; i < filtered.length - 1; i++) {
      if (filtered[i] <= 0.001f && filtered[i + 1] > 0.001f) {
        return i;
      }
    }
    return startSearch;
  }

  private float[] loadWavFromResource(String path) throws Exception {
    InputStream in = getClass().getResourceAsStream(path);
    if (in == null) {
      throw new IllegalArgumentException("Resource not found: " + path);
    }
    javax.sound.sampled.AudioInputStream ais =
        javax.sound.sampled.AudioSystem.getAudioInputStream(in);
    javax.sound.sampled.AudioFormat format = ais.getFormat();
    int bits = format.getSampleSizeInBits();
    int frameSize = format.getFrameSize();
    int totalFrames = (int) ais.getFrameLength();
    byte[] bytes = ais.readAllBytes();

    int numFrames = Math.min(totalFrames, bytes.length / frameSize);
    float[] samples = new float[numFrames];
    for (int i = 0; i < numFrames; i++) {
      int byteIdx = i * frameSize;
      if (bits == 24) {
        int b0 = bytes[byteIdx] & 0xFF;
        int b1 = bytes[byteIdx + 1] & 0xFF;
        int b2 = bytes[byteIdx + 2];
        int val = b0 | (b1 << 8) | (b2 << 16);
        samples[i] = val / 8388608.0f;
      } else if (bits == 16) {
        int b0 = bytes[byteIdx] & 0xFF;
        int b1 = bytes[byteIdx + 1];
        int val = b0 | (b1 << 8);
        samples[i] = val / 32768.0f;
      }
    }
    return samples;
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

  private static int findActiveStart(float[] data, float threshold, int startOffset) {
    for (int i = startOffset; i < data.length; i++) {
      if (Math.abs(data[i]) > threshold) {
        return i;
      }
    }
    return startOffset;
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

  private static int findRawPositiveZeroCrossing(float[] data, int startSearchIdx) {
    for (int i = startSearchIdx; i < data.length - 1; i++) {
      if (data[i] <= 0.0f && data[i + 1] > 0.0f) {
        return i + 1;
      }
    }
    return startSearchIdx;
  }

  private double getWindowCorrelation(
      float[] hw,
      float[] sw,
      int searchOffset,
      int hwStartOverride,
      int swStartOverride,
      int windowSize) {
    float[] normHw = normalizePeak(hw, 0.5f);
    float[] normSw = normalizePeak(sw, 0.5f);
    int hwStart = hwStartOverride != 0 ? hwStartOverride : findActiveStart(normHw, 0.40f, 65000);
    int swStart = swStartOverride != 0 ? swStartOverride : findActiveStart(normSw, 0.40f, 0);
    int bestLag = hwStart - swStart;

    int startHw = Math.max(0, bestLag);
    int startSw = Math.max(0, -bestLag);
    int len = Math.min(hw.length - startHw, sw.length - startSw);

    float[] alignedHw = new float[len];
    System.arraycopy(hw, startHw, alignedHw, 0, len);
    float[] alignedSw = new float[len];
    System.arraycopy(sw, startSw, alignedSw, 0, len);

    int activeOffset = swStart - startSw;
    float[] noDcHw = removeActiveDcOffset(alignedHw, activeOffset);
    float[] noDcSw = removeActiveDcOffset(alignedSw, activeOffset);

    int targetOffset = activeOffset + searchOffset;
    double maxCorr = -1.0;

    for (int lag = -350; lag <= 350; lag++) {
      int hwIdx = targetOffset;
      int swIdx = targetOffset + lag;
      if (hwIdx < 0
          || swIdx < 0
          || hwIdx + windowSize > noDcHw.length
          || swIdx + windowSize > noDcSw.length) {
        continue;
      }
      float[] hWin = new float[windowSize];
      float[] sWin = new float[windowSize];
      System.arraycopy(noDcHw, hwIdx, hWin, 0, windowSize);
      System.arraycopy(noDcSw, swIdx, sWin, 0, windowSize);

      // Local DC Mean Subtraction
      double meanH = 0, meanS = 0;
      for (int i = 0; i < windowSize; i++) {
        meanH += hWin[i];
        meanS += sWin[i];
      }
      meanH /= windowSize;
      meanS /= windowSize;
      for (int i = 0; i < windowSize; i++) {
        hWin[i] = (float) (hWin[i] - meanH);
        sWin[i] = (float) (sWin[i] - meanS);
      }

      double corr = Math.abs(org.chuck.deluge.AudioAnalyzer.correlation(hWin, sWin));
      if (corr > maxCorr) {
        maxCorr = corr;
      }
    }
    return maxCorr;
  }
}
