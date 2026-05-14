package org.chuck.deluge.firmware.storage.wave_table;

/**
 * Utility to generate band-limited versions of a wavetable cycle. Prevents aliasing by zeroing out
 * harmonics above Nyquist for higher octaves.
 */
public class WavetableGenerator {

  /**
   * Generates bands for a wavetable based on a single source cycle. Uses a simple Cooley-Tukey FFT
   * implementation.
   */
  public static void generateBands(WaveTable waveTable, float[] sourceCycle) {
    int n = sourceCycle.length;
    if ((n & (n - 1)) != 0) {
      throw new IllegalArgumentException("Source cycle must be power of two");
    }

    // 1. FFT to frequency domain
    double[] re = new double[n];
    double[] im = new double[n];
    for (int i = 0; i < n; i++) re[i] = sourceCycle[i];
    fft(re, im, false);

    // 2. Generate bands
    int numBands = waveTable.bands.size();
    for (int b = 0; b < numBands; b++) {
      WaveTableBand band = waveTable.bands.get(b);
      int bandSize = band.cycleSizeNoDuplicates;

      double[] bandRe = new double[bandSize];
      double[] bandIm = new double[bandSize];

      // Map harmonics, zeroing those above band's Nyquist
      int harmonics = bandSize / 2;
      for (int i = 0; i < harmonics; i++) {
        bandRe[i] = re[i];
        bandIm[i] = im[i];
        // Hermitian symmetry for real signals
        if (i > 0) {
          bandRe[bandSize - i] = re[n - i];
          bandIm[bandSize - i] = im[n - i];
        }
      }

      // 3. IFFT back to time domain
      fft(bandRe, bandIm, true);

      // 4. Store as 16-bit PCM
      int totalWithDupes = bandSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE;
      band.data = new short[totalWithDupes];
      for (int i = 0; i < bandSize; i++) {
        band.data[i] =
            (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, bandRe[i] * 32767.0));
      }
      // Fill duplicates
      for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
        band.data[bandSize + i] = band.data[i];
      }
    }
  }

  private static void fft(double[] re, double[] im, boolean inverse) {
    int n = re.length;
    int bits = Integer.numberOfTrailingZeros(n);
    for (int i = 0; i < n; i++) {
      int j = Integer.reverse(i) >>> (32 - bits);
      if (j > i) {
        double tr = re[i];
        re[i] = re[j];
        re[j] = tr;
        double ti = im[i];
        im[i] = im[j];
        im[j] = ti;
      }
    }
    for (int len = 2; len <= n; len <<= 1) {
      double ang = 2.0 * Math.PI / len * (inverse ? 1 : -1);
      double wlenRe = Math.cos(ang);
      double wlenIm = Math.sin(ang);
      for (int i = 0; i < n; i += len) {
        double wRe = 1.0;
        double wIm = 0.0;
        for (int j = 0; j < len / 2; j++) {
          double uRe = re[i + j];
          double uIm = im[i + j];
          double vRe = re[i + j + len / 2] * wRe - im[i + j + len / 2] * wIm;
          double vIm = re[i + j + len / 2] * wIm + im[i + j + len / 2] * wRe;
          re[i + j] = uRe + vRe;
          im[i + j] = uIm + vIm;
          re[i + j + len / 2] = uRe - vRe;
          im[i + j + len / 2] = uIm - vIm;
          double nextWRe = wRe * wlenRe - wIm * wlenIm;
          wIm = wRe * wlenIm + wIm * wlenRe;
          wRe = nextWRe;
        }
      }
    }
    if (inverse) {
      for (int i = 0; i < n; i++) {
        re[i] /= n;
        im[i] /= n;
      }
    }
  }
}
