package org.deluge.firmware2;

/**
 * Utility to generate band-limited versions of a wavetable cycle. Prevents aliasing by zeroing out
 * harmonics above Nyquist for higher octaves.
 */
public class WavetableGenerator {

  /**
   * Generates bands for a wavetable based on the source cycles. Uses a simple Cooley-Tukey FFT
   * implementation.
   */
  public static void generateBands(WaveTable waveTable, float[] sourceCycle) {
    int numCycles = waveTable.numCycles;
    if (numCycles <= 0) return;
    int cycleSize = sourceCycle.length / numCycles;
    if ((cycleSize & (cycleSize - 1)) != 0) {
      throw new IllegalArgumentException("Wavetable cycle size must be power of two");
    }

    int numBands = waveTable.bands.size();

    // Process cycle-by-cycle to support multi-cycle wavetables
    for (int c = 0; c < numCycles; c++) {
      // 1. FFT single cycle to frequency domain
      double[] re = new double[cycleSize];
      double[] im = new double[cycleSize];
      for (int i = 0; i < cycleSize; i++) {
        re[i] = sourceCycle[c * cycleSize + i];
      }
      fft(re, im, false);

      // 2. Generate band-limited versions
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
            bandRe[bandSize - i] = re[cycleSize - i];
            bandIm[bandSize - i] = im[cycleSize - i];
          }
        }

        // 3. IFFT back to time domain
        fft(bandRe, bandIm, true);

        // 4. Store as 16-bit PCM at the correct cycle offset in the pre-allocated band array
        int cycleOffset =
            c * (bandSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        for (int i = 0; i < bandSize; i++) {
          band.data[cycleOffset + i] =
              (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, bandRe[i] * 32767.0));
        }
        // Fill duplicates at the end of this cycle
        for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
          band.data[cycleOffset + bandSize + i] = band.data[cycleOffset + i];
        }
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
