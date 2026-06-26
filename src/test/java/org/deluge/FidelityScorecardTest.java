package org.deluge;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.*;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Per-synth fidelity scorecard: our engine render vs the real Deluge hardware recordings of the
 * all-synths arranger songs. For each synth, compares a normalized log-magnitude spectrum (timbre,
 * alignment/level-tolerant) and reports cosine similarity. THROWAWAY analysis tool.
 */
public class FidelityScorecardTest {

  static final int SR = 44100;
  static final String SYNTH_DIR = "/home/ludo/ludocard/SYNTHS";
  static final File CARD = new File("/home/ludo/ludocard");

  // ---- WAV (16/24-bit, stereo->mono float) ----
  static float[] readWavMono(File f) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
      byte[] hdr = new byte[12];
      raf.readFully(hdr);
      int ch = 2, bits = 16;
      long dataOff = -1, dataLen = 0;
      while (raf.getFilePointer() < raf.length() - 8) {
        byte[] id = new byte[4];
        raf.readFully(id);
        int sz = Integer.reverseBytes(raf.readInt());
        String cid = new String(id);
        if (cid.equals("fmt ")) {
          long p = raf.getFilePointer();
          raf.readShort(); // fmt
          ch = Short.reverseBytes(raf.readShort());
          raf.readInt(); // sr
          raf.readInt(); // byterate
          raf.readShort(); // blockalign
          bits = Short.reverseBytes(raf.readShort());
          raf.seek(p + sz);
        } else if (cid.equals("data")) {
          dataOff = raf.getFilePointer();
          dataLen = sz & 0xFFFFFFFFL;
          break;
        } else {
          raf.seek(raf.getFilePointer() + sz);
        }
      }
      int bytesPer = bits / 8;
      int frames = (int) (dataLen / (bytesPer * ch));
      float[] out = new float[frames];
      raf.seek(dataOff);
      byte[] buf = new byte[(int) Math.min(dataLen, (long) frames * bytesPer * ch)];
      raf.readFully(buf);
      int i = 0;
      for (int fr = 0; fr < frames; fr++) {
        double sum = 0;
        for (int c = 0; c < ch; c++) {
          int v;
          if (bytesPer == 3) {
            v = (buf[i] & 0xFF) | ((buf[i + 1] & 0xFF) << 8) | (buf[i + 2] << 16);
            sum += v / (double) (1 << 23);
          } else {
            v = (buf[i] & 0xFF) | (buf[i + 1] << 8);
            sum += v / 32768.0;
          }
          i += bytesPer;
        }
        out[fr] = (float) (sum / ch);
      }
      return out;
    }
  }

  // ---- normalized log spectrum (48 log bins 50Hz..15kHz) via Goertzel ----
  static final int BANDS = 48;

  static double[] spectrum(float[] x, int from, int len) {
    double[] s = new double[BANDS];
    for (int k = 0; k < BANDS; k++) {
      double freq = 50.0 * Math.pow(15000.0 / 50.0, k / (double) (BANDS - 1));
      double w = 2 * Math.PI * freq / SR, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
      for (int n = from; n < from + len && n < x.length; n++) {
        double t = x[n] + c * s1 - s2;
        s2 = s1;
        s1 = t;
      }
      double p = s2 * s2 + s1 * s1 - c * s1 * s2;
      s[k] = Math.log10(Math.max(p, 1e-12));
    }
    double mean = 0;
    for (double v : s) mean += v;
    mean /= BANDS;
    for (int k = 0; k < BANDS; k++) s[k] -= mean; // remove overall level/tilt offset
    return s;
  }

  static double cosine(double[] a, double[] b) {
    double dot = 0, na = 0, nb = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      na += a[i] * a[i];
      nb += b[i] * b[i];
    }
    return dot / (Math.sqrt(na * nb) + 1e-12);
  }

  static float[] renderSynth(File xml) throws Exception {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(60, 110);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(fs);
    int n = SR * 3;
    float[] out = new float[n];
    int got = 0;
    for (int b = 0; got < n; b++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < n; i++)
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
    }
    return out;
  }

  static boolean ciExists(File root, String rel) {
    File cur = root;
    for (String part : rel.split("/")) {
      if (part.isEmpty()) continue;
      File[] kids = cur.listFiles();
      if (kids == null) return false;
      File m = null;
      for (File k : kids)
        if (k.getName().equalsIgnoreCase(part)) {
          m = k;
          break;
        }
      if (m == null) return false;
      cur = m;
    }
    return cur.isFile();
  }

  static boolean playable(File xml) {
    try {
      SynthTrackModel s = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
      for (String raw : new String[] {s.getOsc1RawXml(), s.getOsc2RawXml()}) {
        if (raw == null) continue;
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("fileName=\"([^\"]+)\"").matcher(raw);
        while (m.find()) if (!ciExists(CARD, m.group(1))) return false;
      }
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static double rms(float[] x, int from, int len) {
    double s = 0;
    int c = 0;
    for (int n = from; n < from + len && n < x.length; n++) {
      s += x[n] * (double) x[n];
      c++;
    }
    return Math.sqrt(s / Math.max(c, 1));
  }

  void scoreSong(List<File> synths, File recWav, String label, List<Double> all, List<String> na)
      throws Exception {
    float[] rec = readWavMono(recWav);
    int lead = 0;
    while (lead < rec.length && Math.abs(rec[lead]) < 0.003) lead++;
    int per = (rec.length - lead) / synths.size();
    int win = SR * 2; // 2s analysis window
    System.out.printf(
        "%n=== %s : %d synths, rec %.1fs, lead %.2fs, %.2fs/synth ===%n",
        label, synths.size(), rec.length / (double) SR, lead / (double) SR, per / (double) SR);
    for (int k = 0; k < synths.size(); k++) {
      String name = synths.get(k).getName().replace(".XML", "");
      float[] our = renderSynth(synths.get(k));
      double ourMax = 0;
      for (int off = 0; off + win < our.length; off += SR / 4)
        ourMax = Math.max(ourMax, rms(our, off, win));
      if (ourMax < 0.002) { // genuinely silent in our engine (e.g. multisample w/o samples loaded)
        na.add(name);
        System.out.printf("  %3d  %-30s   n/a (our render silent)%n", k, name);
        continue;
      }
      // spectrum from our loudest 2s window (handles slow attack / arp gaps)
      int ourBest = 0;
      double ourBR = -1;
      for (int off = 0; off + win < our.length; off += SR / 4) {
        double r = rms(our, off, win);
        if (r > ourBR) {
          ourBR = r;
          ourBest = off;
        }
      }
      double[] ours = spectrum(our, ourBest, win);
      // hardware: within this synth's slice, find the loudest 2s window (robust to spacing drift)
      int sliceStart = lead + k * per, bestOff = sliceStart;
      double bestR = -1;
      for (int off = sliceStart;
          off + win < sliceStart + per && off + win < rec.length;
          off += SR / 4) {
        double r = rms(rec, off, win);
        if (r > bestR) {
          bestR = r;
          bestOff = off;
        }
      }
      double sim = cosine(ours, spectrum(rec, bestOff, win));
      all.add(sim);
      System.out.printf("  %3d  %-30s  %.3f%n", k, name, sim);
    }
  }

  @Test
  void scorecard() throws Exception {
    // Local analysis tool: needs the ludocard SYNTHS + the hardware resamplings. Skip otherwise.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        new File(SYNTH_DIR).isDirectory()
            && new File("/home/ludo/ALL_SYNTHS_SONG/ALLSYN_1/output_000.wav").isFile(),
        "fidelity scorecard needs ~/ludocard SYNTHS + ~/ALL_SYNTHS_SONG recordings");
    File[] files =
        new File(SYNTH_DIR)
            .listFiles(
                (d, n) ->
                    (n.endsWith(".XML") || n.endsWith(".xml"))
                        && !n.toUpperCase().startsWith("SONG"));
    Arrays.sort(files, Comparator.comparing(File::getName));
    List<File> playable = new ArrayList<>();
    for (File f : files) if (playable(f)) playable.add(f);
    System.out.println("[Scorecard] playable synths: " + playable.size());
    List<File> p1 = playable.subList(0, Math.min(94, playable.size()));
    List<File> p2 = playable.size() > 94 ? playable.subList(94, playable.size()) : List.of();

    List<Double> all = new ArrayList<>();
    List<String> na = new ArrayList<>();
    scoreSong(
        new ArrayList<>(p1),
        new File("/home/ludo/ALL_SYNTHS_SONG/ALLSYN_1/output_000.wav"),
        "ALLSYN_1",
        all,
        na);
    scoreSong(
        new ArrayList<>(p2),
        new File("/home/ludo/ALL_SYNTHS_SONG/ALLSYN_2/output_000.wav"),
        "ALLSYN_2",
        all,
        na);
    System.out.printf(
        "%n  not-measurable (our render silent, multisamples need samples): %d%n", na.size());

    Collections.sort(all);
    double mean = all.stream().mapToDouble(d -> d).average().orElse(0);
    double median = all.get(all.size() / 2);
    long ge9 = all.stream().filter(d -> d >= 0.9).count();
    long ge8 = all.stream().filter(d -> d >= 0.8).count();
    long lt6 = all.stream().filter(d -> d < 0.6).count();
    System.out.printf(
        "%n=== FIDELITY SUMMARY (cosine of normalized log-spectrum vs hardware) ===%n");
    System.out.printf("  n=%d  mean=%.3f  median=%.3f%n", all.size(), mean, median);
    System.out.printf(
        "  >=0.90: %d (%.0f%%)   >=0.80: %d (%.0f%%)   <0.60: %d%n",
        ge9, 100.0 * ge9 / all.size(), ge8, 100.0 * ge8 / all.size(), lt6);
    System.out.printf("  worst: %s%n", all.subList(0, Math.min(10, all.size())));
  }
}
