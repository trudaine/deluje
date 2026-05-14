package org.chuck.deluge;

import static org.chuck.core.ChuckDSL.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.chuck.audio.*;
import org.chuck.audio.filter.*;
import org.chuck.audio.fx.*;
import org.chuck.audio.util.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Test: does Gain work as a drop-in replacement for ADSR? Chain: buf -> gain -> wv -> dac If this
 * works, the issue is specific to DelugeAdsr.compute().
 */
public class GainInlineTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void testGainInline() throws Exception {
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

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-gaintest");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "gaintest.wav").getAbsolutePath();

    vm.spork(
        () -> {
          float sr = (float) sampleRate();
          SndBuf buf = new SndBuf();
          Gain g = new Gain();
          WvOut2 wv = new WvOut2(sr);

          // Chain: buf -> gain -> wv -> dac
          buf.chuck(g).chuck(wv).chuck(dac());

          advance(samp(100));
          buf.read(absPath);
          wv.wavWrite(wavPath);
          advance(samp(100));

          buf.rate(1.0f);
          buf.pos(0);
          buf.gain(0.8f);

          double durSec = buf.samples() / (double) sr + 0.5;
          System.out.println("[shred] Advancing " + String.format("%.3f", durSec) + " sec...");
          advance(second(durSec));
          System.out.println("[shred] buf.pos=" + buf.pos());

          wv.closeFile();
        });

    vm.advanceTime(SAMPLE_RATE * 5);
    vm.shutdown();

    analyzeWav("gain_inline", wavPath);
  }

  private void analyzeWav(String name, String path) throws Exception {
    File f = new File(path);
    if (!f.exists()) {
      System.out.println(name + ": FILE NOT FOUND");
      return;
    }
    float[] samples = AudioAnalyzer.loadWav(new File(path));
    System.out.println(
        name + ": " + samples.length + " samples, RMS=" + rms(samples) + ", peak=" + peak(samples));
    boolean hasAudio = false;
    for (int j = 0; j < Math.min(5000, samples.length); j++) {
      if (Math.abs(samples[j]) > 0.01) {
        hasAudio = true;
        break;
      }
    }
    System.out.println("  hasAudio=" + hasAudio);
    System.out.println("  first20:");
    for (int j = 0; j < 20 && j < samples.length; j++) {
      System.out.printf("    %3d: %8.6f%n", j, samples[j]);
    }
  }

  private double peak(float[] data) {
    double p = 0;
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > p) p = abs;
    }
    return p;
  }

  private double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }
}
