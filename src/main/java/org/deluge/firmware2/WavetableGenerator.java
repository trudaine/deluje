package org.deluge.firmware2;

/**
 * Generates band-limited octave bands for a {@link WaveTable}.
 *
 * <p>Band 0 time-domain copy, {@code CONVERT_AND_STORE_SAMPLE} q31 input scaling, non-power-of-two
 * {@code dft_r2c}, Nyquist magnitude folding, and floor output scaling follow {@code
 * wave_table.cpp:75-122, 353-731}. The power-of-two forward/inverse FFT itself currently uses
 * double precision rather than NE10's fixed-point {@code ne10_fft_r2c_1d_int32_neon} / {@code
 * ne10_fft_c2r_1d_int32_neon}, so bands &ge; 1 are not bit-exact with NE10.
 */
public class WavetableGenerator {

  // C wave_table.cpp:353
  private static final int MAGNITUDE_REDUCTION_FOR_FFT = 12;

  // C wave_table.cpp:70-72
  private static final int NUM_BITS_IN_INPUT = 16;
  private static final int NUM_BITS_IN_TABLE_SIZE = 8;
  private static final int LSHIFT_AMOUNT = 16 + NUM_BITS_IN_TABLE_SIZE - NUM_BITS_IN_INPUT;

  public static void generateBands(WaveTable waveTable, float[] sourceCycle) {
    if (sourceCycle == null) return;
    int[] q31Samples = new int[sourceCycle.length];
    for (int i = 0; i < sourceCycle.length; i++) {
      q31Samples[i] = WaveTableReader.q31FromFloat(sourceCycle[i]);
    }
    generateBands(waveTable, q31Samples);
  }

  /**
   * Generates bands for a wavetable from left-aligned q31 {@code value32} samples ({@code
   * wave_table.cpp:369-731}).
   */
  public static void generateBands(WaveTable waveTable, int[] q31Samples) {
    int numCycles = waveTable.numCycles;
    int numBands = waveTable.bands.size();
    if (numCycles <= 0 || numBands <= 0 || q31Samples == null || q31Samples.length == 0) return;

    int initialBandCycleSize = waveTable.bands.get(0).cycleSizeNoDuplicates;
    if ((initialBandCycleSize & (initialBandCycleSize - 1)) != 0
        || initialBandCycleSize < WaveTable.kWavetableMinCycleSize) {
      throw new IllegalArgumentException("Wavetable band 0 cycle size must be a power of two >= 8");
    }

    // C wave_table.cpp:173-205 — non-power-of-two raw cycle size is allowed for single-cycle
    // wavetables (expanded to next power-of-two in band 0); multi-cycle wavetables use
    // initialBandCycleSize and ignore any trailing samples after numCycles * initialBandCycleSize.
    int rawCycleSize =
        (numCycles == 1 && q31Samples.length < initialBandCycleSize)
            ? q31Samples.length
            : initialBandCycleSize;
    if (rawCycleSize < WaveTable.kWavetableMinCycleSize
        || q31Samples.length < numCycles * rawCycleSize) {
      throw new IllegalArgumentException("Insufficient wavetable samples for cycle size");
    }
    boolean rawCycleSizeIsPowerOfTwo = (rawCycleSize == initialBandCycleSize);

    // Process cycle-by-cycle to support multi-cycle wavetables (C wave_table.cpp:369)
    for (int c = 0; c < numCycles; c++) {
      int cycleSrcOffset = c * rawCycleSize;
      double[] re = new double[initialBandCycleSize];
      double[] im = new double[initialBandCycleSize];

      if (rawCycleSizeIsPowerOfTwo) {
        // C wave_table.cpp:436-438, 564-576 — band 0 is written directly as (value32 >> 16)
        // without an FFT/IFFT round-trip, and FFT input uses (value32 >> 12).
        WaveTableBand band0 = waveTable.bands.get(0);
        int band0Offset =
            c * (initialBandCycleSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        for (int i = 0; i < initialBandCycleSize; i++) {
          int value32 = q31Samples[cycleSrcOffset + i];
          band0.data[band0Offset + i] = (short) (value32 >> 16);
          re[i] = value32 >> MAGNITUDE_REDUCTION_FOR_FFT;
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
        // C wave_table.cpp:536-550 — non-power-of-two single-cycle wavetable uses fixed-point
        // dft_r2c for bins 0 .. rawCycleSize/2, with bins above rawCycleSize/2 zeroed.
        dftR2cFixedPoint(re, im, q31Samples, cycleSrcOffset, rawCycleSize);
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

        // C wave_table.cpp:720-724 — signed_saturate<16>(x >> (16 - 12 +
        // initialBandCycleMagnitude)),
        // which floors (x / (16 * initialBandCycleSize)) across every band.
        int cycleOffset =
            c * (bandSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        double floorScale = 16.0 * initialBandCycleSize;
        for (int i = 0; i < bandSize; i++) {
          band.data[cycleOffset + i] = toFloorPcm16(bandRe[i] / floorScale);
        }
        for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
          band.data[cycleOffset + bandSize + i] = band.data[cycleOffset + i];
        }
      }
    }
  }

  private static short toFloorPcm16(double scaled) {
    // C wave_table.cpp:720-724 — arithmetic right shift floors toward -infinity, then saturates.
    long floored = (long) Math.floor(scaled);
    if (floored > Short.MAX_VALUE) return Short.MAX_VALUE;
    if (floored < Short.MIN_VALUE) return Short.MIN_VALUE;
    return (short) floored;
  }

  // C wave_table.cpp:75-122 — fixed-point dft_r2c using sineWaveSmall table interpolation.
  private static void dftR2cFixedPoint(
      double[] re, double[] im, int[] q31Samples, int offset, int n) {
    int halfN = n >>> 1;
    int tableMask = (1 << NUM_BITS_IN_TABLE_SIZE) - 1;
    int cosQuadOffset = 1 << (NUM_BITS_IN_TABLE_SIZE - 2);
    int shiftToTable = NUM_BITS_IN_INPUT - NUM_BITS_IN_TABLE_SIZE;

    for (int i = 0; i <= halfN; i++) {
      int sReal = 0;
      int sIm = 0;
      // C :84 — uint32_t angleIncrement = -((i << numBitsInInput) / N);
      int angleIncrement = -Integer.divideUnsigned(i << NUM_BITS_IN_INPUT, n);
      int angle = 0;

      for (int pos = 0; pos < n; pos++) {
        int whichValueSine = (angle >>> shiftToTable) & tableMask;
        int sineValue1 = LookupTables.sineWaveSmall[whichValueSine];
        int sineValue2 = LookupTables.sineWaveSmall[whichValueSine + 1];

        int whichValueCos = (whichValueSine + cosQuadOffset) & tableMask;
        int cosValue1 = LookupTables.sineWaveSmall[whichValueCos];
        int cosValue2 = LookupTables.sineWaveSmall[whichValueCos + 1];

        int inputValueReal = q31Samples[offset + pos] >> MAGNITUDE_REDUCTION_FOR_FFT;

        int shifted = angle << LSHIFT_AMOUNT;
        int strength2 = shifted & 65535;

        int sineValue = (sineValue1 << 16) + (sineValue2 - sineValue1) * strength2;
        int cosValue = (cosValue1 << 16) + (cosValue2 - cosValue1) * strength2;

        // C :112-114 — multiply_accumulate_32x32_rshift32_rounded (fixedpoint.h:180-182).
        sReal = (int) (sReal + ((((long) inputValueReal * cosValue) + 0x80000000L) >> 32));
        sIm = (int) (sIm + ((((long) inputValueReal * sineValue) + 0x80000000L) >> 32));

        angle += angleIncrement;
      }

      // C :120-121 — restore 1 bit lost in Q31 multiply-accumulate.
      re[i] = sReal << 1;
      im[i] = sIm << 1;
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
