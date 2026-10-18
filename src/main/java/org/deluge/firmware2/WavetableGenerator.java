package org.deluge.firmware2;

/**
 * Generates band-limited octave bands for a {@link WaveTable}.
 *
 * <p>Band 0 time-domain copy, {@code CONVERT_AND_STORE_SAMPLE} q31 input scaling, non-power-of-two
 * {@code dft_r2c}, NE10 fixed-point {@code int32} forward/inverse FFT ({@code scaled_flag =
 * false}), Nyquist magnitude folding, and arithmetic-shift output scaling follow {@code
 * wave_table.cpp:75-122, 353-731}.
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
    int initialBandCycleMagnitude = Integer.numberOfTrailingZeros(initialBandCycleSize);

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

    // C wave_table.cpp:401-416 — FFT config and working buffers for initial band.
    Ne10FftInt32.Config fftCfgForInitialBand = Ne10FftInt32.getConfig(initialBandCycleMagnitude);
    if (fftCfgForInitialBand == null && (numBands > 1 || !rawCycleSizeIsPowerOfTwo)) {
      throw new IllegalArgumentException(
          "Unsupported wavetable band 0 FFT magnitude: " + initialBandCycleMagnitude);
    }
    int[] currentCycleInt32 = new int[initialBandCycleSize];
    int[] frequencyDomainData = new int[initialBandCycleSize + 2];

    // Process cycle-by-cycle to support multi-cycle wavetables (C wave_table.cpp:432)
    for (int c = 0; c < numCycles; c++) {
      int cycleSrcOffset = c * rawCycleSize;

      if (rawCycleSizeIsPowerOfTwo) {
        // C wave_table.cpp:436-438, 564-576 — band 0 is written directly as (value32 >> 16)
        // without an FFT/IFFT round-trip, and FFT input uses (value32 >> 12).
        WaveTableBand band0 = waveTable.bands.get(0);
        int band0Offset =
            c * (initialBandCycleSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        for (int i = 0; i < initialBandCycleSize; i++) {
          int value32 = q31Samples[cycleSrcOffset + i];
          band0.data[band0Offset + i] = (short) (value32 >> 16);
          currentCycleInt32[i] = value32 >> MAGNITUDE_REDUCTION_FOR_FFT;
        }
        for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
          band0.data[band0Offset + initialBandCycleSize + i] = band0.data[band0Offset + i];
        }
        if (numBands <= 1) {
          continue;
        }
        // C wave_table.cpp:579 — unscaled NE10 int32 forward r2c FFT of size initialBandCycleSize.
        Ne10FftInt32.r2c1d(frequencyDomainData, currentCycleInt32, fftCfgForInitialBand, false);
      } else {
        // C wave_table.cpp:536-550 — non-power-of-two single-cycle wavetable uses fixed-point
        // dft_r2c for bins 0 .. rawCycleSize/2, with bins above rawCycleSize/2 zeroed.
        dftR2cFixedPoint(frequencyDomainData, q31Samples, cycleSrcOffset, rawCycleSize);
        for (int i = (rawCycleSize >> 1) + 1; i <= (initialBandCycleSize >> 1); i++) {
          frequencyDomainData[2 * i] = 0;
          frequencyDomainData[2 * i + 1] = 0;
        }
      }

      // C wave_table.cpp:629-730 — render band-limited versions (b >= 1 for power-of-two,
      // b >= 0 for non-power-of-two).
      int startBand = rawCycleSizeIsPowerOfTwo ? 1 : 0;
      int outputShift = 16 - MAGNITUDE_REDUCTION_FOR_FFT + initialBandCycleMagnitude;
      for (int b = startBand; b < numBands; b++) {
        WaveTableBand band = waveTable.bands.get(b);
        int bandSize = band.cycleSizeNoDuplicates;
        int nyquist = bandSize >> 1;

        if (b >= 1) {
          // C wave_table.cpp:681-687 — fold Nyquist bin magnitude into real component so it is not
          // lost when the imaginary component vanishes at Nyquist.
          // Uses Math.hypot rather than C's fastPythag, whose `divisor = x << 8` at
          // functions.cpp:1506 overflows int32 for x >= 2^23.
          int nyqIdx = 2 * nyquist;
          int nyqRe = frequencyDomainData[nyqIdx];
          int nyqIm = frequencyDomainData[nyqIdx + 1];
          int pythag = (int) Math.min(Integer.MAX_VALUE, Math.round(Math.hypot(nyqRe, nyqIm)));
          frequencyDomainData[nyqIdx] = (nyqRe < 0) ? -pythag : pythag;
          frequencyDomainData[nyqIdx + 1] = 0;
        }

        // C wave_table.cpp:691-712 — unscaled NE10 int32 inverse c2r FFT back to time domain.
        int bandMagnitude = Integer.numberOfTrailingZeros(bandSize);
        Ne10FftInt32.Config fftCfgThisBand = Ne10FftInt32.getConfig(bandMagnitude);
        if (fftCfgThisBand == null) {
          continue;
        }
        Ne10FftInt32.c2r1d(currentCycleInt32, frequencyDomainData, fftCfgThisBand, false);

        // C wave_table.cpp:720-724 — signed_saturate<16>(currentCycleInt32[i] >> outputShift).
        int cycleOffset =
            c * (bandSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
        for (int i = 0; i < bandSize; i++) {
          band.data[cycleOffset + i] = saturate16(currentCycleInt32[i] >> outputShift);
        }
        for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
          band.data[cycleOffset + bandSize + i] = band.data[cycleOffset + i];
        }
      }
    }
  }

  private static short saturate16(int val) {
    // C fixedpoint.h:108-112, 198-202 — signed_saturate<16>.
    if (val > Short.MAX_VALUE) return Short.MAX_VALUE;
    if (val < Short.MIN_VALUE) return Short.MIN_VALUE;
    return (short) val;
  }

  // C wave_table.cpp:75-122 — fixed-point dft_r2c using sineWaveSmall table interpolation.
  private static void dftR2cFixedPoint(
      int[] frequencyDomainData, int[] q31Samples, int offset, int n) {
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
      frequencyDomainData[2 * i] = sReal << 1;
      frequencyDomainData[2 * i + 1] = sIm << 1;
    }
  }
}
