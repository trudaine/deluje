package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.chuck.deluge.model.KitTrackModel;

import org.chuck.audio.*;
import org.chuck.audio.util.*;
import static org.chuck.core.ChuckDSL.*;

import org.junit.jupiter.api.Test;

/**
 * Minimal test: SndBuf → ADSR → WvOut2 directly, no engine.
 * Isolates whether SndBuf and WvOut2 work correctly.
 */
public class MinimalSndBufTest {

  private static final int SAMPLE_RATE = 44100;

  private static volatile String capturedWavPath;

  @Test
  void testSndBufDirectToWvOut() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(1);

    // Find the kick sample
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = kit.getSounds().get(0).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();
    System.out.println("Kick sample: " + absPath);

    // Load original for comparison
    float[] original = loadWavAsFloat(absPath);
    System.out.println("Original: " + original.length + " samples, RMS=" + rms(original) + ", peak=" + peak(original));

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-minimal-test");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "minimal_kick.wav").getAbsolutePath();
    capturedWavPath = wavPath;

    // Create a simple shred that plays the sample through SndBuf → ADSR → WvOut2 → dac
    vm.spork(() -> {
      try {
        float sr = (float) sampleRate();
        SndBuf buf = new SndBuf();
        DelugeAdsr env = new DelugeAdsr();
        WvOut2 wvOut = new WvOut2(sr);

        // Chain: buf → env → wvOut → dac
        buf.chuck(env).chuck(wvOut).chuck(dac());

        // Load sample
        System.out.println("[shred] Loading sample...");
        buf.read(absPath);
        System.out.println("[shred] Loaded: " + buf.samples() + " samples");

        if (buf.samples() > 0) {
          buf.pos(0);
          buf.rate(1.0f);
          env.set(0.001, 0.0, 1.0, 0.05);
          env.keyOn();

          wvOut.wavWrite(wavPath);
          wvOut.fileGain(1.0f);

          System.out.println("[shred] Recording to: " + wavPath);

          // Advance enough to play through the sample
          double sampleDurSec = buf.samples() / (double) sr;
          System.out.println("[shred] Sample duration: " + String.format("%.3f", sampleDurSec) + "s");
          System.out.println("[shred] Before advance: pos=" + buf.pos() + " rate=" + buf.rate() + " env.state=" + env.state());

          // DIAGNOSTIC: manually tick a few samples
          System.out.println("[shred] Diagnostic manual ticks:");
          for (int t = 1; t <= 20; t++) {
            float b = buf.tick(t);
            float e = env.tick(t);
            float w = wvOut.tick(t);
            if (t <= 10 || t == 20) {
              System.out.printf("[shred] t=%d: buf=%.6f env=%.6f wvOut=%.6f%n", t, b, e, w);
            }
          }
          // Reset for actual play
          buf.pos(0);
          env.keyOn();

          advance(second(sampleDurSec + 0.2));
          System.out.println("[shred] After advance: pos=" + buf.pos() + " env.state=" + env.state());
          env.keyOff();
          advance(second(0.1));

          wvOut.closeFile();
          System.out.println("[shred] WAV written.");
          capturedWavPath = wavPath;
        } else {
          System.out.println("[shred] FAILED: SndBuf has 0 samples after read!");
        }
      } catch (Exception e) {
        System.err.println("[shred] Error: " + e.getMessage());
        e.printStackTrace();
      }
    });

    // Run for enough time
    vm.advanceTime(SAMPLE_RATE * 3); // 3 seconds
    vm.shutdown();

    // Read back and check from test thread
    File engineWav = new File(capturedWavPath);
    assertTrue(engineWav.exists(), "WAV not produced: " + capturedWavPath);
    assertTrue(engineWav.length() > 44, "WAV too small: " + engineWav.length());

    float[] engineOut = loadWavAsFloat(capturedWavPath);
    System.out.println("Engine output: " + engineOut.length + " samples, RMS=" + rms(engineOut) + ", peak=" + peak(engineOut));

    // Print first 20 samples
    System.out.println("First 20 engine samples:");
    for (int i = 0; i < 20 && i < engineOut.length; i++) {
      System.out.printf("  %3d: %8.6f%n", i, engineOut[i]);
    }
    System.out.println("Last 20 engine samples:");
    for (int i = Math.max(0, engineOut.length - 20); i < engineOut.length; i++) {
      System.out.printf("  %3d: %8.6f%n", i, engineOut[i]);
    }

    // Should have some non-zero audio
    boolean hasAudio = false;
    for (int i = 0; i < Math.min(5000, engineOut.length); i++) {
      if (Math.abs(engineOut[i]) > 0.01) { hasAudio = true; break; }
    }
    assertTrue(hasAudio, "Engine output is all near-zero!");
    System.out.println("BASIC SndBuf→WvOut TEST PASSED: engine produces audio");
  }

  @Test
  void testSndBufWvOutSeparateShreds() throws Exception {
    // Test the engine-style approach: one shred produces audio, another records
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(1);

    // Find the kick sample
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = kit.getSounds().get(0).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-minimal-test2");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "minimal_kick2.wav").getAbsolutePath();

    // Spork producer (plays the sample) and recorder (captures with WvOut2)
    vm.spork(() -> {
      SndBuf buf = new SndBuf();
      DelugeAdsr env = new DelugeAdsr();
      // Chain: buf → env → dac
      buf.chuck(env).chuck(dac());

      buf.read(absPath);
      System.out.println("[play] Loaded " + buf.samples() + " samples");

      // Wait a tiny bit for recorder to set up
      advance(samp(100));

      // Play
      buf.pos(0);
      buf.rate(1.0f);
      env.set(0.001, 0.0, 1.0, 0.05);
      env.keyOn();

      // Advance through sample
      double durSec = buf.samples() / (double) SAMPLE_RATE;
      advance(second(durSec + 0.2));
      env.keyOff();
      advance(second(0.1));
      System.out.println("[play] Done playing");

      // Set flag to stop
      vm.setGlobalInt("g_done", 1L);
    });

    vm.spork(() -> {
      float sr = (float) sampleRate();
      WvOut2 wvOut = new WvOut2(sr);

      // Wait a bit for the producer to get ready
      advance(samp(200));

      // Splice into chain: insert WvOut2 between last UGen and dac
      // Since we can't modify the producer's chain from here, we use a tap approach
      Gain tap = new Gain();
      tap.gain(1.0f);
      tap.chuck(wvOut).chuck(dac());

      // Actually let's connect tap to dac and the producer connects to tap
      // But we can't modify producer from here...

      // Alternative: the WvOut2 just needs to be connected to dac to capture everything
      // WvOut2 captures all audio flowing through it
      wvOut.chuck(dac());
      wvOut.wavWrite(wavPath);
      wvOut.fileGain(1.0f);

      System.out.println("[rec] Recording to: " + wavPath);

      // Wait until done
      while (vm.getGlobalInt("g_done") == 0) {
        advance(ms(50));
      }

      wvOut.closeFile();
      System.out.println("[rec] WAV written.");

      // Read back
      try {
        float[] engineOut = loadWavAsFloat(wavPath);
        System.out.println("[rec] Engine output: " + engineOut.length + " samples, RMS=" + rms(engineOut) + ", peak=" + peak(engineOut));
      } catch (Exception e) {
        System.out.println("[rec] Failed to read WAV: " + e.getMessage());
      }
    });

    vm.advanceTime(SAMPLE_RATE * 5); // 5 seconds
    vm.shutdown();
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
