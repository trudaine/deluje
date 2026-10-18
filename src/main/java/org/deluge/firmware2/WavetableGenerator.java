package org.deluge.firmware2;

/**
 * Utility to generate band-limited versions of a wavetable cycle. Prevents aliasing by zeroing out
 * harmonics above Nyquist for higher octaves.
 */
public class WavetableGenerator {

  /**
   * Generates bands for a wavetable based on the source cycles. Matches C++ {@code
   * WaveTable::setup} band generation in {@code wave_table.cpp:369-731}: copies band 0 directly in
   * the time domain when the cycle size is a power of two, folds the Nyquist bin magnitude into
   * real for higher bands, and scales all inverse FFT outputs by band 0's cycle size so gain stays
   * constant across bands.
   */
  public static void generateBands(WaveTable waveTable, float[] sourceCycle) {
    int numCycles = waveTable.numCycles;
    int numBands = waveTable.bands.size();
    if (numCycles <= 0 || numBands <= 0 || sourceCycle == null || sourceCycle.length == 0) return;

    int initialBandCycleSize = waveTable.bands.get(0).cycleSizeNoDuplicates;
    if ((initialBandCycleSize & (initialBandCycleSize - 1)) != 0
        || initialBandCycleSize < WaveTable.kWavetableMinCycleSize) {
      throw new IllegalArgumentException("Wavetable band 0 cycle size must be a power of two >= 8");
    }

    // C wave_table.cpp:173-205 — non-power-of-two raw cycle size is allowed for single-cycle
    // wavetables (expanded to next power-of-two in band 0); multi-cycle wavetables use
    // initialBandCycleSize and ignore any trailing samples after numCycles * initialBandCycleSize.
    int rawCycleSize =
        (numCycles == 1 && sourceCycle.length < initialBandCycleSize)
            ? sourceCycle.length
            : initialBandCycleSize;
    if (rawCycleSize < WaveTable.kWavetableMinCycleSize
        || sourceCycle.length < numCycles * rawCycleSize) {
      throw new IllegalArgumentException("Insufficient wavetable samples for cycle size");
    }
    boolean rawCycleSizeIsPowerOfTwo = (rawCycleSize == initialBandCycleSize);

    // Process cycle-by-cycle to support multi-cycle wavetables (C wave_table.cpp:369)
    for (int c = 0; c < numCycles; c++) {
      int cycleSrcOffset = c * rawCycleSize;
      double[] re = new double[initialBandCycleSize];
      double[] im = new double[initialBandCycleSize];

      if (rawCycleSizeIsPowerOfTwo) {
        // C wave_table.cpp:438, 564-576 — band 0 is written directly from the time-domain cycle
        // without an FFT/IFFT round-trip.
        WaveTableBand band0 = waveTable.bands.get(0);
        int band0Offset =
            c * (initialBandCycleSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        for (int i = 0; i < initialBandCycleSize; i++) {
          float sample = sourceCycle[cycleSrcOffset + i];
          re[i] = sample;
          band0.data[band0Offset + i] = toPcm16(sample);
        }
        for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
          band0.data[band0Offset + initialBandCycleSize + i] = band0.data[band0Offset + i];
        }
        if (numBands <= 1) {
          continue;
        }
        // C wave_table.cpp:579 — unscaled forward FFT of size initialBandCycleSize.
        fft(re, im, false);
      } else {
        // C wave_table.cpp:536-550 — non-power-of-two single-cycle wavetable uses dft_r2c for
        // bins 0 .. rawCycleSize/2, with bins above rawCycleSize/2 zeroed up to
        // initialBandCycleSize/2.
        dftR2c(re, im, sourceCycle, cycleSrcOffset, rawCycleSize);
      }

      // C wave_table.cpp:629-730 — render band-limited versions (b >= 1 for power-of-two,
      // b >= 0 for non-power-of-two).
      int startBand = rawCycleSizeIsPowerOfTwo ? 1 : 0;
      for (int b = startBand; b < numBands; b++) {
        WaveTableBand band = waveTable.bands.get(b);
        int bandSize = band.cycleSizeNoDuplicates;
        int nyquist = bandSize >> 1;

        double[] bandRe = new double[bandSize];
        double[] bandIm = new double[bandSize];

        bandRe[0] = re[0];
        bandIm[0] = 0.0;
        for (int i = 1; i < nyquist; i++) {
          bandRe[i] = re[i];
          bandIm[i] = im[i];
          // Hermitian symmetry for real signals
          bandRe[bandSize - i] = re[i];
          bandIm[bandSize - i] = -im[i];
        }

        // C wave_table.cpp:681-687 — fold Nyquist bin magnitude into real component so it is not
        // lost when the imaginary component vanishes at Nyquist.
        double nyqRe = re[nyquist];
        double nyqIm = im[nyquist];
        double pythag = Math.hypot(nyqRe, nyqIm);
        bandRe[nyquist] = (nyqRe < 0.0) ? -pythag : pythag;
        bandIm[nyquist] = 0.0;

        // C wave_table.cpp:712 — unscaled inverse FFT back to time domain.
        fft(bandRe, bandIm, true);

        // C wave_table.cpp:720-729 — every band divides by initialBandCycleSize
        // (1 << initialBandCycleMagnitude), NOT bandSize, keeping amplitude constant across bands.
        int cycleOffset =
            c * (bandSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        double invInitialCycleSize = 1.0 / initialBandCycleSize;
        for (int i = 0; i < bandSize; i++) {
          band.data[cycleOffset + i] = toPcm16(bandRe[i] * invInitialCycleSize);
        }
        for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
          band.data[cycleOffset + bandSize + i] = band.data[cycleOffset + i];
        }
      }
    }
  }

  private static short toPcm16(double sample) {
    // C wave_table.cpp:438, 720-724 — Q15 signed_saturate<16> conversion.
    long scaled = Math.round(sample * 32768.0);
    if (scaled > Short.MAX_VALUE) return Short.MAX_VALUE;
    if (scaled < Short.MIN_VALUE) return Short.MIN_VALUE;
    return (short) scaled;
  }

  // C wave_table.cpp:75-122 — unscaled real-to-complex DFT for non-power-of-two single-cycle data.
  private static void dftR2c(double[] re, double[] im, float[] in, int offset, int n) {
    int halfN = n >> 1;
    double twoPiOverN = -2.0 * Math.PI / n;
    for (int k = 0; k <= halfN; k++) {
      double sReal = 0.0;
      double sIm = 0.0;
      double w = twoPiOverN * k;
      for (int i = 0; i < n; i++) {
        double angle = w * i;
        double sample = in[offset + i];
        sReal += sample * Math.cos(angle);
        sIm += sample * Math.sin(angle);
      }
      re[k] = sReal;
      im[k] = sIm;
    }
  }

  // Unscaled complex Cooley-Tukey FFT/IFFT (C NE10 scaled_flag=false in wave_table.cpp:579, 712).
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
  }
}
