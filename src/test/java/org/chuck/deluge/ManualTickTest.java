package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.chuck.audio.*;
import org.chuck.audio.util.*;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Manual tick test: bypass the VM's advanceTime and manually tick UGens step by step to isolate
 * where the signal is lost.
 */
public class ManualTickTest {

  @Test
  void stepByStepTick() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    float sampleRate = 44100;

    // Load a sample
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = ((SoundDrum) kit.getDrums().get(0)).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();
    System.out.println("Sample: " + absPath);

    // Load original
    float[] original = AudioAnalyzer.loadWav(new File(absPath));
    System.out.println("Original: " + original.length + " samples, peak=" + peak(original));

    // Create UGens
    SndBuf buf = new SndBuf();
    DelugeAdsr env = new DelugeAdsr(sampleRate);
    WvOut2 wvOut = new WvOut2(sampleRate);

    // Chain: buf → env → wvOut
    buf.chuck(env).chuck(wvOut);

    // Load sample into buf
    buf.read(absPath);
    System.out.println("Buf loaded: " + buf.samples() + " samples");

    buf.pos(0);
    buf.rate(1.0f);
    env.set(0.001, 0.0, 1.0, 0.05);
    env.keyOn();

    // Open WAV output
    File tempDir = new File(System.getProperty("java.io.tmpdir"), "manual-tick");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "manual_tick.wav").getAbsolutePath();
    wvOut.wavWrite(wavPath);
    wvOut.fileGain(1.0f);

    // MANUALLY tick the chain sample by sample
    int numSamples = Math.min(original.length, 44100); // first second
    System.out.println("Ticking " + numSamples + " samples manually...");

    int printInterval = 4410; // every 100ms
    for (int t = 1; t <= numSamples; t++) {
      // 1. Tick buf
      float bufOut = buf.tick(t);

      // 2. Tick env (sums its source = buf, but buf already ticked at this time)
      float envOut = env.tick(t);

      // 3. Tick wvOut (MultiChannelUGen)
      float wvOutOut = wvOut.tick(t);

      if (t <= 10 || (t % printInterval == 0) || t == numSamples) {
        System.out.printf(
            "t=%6d: buf=%10.8f env=%10.8f wvOut[0]=%10.8f wvOut[1]=%10.8f%n",
            t, bufOut, envOut, wvOut.getChannelLastOut(0), wvOut.getChannelLastOut(1));
      }
    }

    wvOut.closeFile();

    // Load and check output
    float[] engineOut = AudioAnalyzer.loadWav(new File(wavPath));
    System.out.println(
        "\nEngine output: "
            + engineOut.length
            + " samples, RMS="
            + rms(engineOut)
            + ", peak="
            + peak(engineOut));

    // Check first 20 samples
    System.out.println("\nFirst 20 engine samples:");
    for (int i = 0; i < 20 && i < engineOut.length; i++) {
      System.out.printf(" %3d: %8.6f (original: %8.6f)%n", i, engineOut[i], original[i]);
    }

    // Check if any audio
    boolean hasAudio = false;
    for (int i = 0; i < Math.min(5000, engineOut.length); i++) {
      if (Math.abs(engineOut[i]) > 0.001) {
        hasAudio = true;
        break;
      }
    }
    assertTrue(hasAudio, "Engine output is all near-zero! peak=" + peak(engineOut));
    System.out.println("\nPASSED: Manual tick produces audio");
  }

  @Test
  void stepByStepTickWvOutDirect() throws Exception {
    // Even simpler: buf → wvOut, no env
    System.setProperty("chuck.audio.dummy", "true");
    float sampleRate = 44100;

    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = ((SoundDrum) kit.getDrums().get(0)).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists());
    String absPath = localTarget.getAbsolutePath();

    float[] original = AudioAnalyzer.loadWav(new File(absPath));

    SndBuf buf = new SndBuf();
    WvOut2 wvOut = new WvOut2(sampleRate);
    Gain g = new Gain();
    g.gain(1.0f);

    // Chain: buf → g → wvOut
    buf.chuck(g);
    g.chuck(wvOut);

    buf.read(absPath);
    buf.pos(0);
    buf.rate(1.0f);

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "manual-tick2");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "manual_tick2.wav").getAbsolutePath();
    wvOut.wavWrite(wavPath);
    wvOut.fileGain(1.0f);

    int numSamples = Math.min(original.length, 44100);

    // Alternative: tick directly through wvOut which should pull from sources
    System.out.println("\n=== Test 2: buf→g→wvOut, tick wvOut only ===");
    for (int t = 1; t <= 5; t++) {
      // Tick buf first manually
      float b = buf.tick(t);
      // Tick gain manually
      float gl = g.tick(t);
      // Tick wvOut (should pull from g which already has value)
      float w = wvOut.tick(t);
      System.out.printf("t=%d: buf=%f gain=%f wvOut=%f%n", t, b, gl, w);
    }

    // Now tick properly: just wvOut, which should pull from source chain
    System.out.println("\n=== Test 3: Reset and tick wvOut (pulling from chain) ===");
    buf.pos(0);
    for (int t = 1; t <= numSamples; t++) {
      float w = wvOut.tick(t);
      if (t <= 5 || t % 4410 == 0) {
        System.out.printf(
            "t=%d: wvOut[0]=%f wvOut[1]=%f%n",
            t, wvOut.getChannelLastOut(0), wvOut.getChannelLastOut(1));
      }
    }

    wvOut.closeFile();
    float[] engineOut = AudioAnalyzer.loadWav(new File(wavPath));
    System.out.println("\nEngine: " + engineOut.length + " samples, peak=" + peak(engineOut));

    boolean hasAudio = false;
    for (int i = 0; i < Math.min(5000, engineOut.length); i++) {
      if (Math.abs(engineOut[i]) > 0.001) {
        hasAudio = true;
        break;
      }
    }
    assertTrue(hasAudio, "All near-zero! peak=" + peak(engineOut));
  }

  // --- helpers ---
  private double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }

  private double peak(float[] data) {
    double p = 0;
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > p) p = abs;
    }
    return p;
  }
}
