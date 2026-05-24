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
 * Minimal ADSR diagnostic: SndBuf -> Adsr -> WvOut2 -> dac No Pan2, no HPF, no Dyno, no complex tap
 * chains. Just the raw ADSR output to see if it works in a simple chain.
 */
@org.junit.jupiter.api.Tag("slow")
public class AdsrSoloTest {

  private static final int SAMPLE_RATE = 44100;

  @Test
  void testAdsrInSimpleChain() throws Exception {
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

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-adsrsolo");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "adsrsolo.wav").getAbsolutePath();
    String bufWavPath = new File(tempDir, "bufsolo.wav").getAbsolutePath();

    vm.spork(
        () -> {
          float sr = (float) sampleRate();

          // MINIMAL chain: SndBuf -> Adsr -> Gain (tap) -> WvOut2 -> dac
          // AND: SndBuf -> Gain (tap) -> WvOut2 -> dac
          SndBuf buf = new SndBuf();
          DelugeAdsr env = new DelugeAdsr();

          // Main chain: buf -> env -> dac
          buf.chuck(env).chuck(dac());

          // Tap: env -> tapEnv -> envWv -> dac
          Gain tapEnv = new Gain();
          WvOut2 envWv = new WvOut2(sr);
          env.chuck(tapEnv);
          tapEnv.chuck(envWv);
          envWv.chuck(dac());

          // Tap: buf -> tapBuf -> bufWv -> dac
          Gain tapBuf = new Gain();
          WvOut2 bufWv = new WvOut2(sr);
          buf.chuck(tapBuf);
          tapBuf.chuck(bufWv);
          bufWv.chuck(dac());

          // Configure ADSR
          env.set(0.001, 0, 1, 0.05);
          env.forceMute();

          // Load sample
          advance(samp(100));
          buf.read(absPath);
          System.out.println("[shred] Loaded " + buf.samples() + " samples");

          // Set up recording
          envWv.wavWrite(wavPath);
          bufWv.wavWrite(bufWavPath);

          advance(samp(100));

          // Trigger
          buf.rate(1.0f);
          buf.pos(0);
          buf.gain(0.8f);
          env.keyOn();

          System.out.println(
              "[shred] Triggered: env.state=" + env.state() + " env.value=" + env.value());

          // Advance
          double durSec = buf.samples() / (double) sr + 0.5;
          System.out.println("[shred] Advancing " + String.format("%.3f", durSec) + " sec...");
          advance(second(durSec));

          System.out.println(
              "[shred] After advance: env.state="
                  + env.state()
                  + " env.value="
                  + env.value()
                  + " buf.pos="
                  + buf.pos());

          env.keyOff();
          advance(second(0.1));

          envWv.closeFile();
          bufWv.closeFile();
          System.out.println("[shred] Done");
        });

    vm.advanceTime(SAMPLE_RATE * 5);
    vm.shutdown();
    System.out.println("VM shutdown");

    // Analyze
    analyzeWav("buf_solo", bufWavPath);
    analyzeWav("adsr_solo", wavPath);
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
    // Print first 20 samples
    for (int j = 0; j < 20 && j < samples.length; j++) {
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
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > p) p = abs;
    }
    return p;
  }
}
