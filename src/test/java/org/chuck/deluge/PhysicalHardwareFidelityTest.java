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

    int hwStart = findActiveStart(normHw, 0.05f);
    int swStart = findActiveStart(normSw, 0.05f);
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

    float[] samples = new float[totalFrames];
    for (int i = 0; i < totalFrames; i++) {
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

  private static int findActiveStart(float[] data, float threshold) {
    for (int i = 0; i < data.length; i++) {
      if (Math.abs(data[i]) > threshold) {
        return i;
      }
    }
    return 0;
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
}
