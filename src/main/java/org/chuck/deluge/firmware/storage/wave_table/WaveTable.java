package org.chuck.deluge.firmware.storage.wave_table;

import static org.chuck.deluge.firmware.util.Q31.*;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of the Deluge's WaveTable class.
 * Implements high-fidelity wavetable synthesis with windowed-sinc interpolation.
 */
public class WaveTable {
  public static final int kInterpolationMaxNumSamples = 16;
  public static final int kInterpolationMaxNumSamplesMagnitude = 4;
  public static final int WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE = 7;
  public static final int kWavetableMinCycleSize = 8;

  public int numCycles;
  public int numCyclesMagnitude;
  public int numCycleTransitionsNextPowerOf2;
  public int numCycleTransitionsNextPowerOf2Magnitude;
  public int waveIndexMultiplier;
  public List<WaveTableBand> bands = new ArrayList<>();

  public void setup(int rawFileCycleSize, int totalSamples) {
    if (rawFileCycleSize < kWavetableMinCycleSize) return;

    int initialBandCycleMagnitude = FirmwareUtils.getMagnitude(rawFileCycleSize);
    boolean rawFileCycleSizeIsAPowerOfTwo = (rawFileCycleSize == (1 << initialBandCycleMagnitude));

    // ── Bit-Accurate Cycle Size Logic ──
    if (!rawFileCycleSizeIsAPowerOfTwo) {
        // Allowed if single-cycle
        if (totalSamples < (rawFileCycleSize << 1) && totalSamples >= rawFileCycleSize) {
            numCycles = 1;
            initialBandCycleMagnitude++; // Render out to bigger power-of-two size
        } else {
            // Multiple cycles (wavetable) with non-power-of-2 size is not allowed in hardware
            initialBandCycleMagnitude++; // robust fallback
        }
    }

    int initialBandCycleSizeNoDuplicates = 1 << initialBandCycleMagnitude;
    numCycles = totalSamples / rawFileCycleSize; // Hardware uses rawFileCycleSize for division
    numCyclesMagnitude = FirmwareUtils.getMagnitude(numCycles);

    int numBands = initialBandCycleMagnitude - 2;

    for (int b = 0; b < numBands; b++) {
      WaveTableBand band = new WaveTableBand();
      int cycleSizeNoDuplicates = initialBandCycleSizeNoDuplicates >> b;
      band.cycleSizeNoDuplicates = cycleSizeNoDuplicates;
      band.cycleSizeMagnitude = (byte) (initialBandCycleMagnitude - b);
      // 1 increment covers whole cycle: 2^32 / 2^magnitude
      band.maxPhaseIncrement = (int) (0xFFFFFFFFL >> (32 - band.cycleSizeMagnitude));

      int bandSizeSamplesWithDuplicates =
          numCycles * (cycleSizeNoDuplicates + WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
      band.data = new short[bandSizeSamplesWithDuplicates];
      band.fromCycleNumber = 0;
      band.toCycleNumber = numCycles;
      bands.add(band);
    }

    if (numCycles > 1) {
      int numCycleTransitions = numCycles - 1;
      numCycleTransitionsNextPowerOf2Magnitude = FirmwareUtils.getMagnitude(numCycleTransitions);
      numCycleTransitionsNextPowerOf2 = 1 << numCycleTransitionsNextPowerOf2Magnitude;
      waveIndexMultiplier =
          (int) (((long) numCycleTransitions << 31) >> numCycleTransitionsNextPowerOf2Magnitude);
    }
  }

  public int render(
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase,
      int waveIndex,
      int waveIndexIncrement) {

    // Decide on ideal band
    WaveTableBand bandHere = null;
    for (WaveTableBand band : bands) {
      if (phaseIncrement <= band.maxPhaseIncrement) {
        bandHere = band;
        break;
      }
    }
    if (bandHere == null && !bands.isEmpty()) {
      bandHere = bands.get(bands.size() - 1);
    }

    if (bandHere == null) return phase;

    if (numCycles > 1) {
      int waveIndexScaled = Q31.multiply_32x32_rshift32_rounded(waveIndexMultiplier, waveIndex);
      int waveIndexIncrementScaled =
          Q31.multiply_32x32_rshift32_rounded(waveIndexMultiplier, waveIndexIncrement);

      int lshiftAmountToGetCrossCycleStrength = 32 + numCycleTransitionsNextPowerOf2Magnitude - 31;
      int crossCycleStrength2Increment =
          waveIndexIncrementScaled << lshiftAmountToGetCrossCycleStrength;

      int numSamplesLeftToDo = numSamples;
      int currentOffset = offset;
      int currentPhase = phase;
      int currentWaveIndexScaled = waveIndexScaled;

      while (numSamplesLeftToDo > 0) {
        int firstCycleNumber =
            currentWaveIndexScaled >> (31 - numCycleTransitionsNextPowerOf2Magnitude);

        int numSamplesThisLoop = numSamplesLeftToDo;

        int firstCycleNumberAfterAllIncrements =
            (currentWaveIndexScaled + waveIndexIncrementScaled * (numSamplesLeftToDo - 1))
                >> (31 - numCycleTransitionsNextPowerOf2Magnitude);

        if (firstCycleNumber != firstCycleNumberAfterAllIncrements) {
          int cycleSizeInWaveIndex = 1 << (31 - numCycleTransitionsNextPowerOf2Magnitude);
          int waveIndexPlaceWithinCycle = currentWaveIndexScaled & (cycleSizeInWaveIndex - 1);
          int waveIndexDistanceUntilFinalBigValueStillInThisCycle =
              (waveIndexIncrementScaled >= 0)
                  ? (cycleSizeInWaveIndex - 1 - waveIndexPlaceWithinCycle)
                  : (waveIndexPlaceWithinCycle);
          int waveIndexIncrementScaledAbs = Math.abs(waveIndexIncrementScaled);
          if (waveIndexIncrementScaledAbs == 0) waveIndexIncrementScaledAbs = 1; // safety
          long numIncrementsWeCanDoNow =
              Integer.toUnsignedLong(waveIndexDistanceUntilFinalBigValueStillInThisCycle)
                  / Integer.toUnsignedLong(waveIndexIncrementScaledAbs);
          numSamplesThisLoop = (int) numIncrementsWeCanDoNow + 1;
        }

        int crossCycleStrength2 = currentWaveIndexScaled << lshiftAmountToGetCrossCycleStrength;

        // Adjust band if we cross boundaries where it lacks data
        while (bandHere.fromCycleNumber > firstCycleNumber
            || bandHere.toCycleNumber <= firstCycleNumber + 1) {
          int idx = bands.indexOf(bandHere);
          if (idx < bands.size() - 1) {
            bandHere = bands.get(idx + 1);
          } else {
            break;
          }
        }

        int whichKernel = getKernelIndex(phaseIncrement, bandHere.maxPhaseIncrement);
        short[][] kernel = LookupTables.windowedSincKernel[whichKernel];

        doRenderingLoop(
            outputBuffer,
            currentOffset,
            numSamplesThisLoop,
            firstCycleNumber,
            bandHere,
            currentPhase,
            phaseIncrement,
            crossCycleStrength2,
            crossCycleStrength2Increment,
            kernel);

        currentPhase += phaseIncrement * numSamplesThisLoop;
        currentWaveIndexScaled += waveIndexIncrementScaled * numSamplesThisLoop;
        numSamplesLeftToDo -= numSamplesThisLoop;
        currentOffset += numSamplesThisLoop;
      }
      return currentPhase;
    } else {
      // Single cycle rendering
      short[][][] kernels = LookupTables.windowedSincKernel;
      int whichKernel = getKernelIndex(phaseIncrement, bandHere.maxPhaseIncrement);
      short[][] kernel = kernels[whichKernel];

      doRenderingLoopSingleCycle(
          outputBuffer, offset, numSamples, bandHere, phase, phaseIncrement, kernel);
      return phase + phaseIncrement * numSamples;
    }
  }

  private void doRenderingLoopSingleCycle(
      int[] outputBuffer,
      int offset,
      int numSamples,
      WaveTableBand band,
      int phase,
      int phaseIncrement,
      short[][] kernel) {
    int bandCycleSizeMagnitude = band.cycleSizeMagnitude;
    short[] bandData = band.data;
    int currentPhase = phase;

    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;

      // Get windowed sinc kernel
      int rshiftAmount = ((32 + kInterpolationMaxNumSamplesMagnitude) - 16 - 8 + 1);
      int rshifted = ((-currentPhase) >>> (rshiftAmount - bandCycleSizeMagnitude));
      int strength2 = rshifted & 32767;

      int progressSmall =
          ((-currentPhase)
              >>> (32 + kInterpolationMaxNumSamplesMagnitude - 8 - 5 - bandCycleSizeMagnitude));
      progressSmall &= 0xF;

      short[] k1 = kernel[progressSmall];
      short[] k2 = kernel[progressSmall + 1];

      int[] interpolatedKernel = new int[16];
      for (int k = 0; k < 16; k++) {
        int diff = k2[k] - k1[k];
        interpolatedKernel[k] = (k1[k] << 16) + diff * strength2 * 2;
      }

      int whichValueCentral = (currentPhase >>> (32 - bandCycleSizeMagnitude));
      int startValue = whichValueCentral - 8;

      long sum = 0;
      for (int k = 0; k < 16; k++) {
        int idx = (startValue + k) & ((1 << bandCycleSizeMagnitude) - 1);
        sum += (long) bandData[idx] * interpolatedKernel[k];
      }
      outputBuffer[offset + i] = (int) (sum >> 16);
    }
  }

  private int getKernelIndex(int phaseIncrement, int bandMaxPhaseIncrement) {
    int whichKernel = 0;
    int phaseIncrementHere = phaseIncrement;
    while (phaseIncrementHere >= bandMaxPhaseIncrement && whichKernel < 6) {
      whichKernel += 2;
      phaseIncrementHere >>>= 1;
    }
    if (whichKernel < 6 && phaseIncrementHere >= (bandMaxPhaseIncrement * 0.707)) {
      whichKernel++;
    }
    return whichKernel;
  }

  private void doRenderingLoop(
      int[] outputBuffer,
      int offset,
      int numSamples,
      int firstCycleNumber,
      WaveTableBand band,
      int phase,
      int phaseIncrement,
      int crossCycleStrength2,
      int crossCycleStrength2Increment,
      short[][] kernel) {

    int bandCycleSizeMagnitude = band.cycleSizeMagnitude;
    int bandCycleSizeWithDuplicates =
        band.cycleSizeNoDuplicates + WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE;
    short[] bandData = band.data;

    int table1Offset = firstCycleNumber * bandCycleSizeWithDuplicates;
    int table2Offset = table1Offset + bandCycleSizeWithDuplicates;

    int currentPhase = phase;
    int currentCrossCycleStrength2 = crossCycleStrength2;

    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;

      // Get windowed sinc kernel
      int rshiftAmount = ((32 + kInterpolationMaxNumSamplesMagnitude) - 16 - 8 + 1);
      int rshifted = ((-currentPhase) >>> (rshiftAmount - bandCycleSizeMagnitude));
      int strength2 = rshifted & 32767;

      int progressSmall =
          ((-currentPhase)
              >>> (32 + kInterpolationMaxNumSamplesMagnitude - 8 - 5 - bandCycleSizeMagnitude));
      progressSmall &= 0xF; // 16 lines in kernel table

      short[] k1 = kernel[progressSmall];
      short[] k2 = kernel[progressSmall + 1];

      int[] interpolatedKernel = new int[16];
      for (int k = 0; k < 16; k++) {
        int diff = k2[k] - k1[k];
        interpolatedKernel[k] = (k1[k] << 16) + diff * strength2 * 2;
      }

      int[] vals = new int[2];
      for (int c = 0; c < 2; c++) {
        int tableOffset = (c == 0) ? table1Offset : table2Offset;
        int whichValueCentral = (currentPhase >>> (32 - bandCycleSizeMagnitude));
        int startValue = whichValueCentral - 8;

        long sum = 0;
        for (int k = 0; k < 16; k++) {
          int idx = (startValue + k) & ((1 << bandCycleSizeMagnitude) - 1);
          sum += (long) bandData[tableOffset + idx] * interpolatedKernel[k];
        }
        vals[c] = (int) (sum >> 16);
      }

      int diff = vals[1] - vals[0];
      outputBuffer[offset + i] =
          Q31.multiply_accumulate_32x32_rshift32_rounded(
              vals[0] >> 1, diff, currentCrossCycleStrength2 >> 1);

      currentCrossCycleStrength2 += crossCycleStrength2Increment;
    }
  }
}
