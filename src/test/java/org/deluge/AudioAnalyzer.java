package org.deluge;

import java.io.*;
import javax.sound.sampled.*;

/** Shared audio analysis utilities for engine accuracy tests. */
public class AudioAnalyzer {

  /** Sample rate used in all tests. */
  public static final int SAMPLE_RATE = 44100;

  public static float[] loadWav(String path) throws Exception {
    File f = new File(path);
    if (f.exists()) return loadWavFromStream(new FileInputStream(f));
    File fallback = new File("../deluge/" + path);
    if (fallback.exists()) return loadWavFromStream(new FileInputStream(fallback));
    String resPath = path.startsWith("/") ? path : "/" + path;
    InputStream ris = AudioAnalyzer.class.getResourceAsStream(resPath);
    if (ris == null) throw new FileNotFoundException("Cannot find: " + path);
    return loadWavFromStream(ris);
  }

  public static float[] loadWav(File f) throws Exception {
    if (!f.exists()) {
      File fallback = new File("../deluge/" + f.getPath());
      if (fallback.exists()) {
        f = fallback;
      }
    }
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
      return decodeAis(ais);
    }
  }

  private static float[] loadWavFromStream(InputStream stream) throws Exception {
    if (!stream.markSupported()) {
      stream = new BufferedInputStream(stream);
    }
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(stream)) {
      return decodeAis(ais);
    }
  }

  private static float[] decodeAis(AudioInputStream ais) throws Exception {
    AudioFormat format = ais.getFormat();
    int bytesPerFrame = format.getFrameSize();
    if (bytesPerFrame <= 0) throw new IOException("Invalid frame size");
    int channels = format.getChannels();
    boolean isBigEndian = format.isBigEndian();
    int sampleSizeInBits = format.getSampleSizeInBits();

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = ais.read(buffer)) != -1) {
      baos.write(buffer, 0, read);
    }
    byte[] audioBytes = baos.toByteArray();
    int totalFrames = audioBytes.length / bytesPerFrame;
    float[] mono = new float[totalFrames];

    for (int i = 0; i < totalFrames; i++) {
      int frameOffset = i * bytesPerFrame;
      float sum = 0.0f;
      for (int c = 0; c < channels; c++) {
        int sampleOffset = frameOffset + c * (sampleSizeInBits / 8);
        float val = 0.0f;
        if (sampleSizeInBits == 16) {
          int b1 = audioBytes[sampleOffset];
          int b2 = audioBytes[sampleOffset + 1];
          short sample =
              isBigEndian ? (short) ((b1 << 8) | (b2 & 0xff)) : (short) ((b2 << 8) | (b1 & 0xff));
          val = sample / 32768.0f;
        } else if (sampleSizeInBits == 8) {
          int sample = audioBytes[sampleOffset] & 0xff;
          val = (sample - 128) / 128.0f;
        } else if (sampleSizeInBits == 24) {
          int b1 = audioBytes[sampleOffset];
          int b2 = audioBytes[sampleOffset + 1];
          int b3 = audioBytes[sampleOffset + 2];
          int sample =
              isBigEndian
                  ? ((b1 & 0xff) << 16) | ((b2 & 0xff) << 8) | (b3 & 0xff)
                  : ((b3 & 0xff) << 16) | ((b2 & 0xff) << 8) | (b1 & 0xff);
          if ((sample & 0x800000) != 0) {
            sample |= 0xff000000;
          }
          val = sample / 8388608.0f;
        }
        sum += val;
      }
      mono[i] = sum / channels;
    }
    return mono;
  }

  /** Compute RMS of a float array. */
  public static double rms(float[] data) {
    double sumSq = 0;
    for (float v : data) sumSq += v * v;
    return Math.sqrt(sumSq / data.length);
  }

  /** Compute peak absolute value. */
  public static double peak(float[] data) {
    double p = 0;
    for (float v : data) {
      double abs = Math.abs(v);
      if (abs > p) p = abs;
    }
    return p;
  }

  /** Normalize source array to have the same RMS as target. Returns a new array. */
  public static float[] normalizeRms(float[] src, float[] target) {
    double srcRms = rms(src);
    double tgtRms = rms(target);
    if (srcRms < 1e-10 || tgtRms < 1e-10) return src.clone();
    float scale = (float) (tgtRms / srcRms);
    float[] out = new float[src.length];
    for (int i = 0; i < src.length; i++) out[i] = src[i] * scale;
    return out;
  }

  /**
   * Compute normalized cross-correlation at zero lag. Returns a value in [-1, 1] where 1 =
   * identical shape.
   */
  public static double correlation(float[] a, float[] b) {
    int len = Math.min(a.length, b.length);
    if (len < 2) return 0;
    double meanA = 0, meanB = 0;
    for (int i = 0; i < len; i++) {
      meanA += a[i];
      meanB += b[i];
    }
    meanA /= len;
    meanB /= len;
    double num = 0, denA = 0, denB = 0;
    for (int i = 0; i < len; i++) {
      double da = a[i] - meanA;
      double db = b[i] - meanB;
      num += da * db;
      denA += da * da;
      denB += db * db;
    }
    double den = Math.sqrt(denA * denB);
    return den > 1e-15 ? num / den : 0;
  }

  /** Compute RMS error between two arrays after optimal linear scaling. */
  public static double rmsError(float[] reference, float[] candidate) {
    int len = Math.min(reference.length, candidate.length);
    if (len < 2) return 999;
    double sumRefCand = 0, sumCandSq = 0;
    for (int i = 0; i < len; i++) {
      sumRefCand += reference[i] * candidate[i];
      sumCandSq += candidate[i] * candidate[i];
    }
    double scale = sumCandSq > 1e-15 ? sumRefCand / sumCandSq : 1.0;
    double errSq = 0;
    for (int i = 0; i < len; i++) {
      double d = reference[i] - scale * candidate[i];
      errSq += d * d;
    }
    return Math.sqrt(errSq / len);
  }

  /** Find the sample offset (lag) that maximizes cross-correlation. */
  public static int findBestLag(float[] a, float[] b, int maxLag) {
    int len = Math.min(a.length, b.length);
    int bestLag = 0;
    double bestCorr = -1;
    for (int lag = -maxLag; lag <= maxLag; lag++) {
      int start = Math.max(0, lag);
      int end = Math.min(len, len + lag);
      int n = end - start;
      if (n < 4) continue;
      double num = 0, denA = 0, denB = 0;
      for (int i = start; i < end; i++) {
        int ai = i;
        int bi = i - lag;
        if (bi < 0 || bi >= len) continue;
        num += a[ai] * b[bi];
        denA += a[ai] * a[ai];
        denB += b[bi] * b[bi];
      }
      double den = Math.sqrt(denA * denB);
      double corr = den > 1e-15 ? num / den : 0;
      if (corr > bestCorr) {
        bestCorr = corr;
        bestLag = lag;
      }
    }
    return bestLag;
  }

  /**
   * Align two signals by finding the onset in {@code signal} and returning aligned slices of {@code
   * ref} and {@code signal} for comparison.
   *
   * @return array of 2 float[]: [0] = reference slice, [1] = signal slice
   */
  public static float[][] alignSignals(float[] ref, float[] signal) {
    int engOnset = -1;
    double noiseFloor = rms(signal) * 0.1;
    if (noiseFloor < 0.0001) noiseFloor = 0.0001;
    for (int i = 0; i < signal.length; i++) {
      if (Math.abs(signal[i]) > noiseFloor) {
        engOnset = i;
        break;
      }
    }
    if (engOnset < 0) engOnset = 0;

    int compLen = Math.min(ref.length, signal.length - engOnset);
    float[] origSlice = new float[compLen];
    float[] engSlice = new float[compLen];
    System.arraycopy(ref, 0, origSlice, 0, compLen);
    System.arraycopy(signal, engOnset, engSlice, 0, compLen);

    // Fine-tune alignment
    int maxLag = (engOnset > 10000 && ref.length < 30000) ? 2000 : 500;
    int fineLag = findBestLag(origSlice, engSlice, maxLag);

    if (fineLag > 0) {
      int alen = Math.min(compLen, ref.length - fineLag);
      origSlice = new float[alen];
      engSlice = new float[alen];
      System.arraycopy(ref, fineLag, origSlice, 0, alen);
      System.arraycopy(signal, engOnset, engSlice, 0, alen);
    } else if (fineLag < 0) {
      int shift = -fineLag;
      int alen = Math.min(compLen - shift, ref.length);
      origSlice = new float[alen];
      engSlice = new float[alen];
      System.arraycopy(ref, 0, origSlice, 0, alen);
      System.arraycopy(signal, engOnset + shift, engSlice, 0, alen);
    }

    return new float[][] {origSlice, engSlice};
  }

  /**
   * Estimate fundamental frequency via autocorrelation. Returns frequency in Hz, or 0 if
   * undetectable.
   */
  public static double estimateFrequency(float[] buf, int sr, double minFreq, double maxFreq) {
    int minLag = (int) (sr / maxFreq);
    int maxLag = (int) (sr / minFreq);
    if (maxLag >= buf.length) maxLag = buf.length - 1;
    if (minLag < 1) minLag = 1;

    double bestCorr = 0;
    int bestLag = 0;

    for (int lag = minLag; lag <= maxLag; lag++) {
      double num = 0, denA = 0, denB = 0;
      int n = buf.length - lag;
      for (int i = 0; i < n; i++) {
        num += buf[i] * buf[i + lag];
        denA += buf[i] * buf[i];
        denB += buf[i + lag] * buf[i + lag];
      }
      double den = Math.sqrt(denA * denB);
      if (den > 1e-15) {
        double corr = num / den;
        if (corr > bestCorr) {
          bestCorr = corr;
          bestLag = lag;
        }
      }
    }

    if (bestLag == 0 || bestCorr < 0.1) return 0;
    return (double) sr / bestLag;
  }

  /**
   * Extract harmonic magnitudes via Goertzel-like DFT at integer multiples of the fundamental
   * frequency. Uses the closest FFT bin.
   *
   * <p>Performs an N-point DFT (N = buf length or nextPow2) and picks the peak magnitude in the bin
   * closest to each harmonic.
   *
   * @param buf input signal
   * @param fundamental fundamental frequency in Hz
   * @param sr sample rate
   * @param nHarmonics number of harmonics to extract
   * @return array of harmonic magnitudes, normalized so fundamental = 1.0
   */
  public static double[] harmonicProfile(float[] buf, double fundamental, int sr, int nHarmonics) {
    int n = buf.length;
    // Use a power-of-two FFT size for efficiency
    int fftSize = 1;
    while (fftSize < n) fftSize <<= 1;
    if (fftSize > 65536) fftSize = 65536;

    double[] profile = new double[nHarmonics];
    double[] window = new double[n];
    // Hanning window
    for (int i = 0; i < n; i++) {
      window[i] = buf[i] * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1)));
    }

    for (int h = 0; h < nHarmonics; h++) {
      double freq = fundamental * (h + 1);
      if (freq >= sr / 2.0) {
        profile[h] = 0;
        continue;
      }

      // Continuous Goertzel-style DFT at exact harmonic frequency
      double real = 0, imag = 0;
      double angle = 2.0 * Math.PI * freq / sr;
      for (int i = 0; i < n; i++) {
        double t = angle * i;
        real += window[i] * Math.cos(t);
        imag -= window[i] * Math.sin(t);
      }
      profile[h] = Math.sqrt(real * real + imag * imag);
    }

    // Normalize so fundamental = 1.0
    if (profile[0] > 1e-15) {
      for (int h = 0; h < nHarmonics; h++) profile[h] /= profile[0];
    }
    return profile;
  }

  /**
   * Compare harmonic profile against an ideal shape model.
   *
   * @param profile harmonic magnitudes (fundamental = 1.0)
   * @param shape "SINE", "SQUARE", "SAW", or "TRIANGLE"
   * @return match quality score (0 = poor, 1 = perfect)
   */
  public static double shapeMatchQuality(double[] profile, String shape) {
    int nh = profile.length;
    double score = 0;
    double maxScore = 0;

    // Skip fundamental (h=0) — we compare harmonic ratios
    switch (shape.toUpperCase()) {
      case "SINE":
        // Only fundamental; all harmonics should be near zero
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = 0.0;
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1); // lower harmonics matter more
          score += weight * Math.max(0, 1.0 - err * 5.0);
          maxScore += weight;
        }
        break;

      case "SQUARE":
        // Odd harmonics only at 1/n amplitude
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = (h % 2 == 0) ? 0.0 : 1.0 / (h + 1);
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 3.0);
          maxScore += weight;
        }
        break;

      case "SAW":
        // All harmonics at 1/n amplitude
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = 1.0 / (h + 1);
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 3.0);
          maxScore += weight;
        }
        break;

      case "TRIANGLE":
        // Odd harmonics at 1/n² amplitude (even harmonics at 0)
        for (int h = 1; h < nh; h++) {
          double actual = profile[h];
          double ideal = (h % 2 == 0) ? 0.0 : 1.0 / ((h + 1) * (h + 1));
          double err = Math.abs(actual - ideal);
          double weight = 1.0 / (h + 1);
          score += weight * Math.max(0, 1.0 - err * 5.0);
          maxScore += weight;
        }
        break;

      default:
        return 0;
    }

    return maxScore > 0 ? score / maxScore : 0;
  }

  /** Find the first sample index where signal exceeds noiseFloor. */
  public static int findOnset(float[] signal) {
    double nf = rms(signal) * 0.1;
    if (nf < 0.0001) nf = 0.0001;
    for (int i = 0; i < signal.length; i++) {
      if (Math.abs(signal[i]) > nf) return i;
    }
    return 0;
  }
}
