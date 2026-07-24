package org.deluge;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Analysis probe for the FM-bells fidelity dispute (docs/FIDELITY_GAP_ANALYSIS.md §4.1/4.1bis):
 * §4.1bis wrote the bells' terrible scores off as a MISALIGNED hardware slice, while the ear-check
 * said the hardware sounds metallic. This probe settles it with direct evidence: it extracts the
 * exact hardware slice the scorecard scores for a broken preset (068 FM Bells 1) and a fine one
 * (050 FM Basic Bass), writes both slices plus our renders to WAV under target/fm_probe/, and
 * prints per-250ms RMS + spectral-centroid curves for each side. If the hardware slice shows a
 * clean onset and stays near ~500 Hz, the softness is real hardware behavior (engine divergence);
 * if the slice has no onset or contains neighbouring content, the misalignment story holds.
 *
 * <p>Self-skips without the ludocard recordings, like FidelityScorecardTest.
 */
public class FmBellsAlignmentProbeTest {

  static final int SR = 44100;

  @Test
  void probe() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        new File(FidelityScorecardTest.SYNTH_DIR).isDirectory()
            && new File(FidelityScorecardTest.HOME + "/ALL_SYNTHS_SONG/ALLSYN_1/output_000.wav")
                .isFile(),
        "needs ~/ludocard SYNTHS + ~/ALL_SYNTHS_SONG recordings");

    // EXACT same list construction as the scorecard — including the SONG/CAL exclusions and
    // the playable() filter, or every slice index shifts and we compare the wrong notes.
    File[] files =
        new File(FidelityScorecardTest.SYNTH_DIR)
            .listFiles(
                (d, n) ->
                    (n.endsWith(".XML") || n.endsWith(".xml"))
                        && !n.toUpperCase().startsWith("SONG")
                        && !n.matches("\\d\\d CAL .*"));
    java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
    List<File> synths = new ArrayList<>();
    for (File f : files) if (FidelityScorecardTest.playable(f)) synths.add(f);
    List<File> firstHalf = synths.subList(0, Math.min(94, synths.size()));

    float[] rec =
        FidelityScorecardTest.readWavMono(
            new File(FidelityScorecardTest.HOME + "/ALL_SYNTHS_SONG/ALLSYN_1/output_000.wav"));
    int lead = 0;
    while (lead < rec.length && Math.abs(rec[lead]) < 0.003) lead++;
    int tail = rec.length;
    while (tail > lead && Math.abs(rec[tail - 1]) < 0.003) tail--;
    int[] onset = FidelityScorecardTest.detectOnsets(rec, lead, tail, firstHalf.size());

    File outDir = new File("target/fm_probe");
    outDir.mkdirs();

    for (String targetName : new String[] {"068", "050", "069"}) {
      int k = -1;
      for (int i = 0; i < firstHalf.size(); i++) {
        if (firstHalf.get(i).getName().startsWith(targetName)) {
          k = i;
          break;
        }
      }
      if (k < 0) {
        System.out.println("preset " + targetName + " not in first half — skipped");
        continue;
      }
      String name = firstHalf.get(k).getName().replace(".XML", "");
      int sliceStart = onset[k];
      int sliceEnd = (k + 1 < onset.length) ? onset[k + 1] : tail;
      System.out.printf(
          "%n=== %s  k=%d  hwSlice %.2fs..%.2fs (%.2fs) ===%n",
          name,
          k,
          sliceStart / (double) SR,
          sliceEnd / (double) SR,
          (sliceEnd - sliceStart) / (double) SR);

      float[] hw = java.util.Arrays.copyOfRange(rec, sliceStart, sliceEnd);
      writeWav(new File(outDir, name + "_hw.wav"), hw);
      printCurves("HW ", hw);

      org.deluge.firmware2.Functions.resetNoiseSeed();
      float[] our = FidelityScorecardTest.renderSynth(firstHalf.get(k));
      org.deluge.storage.audio.AudioFileReader.clearCache();
      writeWav(new File(outDir, name + "_our.wav"), our);
      printCurves("OUR", our);
    }
  }

  /** Per-250ms RMS and spectral centroid, up to 4 s. */
  static void printCurves(String tag, float[] x) {
    int frame = SR / 4;
    StringBuilder rmsL = new StringBuilder();
    StringBuilder cenL = new StringBuilder();
    for (int f = 0; f < 16 && (f + 1) * frame <= x.length; f++) {
      int off = f * frame;
      double r = FidelityScorecardTest.rms(x, off, frame);
      rmsL.append(String.format(" %5.3f", r));
      cenL.append(String.format(" %5.0f", r < 0.003 ? 0 : centroid(x, off, frame)));
    }
    System.out.println(tag + " rms/250ms: " + rmsL);
    System.out.println(tag + " cent/250ms:" + cenL);
  }

  /** Spectral centroid (Hz) of one frame via Goertzel-free DFT magnitudes on 64 log bands. */
  static double centroid(float[] x, int off, int len) {
    int n = 1;
    while (n * 2 <= len && n < 16384) n *= 2;
    double[] re = new double[n], im = new double[n];
    for (int i = 0; i < n; i++) {
      double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
      re[i] = x[off + i] * w;
    }
    fft(re, im);
    double num = 0, den = 0;
    for (int b = 1; b < n / 2; b++) {
      double mag = Math.hypot(re[b], im[b]);
      double hz = b * (double) SR / n;
      num += mag * hz;
      den += mag;
    }
    return den > 0 ? num / den : 0;
  }

  static void fft(double[] re, double[] im) {
    int n = re.length;
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        double t = re[i];
        re[i] = re[j];
        re[j] = t;
        t = im[i];
        im[i] = im[j];
        im[j] = t;
      }
    }
    for (int len = 2; len <= n; len <<= 1) {
      double ang = -2 * Math.PI / len;
      double wr = Math.cos(ang), wi = Math.sin(ang);
      for (int i = 0; i < n; i += len) {
        double cr = 1, ci = 0;
        for (int j = 0; j < len / 2; j++) {
          int a = i + j, b = i + j + len / 2;
          double ur = re[a], ui = im[a];
          double vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
          re[a] = ur + vr;
          im[a] = ui + vi;
          re[b] = ur - vr;
          im[b] = ui - vi;
          double ncr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = ncr;
        }
      }
    }
  }

  static void writeWav(File f, float[] x) throws Exception {
    byte[] pcm = new byte[x.length * 2];
    for (int i = 0; i < x.length; i++) {
      int v = (int) Math.max(-32768, Math.min(32767, x[i] * 32767));
      pcm[2 * i] = (byte) v;
      pcm[2 * i + 1] = (byte) (v >> 8);
    }
    try (javax.sound.sampled.AudioInputStream ais =
        new javax.sound.sampled.AudioInputStream(
            new java.io.ByteArrayInputStream(pcm),
            new javax.sound.sampled.AudioFormat(SR, 16, 1, true, false),
            x.length)) {
      javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, f);
    }
  }
}
