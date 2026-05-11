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
 * Direct ADSR test: put WvOut2 INLINE in the chain, not as a tap.
 * If this works, the problem is in the tap routing (multi-source pulling).
 * Chain: buf -> env -> wv -> dac
 */
public class AdsrInlineTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void testAdsrInline() throws Exception {
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

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-adsrinline");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "adsrinline.wav").getAbsolutePath();

    vm.spork(() -> {
      float sr = (float) sampleRate();

      // DIRECT chain: SndBuf -> Adsr -> WvOut2 -> dac
      // The WvOut2 is INLINE, not a tap.
      SndBuf buf = new SndBuf();
      DelugeAdsr env = new DelugeAdsr();
      WvOut2 wv = new WvOut2(sr);

      // Chain: buf -> env -> wv -> dac
      buf.chuck(env).chuck(wv).chuck(dac());

      // Configure
      env.set(0.001, 0, 1, 0.05);
      env.forceMute();

      // Load sample
      advance(samp(100));
      buf.read(absPath);
      System.out.println("[shred] Loaded " + buf.samples() + " samples");

      // Set up recording
      wv.wavWrite(wavPath);

      advance(samp(100));

      // Trigger
      buf.rate(1.0f);
      buf.pos(0);
      buf.gain(0.8f);
      env.keyOn();

      System.out.println("[shred] Triggered: env.state=" + env.state() + " env.value=" + env.value());

      // Advance
      double durSec = buf.samples() / (double) sr + 0.5;
      System.out.println("[shred] Advancing " + String.format("%.3f", durSec) + " sec...");
      advance(second(durSec));

      System.out.println("[shred] After advance: env.state=" + env.state() + " env.value=" + env.value() + " buf.pos=" + buf.pos());

      // Manual post-check
      buf.pos(0);
      float testBuf = buf.tick(99999);
      float testEnv = env.tick(99999);
      System.out.println("[shred] Post-advance manual tick: buf=" + testBuf + " env=" + testEnv);

      env.keyOff();
      advance(second(0.1));

      wv.closeFile();
      System.out.println("[shred] Done");
    });

    vm.advanceTime(SAMPLE_RATE * 5);
    vm.shutdown();
    System.out.println("VM shutdown");

    // Analyze
    analyzeWav("adsr_inline", wavPath);
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
    for (int j = 0; j < 20 && j < samples.length; j++) {
      System.out.printf("    %3d: %8.6f%n", j, samples[j]);
    }
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
