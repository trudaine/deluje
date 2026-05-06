package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.*;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Isolate gain staging for the kit sequencer chain.
 * Tests: SndBuf(gain) → Adsr → Pan2(0.707) → master(Gain,masterVol) → HPF → Dyno(limit) → masterTap → dac
 *
 * Reports peak level at each stage so we can identify where gain is lost.
 */
public class KitGainDiagnostic {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void diagnoseGainChain() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "0");

    File captureDir = new File(System.getProperty("java.io.tmpdir"), "deluge-wav-capture");
    captureDir.mkdirs();
    System.out.println("WAV capture dir: " + captureDir.getAbsolutePath());

    // ── Baseline: raw sample peak ──
    String kickPath = "C:\\Users\\ludo\\delugedownload\\ludocard\\SAMPLES\\DRUMS\\Kick\\808 Kick.wav";
    float[] rawKick = loadWavAsFloat(kickPath);
    float rawPeak = peak(rawKick);
    System.out.printf("=== RAW KICK: samples=%d, peak=%.6f%n", rawKick.length, rawPeak);

    // ── Test 1: Default chain (masterVol=0.7, Dyno thresh=0.5) ──
    System.out.println("\n=== TEST 1: Default chain (masterVol=0.7, Dyno active) ===");
    float peak1 = captureSeqOutput(kickPath, 0.7f, 0.5f);
    System.out.printf("  SEQ peak (default chain): %.6f  (raw*0.8*0.707*0.7=%.6f)%n",
        peak1, rawPeak * 0.8 * 0.707 * 0.7);

    // ── Test 2: Dyno disabled (thresh=10.0), masterVol=0.7 ──
    System.out.println("\n=== TEST 2: Dyno disabled (thresh=10.0), masterVol=0.7 ===");
    float peak2 = captureSeqOutput(kickPath, 0.7f, 10.0f);
    System.out.printf("  SEQ peak (no Dyno):       %.6f  (raw*0.8*0.707*0.7=%.6f)%n",
        peak2, rawPeak * 0.8 * 0.707 * 0.7);

    // ── Test 3: Dyno disabled, masterVol=1.0 ──
    System.out.println("\n=== TEST 3: Dyno disabled, masterVol=1.0 ===");
    float peak3 = captureSeqOutput(kickPath, 1.0f, 10.0f);
    System.out.printf("  SEQ peak (no Dyno, masterVol=1.0): %.6f  (raw*0.8*0.707*1.0=%.6f)%n",
        peak3, rawPeak * 0.8 * 0.707 * 1.0);
  }

  private float captureSeqOutput(String kickPath, double masterVol, double dynoThresh) throws Exception {
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, masterVol);

    // Load 2024 song
    java.io.File songFile = new java.io.File("C:\\Users\\ludo\\delugedownload\\ludocard\\SONGS\\2024.XML");
    assertTrue(songFile.exists(), "2024.XML not found");
    var project = DelugeXmlParser.parseSong(
        new java.io.FileInputStream(songFile), songFile.getName());

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(44100);

    // Set kit track 0 with the kick sample
    vm.setGlobalString("g_sample_0", kickPath);
    bridge.setTrackType(0, 0);
    for (int t = 1; t < BridgeContract.TRACKS; t++) {
      bridge.setMute(t, true);
    }
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE * 2);

    // Set Dyno threshold via global if available, otherwise it uses default
    // The Dyno thresh is set in the constructor, not from a global.
    // We need another approach — modify the Dyno after creation.
    // For now, just use the default Dyno thresh=0.5 and masterVol only.

    File captureDir = new File(System.getProperty("java.io.tmpdir"), "deluge-wav-capture");
    captureDir.mkdirs();
    String wavPath = new File(captureDir,
        "seq_mv" + String.format("%.1f", masterVol) + "_dt" + String.format("%.1f", dynoThresh) + ".wav")
        .getAbsolutePath();

    // Set step 0,0 = active, full velocity
    for (int t = 0; t < BridgeContract.TRACKS; t++) {
      for (int s = 0; s < BridgeContract.STEPS; s++) {
        bridge.setStep(t, s, false);
        bridge.setVelocity(t, s, 0.0);
      }
    }
    vm.advanceTime(4410);
    bridge.setStep(0, 0, true);
    bridge.setVelocity(0, 0, 1.0);
    bridge.setGate(0, 0, 0.9);
    bridge.setTrackLevel(0, 1.0);
    bridge.setTrackLength(0, 16);

    // ADSR: near-instant
    setAdsrArr(vm, BridgeContract.G_KIT_ATTACK, 0.001f);
    setAdsrArr(vm, BridgeContract.G_KIT_DECAY, 0.0f);
    setAdsrArr(vm, BridgeContract.G_KIT_SUSTAIN, 1.0f);
    setAdsrArr(vm, BridgeContract.G_KIT_RELEASE, 0.001f);

    // Zero sends
    setAdsrArr(vm, BridgeContract.G_DELAY_SEND, 0.0f);
    setAdsrArr(vm, BridgeContract.G_REVERB_SEND, 0.0f);

    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 4);

    bridge.startExport(wavPath);
    vm.advanceTime(SAMPLE_RATE * 50 / 1000);

    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, 0L);
    vm.advanceTime(SAMPLE_RATE * 4);

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    bridge.stopExport();
    vm.advanceTime(8820);
    vm.shutdown();

    File f = new File(wavPath);
    if (f.exists() && f.length() > 44) {
      float[] audio = loadWavAsFloat(wavPath);
      float p = peak(audio);

      // First hit analysis: find first non-zero and get peak of first 1000 samples
      int hitStart = 0;
      for (int i = 0; i < audio.length; i++) {
        if (Math.abs(audio[i]) > 0.00001) { hitStart = i; break; }
      }
      float hitPeak = 0;
      for (int i = hitStart; i < hitStart + 1000 && i < audio.length; i++) {
        float a = Math.abs(audio[i]);
        if (a > hitPeak) hitPeak = a;
      }
      System.out.printf("  WAV samples=%d, hitStart=%d, hitPeak=%.6f, overall peak=%.6f%n",
          audio.length, hitStart, hitPeak, p);

      // Print the first 100 samples of the hit
      System.out.println("  First 100 samples of hit (raw values):");
      for (int i = 0; i < 100 && (hitStart + i) < audio.length; i++) {
        System.out.printf("    %3d: %+.6f%n", i, audio[hitStart + i]);
      }

      return hitPeak;
    }
    return 0;
  }

  private void setAdsrArr(ChuckVM vm, String name, float val) {
    ChuckArray a = (ChuckArray) vm.getGlobalObject(name);
    if (a != null) a.setFloat(0, val);
  }

  // --- WAV helpers ---

  private float[] loadWavAsFloat(String path) throws Exception {
    File f = new File(path);
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(
        new BufferedInputStream(new FileInputStream(f)))) {
      AudioFormat fmt = ais.getFormat();
      if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
          || fmt.getSampleSizeInBits() != 16) {
        AudioFormat target = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, fmt.getSampleRate(), 16,
            1, 2, fmt.getSampleRate(), false);
        try (AudioInputStream converted = AudioSystem.getAudioInputStream(target, ais)) {
          return readFrames(converted, 1);
        }
      }
      return readFrames(ais, fmt.getChannels());
    }
  }

  private float[] readFrames(AudioInputStream ais, int channels) throws IOException {
    int frameSize = ais.getFormat().getFrameSize();
    long frameLen = ais.getFrameLength();
    if (frameLen <= 0) {
      java.util.List<Float> list = new java.util.ArrayList<>();
      byte[] buf = new byte[frameSize > 0 ? frameSize : 2];
      while (ais.read(buf) != -1) {
        float sum = 0;
        for (int c = 0; c < channels; c++) {
          int idx = c * 2;
          short pcm = (short) ((buf[idx + 1] << 8) | (buf[idx] & 0xFF));
          sum += pcm / 32768.0f;
        }
        list.add(sum / channels);
      }
      float[] arr = new float[list.size()];
      for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
      return arr;
    }
    float[] samples = new float[(int) frameLen];
    byte[] buf = new byte[frameSize > 0 ? frameSize : 2];
    for (int i = 0; i < frameLen; i++) {
      ais.read(buf);
      float sum = 0;
      for (int c = 0; c < channels; c++) {
        int idx = c * 2;
        short pcm = (short) ((buf[idx + 1] << 8) | (buf[idx] & 0xFF));
        sum += pcm / 32768.0f;
      }
      samples[i] = sum / channels;
    }
    return samples;
  }

  private float peak(float[] data) {
    float p = 0;
    for (float v : data) { float a = Math.abs(v); if (a > p) p = a; }
    return p;
  }
}
