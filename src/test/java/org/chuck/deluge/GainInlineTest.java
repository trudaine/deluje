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
import org.chuck.audio.filter.*;
import org.chuck.audio.util.*;
import org.chuck.audio.fx.*;
import static org.chuck.core.ChuckDSL.*;

import org.junit.jupiter.api.Test;

/**
 * Test: does Gain work as a drop-in replacement for ADSR?
 * Chain: buf -> gain -> wv -> dac
 * If this works, the issue is specific to DelugeAdsr.compute().
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
    String samplePath = kit.getSounds().get(0).getSamplePath();
    File localTarget = new File("target/classes/" + samplePath);
    assertTrue(localTarget.exists(), "Sample file not found: " + localTarget);
    String absPath = localTarget.getAbsolutePath();
    System.out.println("Kick sample: " + absPath);

    File tempDir = new File(System.getProperty("java.io.tmpdir"), "deluge-gaintest");
    tempDir.mkdirs();
    String wavPath = new File(tempDir, "gaintest.wav").getAbsolutePath();

    vm.spork(() -> {
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
    if (!f.exists()) { System.out.println(name + ": FILE NOT FOUND"); return; }
    float[] samples = loadWavAsFloat(path);
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

  private float[] loadWavAsFloat(String path) throws Exception {
    File f = new File(path);
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(
        new BufferedInputStream(new FileInputStream(f)))) {
      AudioFormat fmt = ais.getFormat();
      if (fmt.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || fmt.getSampleSizeInBits() != 16) {
        AudioFormat target = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, fmt.getSampleRate(), 16, 1, 2, fmt.getSampleRate(), false);
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
