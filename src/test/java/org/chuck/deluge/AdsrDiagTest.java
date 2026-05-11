package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;

import org.chuck.audio.*;
import org.chuck.audio.filter.*;
import org.chuck.audio.util.*;
import org.chuck.audio.fx.*;
import static org.chuck.core.ChuckDSL.*;

import org.junit.jupiter.api.Test;

/**
 * Diagnostic test: use DelugeAdsrDiag (which overrides block tick and dumps output samples)
 * to see exactly what ADSR produces during the block advance.
 */
public class AdsrDiagTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void testAdsrWithDiag() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);

    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = ((SoundDrum) kit.getDrums().get(0)).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();
    System.out.println("Kick sample: " + absPath);

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-adsrdiag");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "adsrdiag.wav").getAbsolutePath();

    // Use modifier so we can close the dump from inside the shred
    DelugeAdsrDiag[] diagRef = new DelugeAdsrDiag[1];

    vm.spork(() -> {
      float sr = (float) sampleRate();
      SndBuf buf = new SndBuf();
      DelugeAdsrDiag env = new DelugeAdsrDiag(sr);
      diagRef[0] = env;
      WvOut2 wv = new WvOut2(sr);

      // Chain: buf -> env -> wv -> dac
      buf.chuck(env).chuck(wv).chuck(dac());

      advance(samp(100));
      buf.read(absPath);
      wv.wavWrite(wavPath);
      advance(samp(100));

      buf.rate(1.0f);
      buf.pos(0);
      buf.gain(0.8f);
      env.set(0.001, 0, 1, 0.05);
      env.forceMute();
      env.keyOn();

      System.out.println("[shred] Triggered: env.state=" + env.state() + " env.value=" + env.value());

      double durSec = buf.samples() / (double) sr + 0.5;
      System.out.println("[shred] Advancing " + String.format("%.3f", durSec) + " sec...");
      advance(second(durSec));

      // Post-advance manual tick
      buf.pos(0);
      float testBuf = buf.tick(99999);
      float testEnv = env.tick(99999);
      System.out.println("[shred] POST-advance manual tick: buf.tick(99999)=" + testBuf
          + " env.tick(99999)=" + testEnv + " env.state=" + env.state() + " env.value=" + env.value());

      env.keyOff();
      advance(second(0.1));

      wv.closeFile();
      env.closeDump();
      System.out.println("[shred] Done");
    });

    vm.advanceTime(SAMPLE_RATE * 5);
    vm.shutdown();
    System.out.println("VM shutdown");

    analyzeWav("adsr_diag", wavPath);

    // Read and analyze the binary dump
    File dumpFile = new File(System.getProperty("java.io.tmpdir"), "deluge-adsr-dump.bin");
    if (dumpFile.exists()) {
      float[] dumpSamples = loadBinaryDump(dumpFile);
      System.out.println("ADSR BINARY DUMP: " + dumpSamples.length + " samples, RMS=" + rms(dumpSamples)
          + ", peak=" + peak(dumpSamples));
      boolean dumpHasAudio = false;
      for (int j = 0; j < Math.min(5000, dumpSamples.length); j++) {
        if (Math.abs(dumpSamples[j]) > 0.01) { dumpHasAudio = true; break; }
      }
      System.out.println("  dumpHasAudio=" + dumpHasAudio);
      System.out.println("  first20:");
      for (int j = 0; j < 20 && j < dumpSamples.length; j++) {
        System.out.printf("    %3d: %8.6f%n", j, dumpSamples[j]);
      }
      // Show all initial samples (first 100) to catch envelope attack
      System.out.println("  first100 (all):");
      for (int j = 0; j < Math.min(100, dumpSamples.length); j++) {
        System.out.printf("    %3d: %8.6f%n", j, dumpSamples[j]);
      }
    } else {
      System.out.println("BINARY DUMP FILE NOT FOUND");
    }
  }

  private float[] loadBinaryDump(File f) throws IOException {
    DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
    java.util.ArrayList<Float> list = new java.util.ArrayList<>();
    try {
      while (true) {
        list.add(dis.readFloat());
      }
    } catch (EOFException e) {
      // done
    }
    dis.close();
    float[] arr = new float[list.size()];
    for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
    return arr;
  }

  private void analyzeWav(String name, String path) throws Exception {
    File f = new File(path);
    if (!f.exists()) { System.out.println(name + ": FILE NOT FOUND"); return; }
    float[] samples = AudioAnalyzer.loadWav(new File(path));
    System.out.println(name + ": " + samples.length + " samples, RMS=" + rms(samples) + ", peak=" + peak(samples));
    boolean hasAudio = false;
    for (int j = 0; j < Math.min(5000, samples.length); j++) {
      if (Math.abs(samples[j]) > 0.01) { hasAudio = true; break; }
    }
    System.out.println("  hasAudio=" + hasAudio);
    System.out.println("  first20:");
    for (int j = 0; j < 20 && j < samples.length; j++) {
      System.out.printf("    %3d: %8.6f%n", j, samples[j]);
    }
  }

  private double peak(float[] data) {
    double p = 0;
    for (float v : data) { double abs = Math.abs(v); if (abs > p) p = abs; }
    return p;
  }

  private double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }
}
