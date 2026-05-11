package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.chuck.core.ChuckArray;
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
 * Isolate the exact kit chain in a single shred and dump samples at each stage.
 * Chain: SndBuf → ADSR → Pan2 → master(Gain) → HPF(20Hz) → Dyno(limiter) → masterTap(Gain) → WvOut2
 *
 * FOCUSED DIAG: We put a WvOut2 directly after SndBuf (before ADSR) and directly after ADSR
 * to find where signal is lost during block-based advance().
 */
public class ChainDiagnosticTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void diagnoseKitChain() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    ChuckVM vm = new ChuckVM(SAMPLE_RATE, 2);
    vm.setLogLevel(0);

    // Find the kick sample
    InputStream is = getClass().getResourceAsStream("/KITS/000 TR-808.XML");
    assertNotNull(is);
    KitTrackModel kit = DelugeXmlParser.parseKit(is, "000 TR-808");
    String samplePath = ((SoundDrum) kit.getDrums().get(0)).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();
    System.out.println("Kick sample: " + absPath);
    System.out.println("Kick WAV frames: " + AudioAnalyzer.loadWav(new File(absPath)).length);

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-chaindiag");
    tempDir.mkdirs();

    final String wavAfterBuf  = new File(tempDir, "diag_afterbuf.wav").getAbsolutePath();
    final String wavAfterAdsr = new File(tempDir, "diag_afteradsr.wav").getAbsolutePath();

    // Track ADSR state from the shred
    final boolean[] adsrHadSignalInAdvance = {false};

    vm.spork(() -> {
      float sr = (float) sampleRate();

      // Build the exact kit chain
      SndBuf buf = new SndBuf();
      DelugeAdsr env = new DelugeAdsr();
      Pan2 pan = new Pan2();
      Gain master = new Gain();
      HPF hpf = new HPF(sr);
      Dyno limit = new Dyno(sr);
      Gain masterTap = new Gain();

      // Chain: buf → env → pan → master → hpf → limit → masterTap → dac
      buf.chuck(env).chuck(pan).chuck(master);
      master.chuck(hpf).chuck(limit).chuck(masterTap).chuck(dac());

      // Taps: tap with Gain → WvOut2 → blackhole
      // buf → tapBufGain → bufWv → blackhole
      // env → tapEnvGain → envWv → blackhole
      Gain tapBufGain = new Gain();
      Gain tapEnvGain = new Gain();
      WvOut2 bufWv = new WvOut2(sr);
      WvOut2 envWv = new WvOut2(sr);
      Gain bh = new Gain();
      bh.gain(0); // blackhole - absorbs output silently

      buf.chuck(tapBufGain);
      tapBufGain.chuck(bufWv);
      bufWv.chuck(bh);

      env.chuck(tapEnvGain);
      tapEnvGain.chuck(envWv);
      envWv.chuck(bh);

      // Configure
      hpf.freq(20);
      limit.limiter();
      master.gain(1.0);
      masterTap.gain(1.0);
      env.set(0.001, 0, 1, 0.05);
      env.forceMute();
      pan.pan(0.0f);

      // Load sample
      advance(samp(100));
      buf.read(absPath);
      System.out.println("[shred] Loaded " + buf.samples() + " samples");

      // DIAG: Pre-trigger state
      System.out.println("[shred] PRE-trigger: env.state=" + env.state() + " env.value=" + env.value());

      // Prepare recording files
      bufWv.wavWrite(wavAfterBuf);
      envWv.wavWrite(wavAfterAdsr);

      advance(samp(100));

      // Trigger
      buf.rate(1.0f);
      buf.pos(0);
      buf.gain(0.8f);
      env.keyOn();

      System.out.println("[shred] POST-trigger: env.state=" + env.state() + " env.value=" + env.value());
      System.out.println("[shred] buf.pos=" + buf.pos() + " buf.rate=" + buf.rate());
      System.out.println("[shred] buf.samples[0..4]=" + buf.valueAt(0) + "," + buf.valueAt(1) + "," + buf.valueAt(2) + "," + buf.valueAt(3) + "," + buf.valueAt(4));

      // Now advance time to play the sample
      double durSec = buf.samples() / (double) sr + 0.5;
      System.out.println("[shred] Advancing " + String.format("%.3f", durSec) + " seconds...");

      advance(second(durSec));

      // DIAG: Post-advance state
      System.out.println("[shred] POST-advance: env.state=" + env.state() + " env.value=" + env.value());
      System.out.println("[shred] POST-advance: buf.pos=" + buf.pos());
      System.out.println("[shred] buf.samples[0..4]=" + buf.valueAt(0) + "," + buf.valueAt(1) + "," + buf.valueAt(2) + "," + buf.valueAt(3) + "," + buf.valueAt(4));

      // DIAG: Manually tick ADSR with buf's first sample to verify envelope still works
      buf.pos(0);
      float testBufOut = buf.tick(99999);
      float testEnvOut = env.tick(99999);
      System.out.println("[shred] POST-diagnostic tick: buf.tick(99999)=" + testBufOut + " env.tick(99999)=" + testEnvOut + " env.state=" + env.state() + " env.value=" + env.value());

      adsrHadSignalInAdvance[0] = true;

      env.keyOff();
      advance(second(0.1));

      bufWv.closeFile();
      envWv.closeFile();
      System.out.println("[shred] All WAVs written");
    });

    vm.advanceTime(SAMPLE_RATE * 5);
    vm.shutdown();

    // Analyze each WAV
    analyzeWav("afterbuf", wavAfterBuf);
    analyzeWav("afteradsr", wavAfterAdsr);
  }

  private void analyzeWav(String name, String path) throws Exception {
    File f = new File(path);
    if (!f.exists()) {
      System.out.println(name + ": FILE NOT FOUND");
      return;
    }
    float[] samples = AudioAnalyzer.loadWav(new File(path));
    System.out.println(name + ": " + samples.length + " samples, RMS=" + rms(samples) + ", peak=" + peak(samples));
    boolean hasAudio = false;
    for (int j = 0; j < Math.min(5000, samples.length); j++) {
      if (Math.abs(samples[j]) > 0.01) { hasAudio = true; break; }
    }
    System.out.println("  hasAudio=" + hasAudio);
    // Print first 40 samples
    for (int j = 0; j < 40 && j < samples.length; j++) {
      System.out.printf("    %3d: %8.6f%n", j, samples[j]);
    }
    // Find first non-zero
    for (int j = 0; j < samples.length; j++) {
      if (Math.abs(samples[j]) > 0.001) {
        System.out.println("  first non-zero at sample " + j + ": " + samples[j]);
        break;
      }
    }
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
