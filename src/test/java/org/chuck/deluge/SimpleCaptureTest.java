package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.chuck.core.ChuckDSL.*;

import java.io.*;
import org.chuck.core.ChuckVM;
import org.chuck.audio.util.*;
import org.junit.jupiter.api.Test;

/**
 * Minimal three-way test to isolate the engine output issue.
 * Tests are run inside vm.spork() so dac() is bound.
 */
public class SimpleCaptureTest {

  private static final int SR = 44100;

  @Test
  void testSndBufDirectCapture() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SR, 2);
    vm.setLogLevel(0);

    File kickFile = getKickFile();

    float[] orig = AudioAnalyzer.loadWav(kickFile);
    System.out.printf("ORIG: len=%d RMS=%.6f peak=%.6f%n", orig.length, rms(orig), peak(orig));
    System.out.print("ORIG[0..9]:");
    for (int i = 0; i < 10 && i < orig.length; i++) System.out.printf(" %.4f", orig[i]);
    System.out.println();

    // Run in spork so dac() is bound
    float[][] output = new float[1][];
    vm.spork(() -> {
      SndBuf buf = new SndBuf();
      buf.read(kickFile.getAbsolutePath());
      buf.rate(1.0);
      buf.pos(0);
      buf.gain(0.8f);

      Gain master = new Gain();
      master.gain(0.7f);
      buf.chuck(master).chuck(dac());

      // Capture with WvOut2
      File tmpWav = new File(System.getProperty("java.io.tmpdir"), "test1.wav");
      WvOut2 wv = new WvOut2(SR);
      wv.record(1);
      wv.wavWrite(tmpWav.getAbsolutePath());
      master.unchuck(dac());
      master.chuck(wv);
      wv.chuck(dac());

      advance(samp(SR)); // 1 second

      wv.closeFile();
      try {
        output[0] = AudioAnalyzer.loadWav(tmpWav);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      System.out.println("TEST1 shred done");
    });

    vm.advanceTime(SR * 2);
    vm.shutdown();

    float[] cap1 = output[0];
    System.out.printf("CAP1: len=%d RMS=%.6f peak=%.6f%n", cap1.length, rms(cap1), peak(cap1));
    System.out.print("CAP1[0..9]:");
    for (int i = 0; i < 10 && i < cap1.length; i++) System.out.printf(" %.4f", cap1[i]);
    System.out.println();
    int nz1 = 0;
    for (int i = 0; i < Math.min(300, cap1.length); i++) {
      if (Math.abs(cap1[i]) > 0.001) {
        if (nz1 == 0) System.out.println("CAP1 first>0.001 at idx=" + i + " val=" + cap1[i]);
        nz1++;
      }
    }
    double expPeak1 = peak(orig) * 0.8 * 0.7;
    System.out.printf("CAP1 expected peak=%.6f actual=%.6f ratio=%.2f%n", expPeak1, peak(cap1), peak(cap1)/expPeak1);
    assertTrue(peak(cap1) > 0.05, "Test1: peak too low: " + peak(cap1));
  }

  @Test
  void testSndBufAdsrCapture() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SR, 2);
    vm.setLogLevel(0);

    File kickFile = getKickFile();
    float[] orig = AudioAnalyzer.loadWav(kickFile);
    System.out.printf("ORIG: len=%d RMS=%.6f peak=%.6f%n", orig.length, rms(orig), peak(orig));

    float[][] output = new float[1][];
    vm.spork(() -> {
      SndBuf buf = new SndBuf();
      buf.read(kickFile.getAbsolutePath());
      buf.rate(1.0);
      buf.pos(0);
      buf.gain(0.8f);

      DelugeAdsr env = new DelugeAdsr();
      env.set(0.001, 0.0, 1.0, 0.001);
      env.forceMute();

      Gain master = new Gain();
      master.gain(0.7f);
      buf.chuck(env).chuck(master).chuck(dac());

      File tmpWav = new File(System.getProperty("java.io.tmpdir"), "test2.wav");
      WvOut2 wv = new WvOut2(SR);
      wv.record(1);
      wv.wavWrite(tmpWav.getAbsolutePath());
      master.unchuck(dac());
      master.chuck(wv);
      wv.chuck(dac());

      advance(samp(500));
      env.keyOn();
      System.out.println("[shred] keyOn: state=" + env.state() + " val=" + env.value());
      advance(samp(SR)); // 1 second
      env.keyOff();
      advance(samp(500));

      wv.closeFile();
      try {
        output[0] = AudioAnalyzer.loadWav(tmpWav);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    vm.advanceTime(SR * 3);
    vm.shutdown();

    float[] cap2 = output[0];
    System.out.printf("CAP2: len=%d RMS=%.6f peak=%.6f%n", cap2.length, rms(cap2), peak(cap2));
    System.out.print("CAP2[0..9]:");
    for (int i = 0; i < 10 && i < cap2.length; i++) System.out.printf(" %.4f", cap2[i]);
    System.out.println();
    int nz2 = 0;
    for (int i = 0; i < Math.min(300, cap2.length); i++) {
      if (Math.abs(cap2[i]) > 0.001) {
        if (nz2 == 0) System.out.println("CAP2 first>0.001 at idx=" + i + " val=" + cap2[i]);
        nz2++;
      }
    }
    double expPeak2 = peak(orig) * 0.8 * 0.7;
    System.out.printf("CAP2 expected peak=%.6f actual=%.6f ratio=%.2f%n", expPeak2, peak(cap2), peak(cap2)/expPeak2);
    assertTrue(peak(cap2) > 0.05, "Test2: peak too low: " + peak(cap2));
  }

  // ── helpers ──

  private File getKickFile() throws Exception {
    String p = "target/classes/SAMPLES/DRUMS/Kick/808 Kick.wav";
    File f = new File(p);
    if (!f.exists()) {
      java.io.InputStream is = getClass().getResourceAsStream("/SAMPLES/DRUMS/Kick/808 Kick.wav");
      File tmp = new File(System.getProperty("java.io.tmpdir"), "808Kick.wav");
      java.nio.file.Files.copy(is, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      f = tmp;
    }
    return f;
  }

  private double rms(float[] d) {
    double s = 0;
    for (float v : d) s += v * v;
    return Math.sqrt(s / d.length);
  }

  private double peak(float[] d) {
    double p = 0;
    for (float v : d) { double a = Math.abs(v); if (a > p) p = a; }
    return p;
  }
}
