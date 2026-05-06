package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.*;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Capture kit preview and sequencer audio to WAV files for analysis.
 * Saves to %TEMP%/deluge-wav-capture/*.wav so they can be loaded into
 * an audio editor (Audacity, etc.) for comparison.
 */
public class CaptureKitPreviewWav {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void capturePreviewAudio() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    System.setProperty("chuck.loglevel", "0");

    File captureDir = new File(System.getProperty("java.io.tmpdir"), "deluge-wav-capture");
    captureDir.mkdirs();
    System.out.println("WAV capture dir: " + captureDir.getAbsolutePath());

    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // Load 2024 song (16-voice kit at track 0)
    java.io.File songFile = new java.io.File(
        "C:\\Users\\ludo\\delugedownload\\ludocard\\SONGS\\2024.XML");
    assertTrue(songFile.exists(), "2024.XML not found");

    var project = DelugeXmlParser.parseSong(
        new java.io.FileInputStream(songFile), songFile.getName());

    var kitTrack = (org.chuck.deluge.model.KitTrackModel) project.getTracks().get(0);
    String kickRelPath = kitTrack.getSounds().get(0).getSamplePath();
    java.io.File libraryDir = org.chuck.deluge.project.PreferencesManager.getLibraryDir();
    String kickPath = new java.io.File(libraryDir, kickRelPath).getAbsolutePath();

    // Load the kick as reference (raw sample, unprocessed)
    float[] rawKick = loadWavAsFloat(kickPath);
    System.out.printf("Raw kick: %d samples, RMS=%.6f, peak=%.6f%n",
        rawKick.length, rms(rawKick), peak(rawKick));

    // Start engine
    vm.spork(new DelugeEngineDSL(vm));
    vm.advanceTime(44100);

    // Set kit track 0 with the kick sample
    vm.setGlobalString("g_sample_0", kickPath);
    bridge.setTrackType(0, 0);
    // Mute all other tracks
    for (int t = 1; t < BridgeContract.TRACKS; t++) {
      bridge.setMute(t, true);
    }

    // Trigger load
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE * 2);

    // ── CAPTURE 1: Sequencer playback ──
    System.out.println("\n=== Capture 1: Sequencer playback ===");
    captureDir.mkdirs();
    String seqWav = new File(captureDir, "seq_kick.wav").getAbsolutePath();

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

    // Set ADSR
    { ChuckArray a = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_ATTACK);
      if (a != null) a.setFloat(0, 0.001f); }
    { ChuckArray a = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_DECAY);
      if (a != null) a.setFloat(0, 0.0f); }
    { ChuckArray a = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_SUSTAIN);
      if (a != null) a.setFloat(0, 1.0f); }
    { ChuckArray a = (ChuckArray) vm.getGlobalObject(BridgeContract.G_KIT_RELEASE);
      if (a != null) a.setFloat(0, 0.001f); }

    // Zero out delay/reverb sends
    { ChuckArray a = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
      if (a != null) a.setFloat(0, 0.0f); }
    { ChuckArray a = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);
      if (a != null) a.setFloat(0, 0.0f); }

    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(SAMPLE_RATE / 4);

    // Start WvOut2 export
    bridge.startExport(seqWav);
    vm.advanceTime(SAMPLE_RATE * 50 / 1000); // brief settling

    // Start sequencer
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    vm.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, 0L);

    // Capture 4 seconds
    vm.advanceTime(SAMPLE_RATE * 4);

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
    bridge.stopExport();
    vm.advanceTime(8820);

    File seqFile = new File(seqWav);
    if (seqFile.exists() && seqFile.length() > 44) {
      float[] seqAudio = loadWavAsFloat(seqWav);
      System.out.printf("Sequencer WAV: %d samples, RMS=%.6f, peak=%.6f%n",
          seqAudio.length, rms(seqAudio), peak(seqAudio));
      System.out.println("First 50 samples:");
      for (int i = 0; i < 50 && i < seqAudio.length; i++) {
        System.out.printf("  %3d: %8.6f%n", i, seqAudio[i]);
      }
    } else {
      System.out.println("SEQUENCER WAV empty or missing: " + seqWav);
    }

    // ── CAPTURE 2: Preview playback ──
    // Need a fresh VM since kit_preview_shred was already sporked
    vm.shutdown();

    ChuckVM vm2 = new ChuckVM(SAMPLE_RATE, 2);
    BridgeContract bridge2 = new BridgeContract();
    bridge2.register(vm2);

    vm2.spork(new DelugeEngineDSL(vm2));
    vm2.advanceTime(44100);

    vm2.setGlobalString("g_sample_0", kickPath);
    bridge2.setTrackType(0, 0);
    for (int t = 1; t < BridgeContract.TRACKS; t++) {
      bridge2.setMute(t, true);
    }
    vm2.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm2.advanceTime(SAMPLE_RATE * 2);

    System.out.println("\n=== Capture 2: Preview playback ===");
    String prvWav = new File(captureDir, "preview_kick.wav").getAbsolutePath();

    // Start export, then trigger preview
    bridge2.startExport(prvWav);
    vm2.advanceTime(SAMPLE_RATE * 100 / 1000);

    // Trigger preview on track 0
    vm2.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, 0L);
    vm2.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
    vm2.advanceTime(SAMPLE_RATE * 2); // play for 2 seconds

    // Release preview
    vm2.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, -1L);
    vm2.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
    vm2.advanceTime(SAMPLE_RATE * 50 / 1000);

    bridge2.stopExport();
    vm2.advanceTime(4410);

    File prvFile = new File(prvWav);
    if (prvFile.exists() && prvFile.length() > 44) {
      float[] prvAudio = loadWavAsFloat(prvWav);
      System.out.printf("Preview WAV: %d samples, RMS=%.6f, peak=%.6f%n",
          prvAudio.length, rms(prvAudio), peak(prvAudio));
      System.out.println("First 50 samples:");
      for (int i = 0; i < 50 && i < prvAudio.length; i++) {
        System.out.printf("  %3d: %8.6f%n", i, prvAudio[i]);
      }
    } else {
      System.out.println("PREVIEW WAV empty or missing: " + prvWav);
    }

    vm2.shutdown();
  }

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

  private double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }

  private double peak(float[] data) {
    double p = 0;
    for (float v : data) { double abs = Math.abs(v); if (abs > p) p = abs; }
    return p;
  }
}
