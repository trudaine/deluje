package org.deluge.firmware2;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of the Deluge's WaveTable class. Implements high-fidelity wavetable synthesis with
 * windowed-sinc interpolation.
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

  private static class WaveTableScratch {
    final short[] kernelVector = new short[16]; // int16 vqdmulh taps (C wave_table.cpp:979-986)
    final int[] vals = new int[2];
  }

  private static final ThreadLocal<WaveTableScratch> scratchHolder =
      ThreadLocal.withInitial(WaveTableScratch::new);

  public void setup(int rawFileCycleSize, int totalSamples) {
    if (rawFileCycleSize < kWavetableMinCycleSize) return;

    int initialBandCycleMagnitude = getMagnitude(rawFileCycleSize);
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
    numCyclesMagnitude = getMagnitude(numCycles);

    int numBands = initialBandCycleMagnitude - 2;

    for (int b = 0; b < numBands; b++) {
      WaveTableBand band = new WaveTableBand();
      int cycleSizeNoDuplicates = initialBandCycleSizeNoDuplicates >> b;
      band.cycleSizeNoDuplicates = cycleSizeNoDuplicates;
      band.cycleSizeMagnitude = (byte) (initialBandCycleMagnitude - b);
      // C wave_table.cpp:288 — (uint32_t)(0xFFFFFFFF >> cycleSizeMagnitude) * 1.25
      band.maxPhaseIncrement = (int) ((0xFFFFFFFFL >>> band.cycleSizeMagnitude) * 1.25);

      int bandSizeSamplesWithDuplicates =
          numCycles * (cycleSizeNoDuplicates + WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
      band.data = new short[bandSizeSamplesWithDuplicates];
      band.fromCycleNumber = 0;
      band.toCycleNumber = numCycles;
      bands.add(band);
    }

    if (numCycles > 1) {
      int numCycleTransitions = numCycles - 1;
      // C wave_table.cpp:749 uses getMagnitudeOld = 32 - clz (NOT the 31 - clz getMagnitude).
      numCycleTransitionsNextPowerOf2Magnitude =
          32 - Integer.numberOfLeadingZeros(numCycleTransitions);
      numCycleTransitionsNextPowerOf2 = 1 << numCycleTransitionsNextPowerOf2Magnitude;
      // C wave_table.cpp:752
      waveIndexMultiplier = numCycleTransitions << (31 - numCycleTransitionsNextPowerOf2Magnitude);
    }
  }

  private int getMagnitude(int val) {
    int magnitude = 0;
    int temp = val;
    while (temp > 1) {
      temp >>>= 1;
      magnitude++;
    }
    return magnitude;
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

    // Decide on ideal band — C wave_table.cpp:1061: bands.search(phaseIncrement, GREATER_OR_EQUAL)
    // on the uint32 maxPhaseIncrement key.
    WaveTableBand bandHere = null;
    for (WaveTableBand band : bands) {
      if (Integer.compareUnsigned(phaseIncrement, band.maxPhaseIncrement) <= 0) {
        bandHere = band;
        break;
      }
    }
    if (bandHere == null && !bands.isEmpty()) {
      bandHere = bands.get(bands.size() - 1);
    }

    if (bandHere == null) return phase;

    if (numCycles > 1) {
      // C wave_table.cpp:1071-1072 — SIGNED rounded multiplies.
      int waveIndexScaled =
          Functions.multiply_32x32_rshift32_rounded(waveIndexMultiplier, waveIndex);
      int waveIndexIncrementScaled =
          Functions.multiply_32x32_rshift32_rounded(waveIndexMultiplier, waveIndexIncrement);

      // C wave_table.cpp:910,1074-1075 — NUM_BITS_IN_WAVE_INDEX_SCALED_INPUT = 30.
      int lshiftAmountToGetCrossCycleStrength = 32 + numCycleTransitionsNextPowerOf2Magnitude - 30;
      int crossCycleStrength2Increment =
          waveIndexIncrementScaled << lshiftAmountToGetCrossCycleStrength;

      int numSamplesLeftToDo = numSamples;
      int currentOffset = offset;
      int currentPhase = phase;
      int currentWaveIndexScaled = waveIndexScaled;
      // C wave_table.cpp:1055/1156 — the resetter advances across cycle chunks.
      int resetterPhaseThisCycle = resetterPhase;

      while (numSamplesLeftToDo > 0) {
        // C wave_table.cpp:1081-1090 — shifts use NUM_BITS_IN_WAVE_INDEX_SCALED_INPUT = 30.
        int firstCycleNumber =
            currentWaveIndexScaled >> (30 - numCycleTransitionsNextPowerOf2Magnitude);

        int numSamplesThisLoop = numSamplesLeftToDo;

        int firstCycleNumberAfterAllIncrements =
            (currentWaveIndexScaled + waveIndexIncrementScaled * (numSamplesLeftToDo - 1))
                >> (30 - numCycleTransitionsNextPowerOf2Magnitude);

        if (firstCycleNumber != firstCycleNumberAfterAllIncrements) {
          int cycleSizeInWaveIndex = 1 << (30 - numCycleTransitionsNextPowerOf2Magnitude);
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

        // Adjust band if we cross boundaries where it lacks data (C wave_table.cpp:1113-1121).
        boolean validBandFound = true;
        while (bandHere.fromCycleNumber > firstCycleNumber
            || bandHere.toCycleNumber <= firstCycleNumber + 1) {
          int idx = bands.indexOf(bandHere);
          if (idx < bands.size() - 1) {
            bandHere = bands.get(idx + 1);
          } else {
            validBandFound = false;
            break;
          }
        }

        if (validBandFound) {
          int whichKernel = getKernelIndex(phaseIncrement, bandHere.maxPhaseIncrement);
          short[][] kernel = SincKernel.windowedSincKernel[whichKernel];

          if (doOscSync) {
            // C wave_table.cpp:1128-1145 — the cycle chunk renders through renderOscSync with
            // doRenderingLoop as the segment renderer; crossCycleStrength2 advances between
            // sync sessions via the redo callback.
            final WaveTableBand bandF = bandHere;
            final int firstCycleF = firstCycleNumber;
            final short[][] kernelF = kernel;
            final int ccsInc = crossCycleStrength2Increment;
            final int[] ccs = {crossCycleStrength2};
            currentPhase =
                renderOscSyncSessions(
                    outputBuffer,
                    currentOffset,
                    numSamplesThisLoop,
                    phaseIncrement,
                    currentPhase,
                    resetterPhaseThisCycle,
                    resetterPhaseIncrement,
                    resetterDivideByPhaseIncrement,
                    retriggerPhase,
                    (from, count, ph) ->
                        doRenderingLoop(
                            outputBuffer,
                            from,
                            count,
                            firstCycleF,
                            bandF,
                            ph,
                            phaseIncrement,
                            ccs[0],
                            ccsInc,
                            kernelF),
                    samplesIncl -> ccs[0] += ccsInc * (samplesIncl - 1));
          } else {
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
          }
        } else {
          // C wave_table.cpp:1117 — goto doneRenderingACycle skips rendering when no band covers
          // cycle transition
          currentPhase += phaseIncrement * numSamplesThisLoop;
        }

        // C wave_table.cpp:1152-1157 — advance the outer positions per chunk.
        currentWaveIndexScaled += waveIndexIncrementScaled * numSamplesThisLoop;
        resetterPhaseThisCycle += resetterPhaseIncrement * numSamplesThisLoop;
        numSamplesLeftToDo -= numSamplesThisLoop;
        currentOffset += numSamplesThisLoop;
      }
      return currentPhase;
    } else {
      // Single cycle rendering (C wave_table.cpp:1160-1180)
      short[][][] kernels = SincKernel.windowedSincKernel;
      int whichKernel = getKernelIndex(phaseIncrement, bandHere.maxPhaseIncrement);
      short[][] kernel = kernels[whichKernel];

      if (doOscSync) {
        final WaveTableBand bandF = bandHere;
        final short[][] kernelF = kernel;
        return renderOscSyncSessions(
            outputBuffer,
            offset,
            numSamples,
            phaseIncrement,
            phase,
            resetterPhase,
            resetterPhaseIncrement,
            resetterDivideByPhaseIncrement,
            retriggerPhase,
            (from, count, ph) ->
                doRenderingLoopSingleCycle(
                    outputBuffer, from, count, bandF, ph, phaseIncrement, kernelF),
            samplesIncl -> {});
      }
      doRenderingLoopSingleCycle(
          outputBuffer, offset, numSamples, bandHere, phase, phaseIncrement, kernel);
      return phase + phaseIncrement * numSamples;
    }
  }

  /** Raw segment renderer for {@link #renderOscSyncSessions}: OVERWRITES the output range. */
  private interface SyncSegmentRenderer {
    void render(int fromOffset, int count, int phase);
  }

  /**
   * C render_wave.h:25-90 renderOscSync — the generic hard-sync session loop: renders raw segments
   * between sync resets, half-sine-blends each crossover sample, re-derives the synced phase from
   * the resetter at every reset, and calls {@code crossoverRedoExtra} with
   * samplesIncludingNextCrossoverSample at each reset (the wavetable advances its
   * cross-cycle-strength there, wave_table.cpp:1139-1141). Returns the updated phase.
   */
  private int renderOscSyncSessions(
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase,
      SyncSegmentRenderer segment,
      java.util.function.IntConsumer crossoverRedoExtra) {
    int bufPos = 0;
    boolean renderedASyncFromItsStartYet = false;
    int crossoverSampleBeforeSync = 0;
    int fadeBetweenSyncs = 0;
    long numSamplesThisOscSyncSession = numSamples;
    long samplesIncludingNextCrossoverSample = 1;

    while (true) {
      // C render_wave.h:41-46 (all uint32 arithmetic)
      long distanceTilNextCrossoverSample =
          (-(resetterPhase & 0xFFFFFFFFL) - ((resetterPhaseIncrement & 0xFFFFFFFFL) >>> 1))
              & 0xFFFFFFFFL;
      samplesIncludingNextCrossoverSample +=
          ((distanceTilNextCrossoverSample - 1) & 0xFFFFFFFFL)
              / (resetterPhaseIncrement & 0xFFFFFFFFL);
      boolean shouldBeginNextSyncAfter =
          numSamplesThisOscSyncSession >= samplesIncludingNextCrossoverSample;
      int numSamplesThisSyncRender =
          (int)
              (shouldBeginNextSyncAfter
                  ? samplesIncludingNextCrossoverSample
                  : numSamplesThisOscSyncSession);

      segment.render(offset + bufPos, numSamplesThisSyncRender, phase);

      // C render_wave.h:55-61 — half-sine crossfade of the crossover sample at this window's
      // start.
      if (renderedASyncFromItsStartYet) {
        int v = outputBuffer[offset + bufPos];
        int average = (v >> 1) + (crossoverSampleBeforeSync >> 1);
        int halfDifference = (v >> 1) - (crossoverSampleBeforeSync >> 1);
        int sineValue = Functions.getSine(fadeBetweenSyncs >> 1, 32);
        outputBuffer[offset + bufPos] =
            average + (Functions.multiply_32x32_rshift32(halfDifference, sineValue) << 1);
      }

      if (shouldBeginNextSyncAfter) {
        // C render_wave.h:63-84
        bufPos += (int) samplesIncludingNextCrossoverSample - 1;
        crossoverSampleBeforeSync = outputBuffer[offset + bufPos];
        numSamplesThisOscSyncSession -= samplesIncludingNextCrossoverSample - 1;
        crossoverRedoExtra.accept((int) samplesIncludingNextCrossoverSample);
        resetterPhase +=
            resetterPhaseIncrement
                * (int)
                    (samplesIncludingNextCrossoverSample - (renderedASyncFromItsStartYet ? 1 : 0));
        fadeBetweenSyncs =
            Functions.multiply_32x32_rshift32(resetterPhase, resetterDivideByPhaseIncrement) << 17;
        phase =
            Functions.multiply_32x32_rshift32(fadeBetweenSyncs, phaseIncrement) + retriggerPhase;
        phase -= phaseIncrement; // we're going back and redoing the last sample
        renderedASyncFromItsStartYet = true;
        samplesIncludingNextCrossoverSample = 2;
        continue;
      }

      phase += phaseIncrement * numSamplesThisSyncRender;
      break;
    }
    return phase;
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

      // C wave_table.cpp:854-861 forms windowedSincTableLineOffsetBytes = (-phase)>>(23-mag), masks
      // it to a multiple of 32 (0b111100000), then adds it to an int16* as BYTES — i.e. the C -5 is
      // a
      // byte-vs-line (32 bytes = 16 int16 per kernel row) artifact of pointer arithmetic. Java
      // indexes
      // the kernel by ROW (kernel[progressSmall]), so that ÷32 is already implicit: the faithful
      // shift
      // is (28-mag) with & 0xF, NOT (23-mag). Carrying the -5 selected a row 5 bits too low —
      // landing
      // inside strength2's own bit field — so nearly every sample used the wrong sinc kernel row.
      // Verified: (-phase)>>(28-mag)&0xF reproduces C's byteOff/32 row exactly for all phases.
      int progressSmall =
          ((-currentPhase)
              >>> (32 + kInterpolationMaxNumSamplesMagnitude - 8 - bandCycleSizeMagnitude));
      progressSmall &= 0xF;

      short[] k1 = kernel[progressSmall];
      short[] k2 = kernel[progressSmall + 1];

      int whichValueCentral = (currentPhase >>> (32 - bandCycleSizeMagnitude));
      int startValue = whichValueCentral - 8;
      int mask = (1 << bandCycleSizeMagnitude) - 1;

      // C wave_table.cpp:875-905: kernelVector = vaddq_s16(value1, vqdmulhq_n_s16(value2-value1,
      // strength2)) — every step is an int16-lane op (vsub/vqdmulh/vadd wrap-or-saturate to int16),
      // NOT full-precision Q16. Then the taps are combined with vmull_s16/vmlal_s16
      // (int16×int16→int32)
      // across a 4-lane int32 accumulator with NO final >>16. The former full-precision kernel
      // ((v1<<16)+2·diff·s2) then >>16 was ~0.03% (−66 dB) brighter than hardware — golden-verified
      // (WaveTableGoldenBufferTest). Replicate C exactly: int16 vqdmulh + int16 wrap, then the same
      // lane-structured int32 accumulation (lane j = Σ_b kv[4b+j]·data[4b+j], j=k&3), summed as C
      // does.
      int l0 = 0, l1 = 0, l2 = 0, l3 = 0;
      for (int k = 0; k < 16; k++) {
        int v1 = k1[k];
        int diff = (short) (k2[k] - v1); // vsubq_s16 wraps to int16
        int md = (2 * diff * strength2) >> 16; // vqdmulhq_n_s16: doubling multiply, high half
        md = md > 32767 ? 32767 : (md < -32768 ? -32768 : md); // …saturating to int16
        int kv = (short) (v1 + md); // vaddq_s16 wraps to int16
        int prod = kv * bandData[(startValue + k) & mask]; // vmull_s16: int16×int16 → int32
        switch (k & 3) {
          case 0 -> l0 += prod;
          case 1 -> l1 += prod;
          case 2 -> l2 += prod;
          default -> l3 += prod;
        }
      }
      outputBuffer[offset + i] = (l0 + l2) + (l1 + l3); // vadd_s32 then vpadd_s32
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

      // Same kernel-row fix as doRenderingLoopSingleCycle: the C -5 is a byte-vs-line (32
      // bytes/row)
      // pointer-arithmetic artifact (wave_table.cpp:854-861); a row-indexed port must drop it,
      // giving
      // the faithful shift (28-mag)&0xF instead of (23-mag), which was landing inside strength2's
      // bits.
      int progressSmall =
          ((-currentPhase)
              >>> (32 + kInterpolationMaxNumSamplesMagnitude - 8 - bandCycleSizeMagnitude));
      progressSmall &= 0xF; // 16 lines in kernel table

      short[] k1 = kernel[progressSmall];
      short[] k2 = kernel[progressSmall + 1];

      // Faithful int16 vqdmulh kernel — same fix as doRenderingLoopSingleCycle
      // (wave_table.cpp:979-986):
      // int16 vsub/vqdmulh/vadd, NOT full-precision Q16. The kernelVector taps are shared by both
      // cycles; each cycle then accumulates with the same lane-structured int32 sum (lane j=k&3),
      // NO final >>16 (C:988-1007). Fixed the ~0.03% (−66 dB) over-bright drift on wavetable
      // voices.
      WaveTableScratch scratch = scratchHolder.get();
      short[] kv = scratch.kernelVector;
      for (int k = 0; k < 16; k++) {
        int v1 = k1[k];
        int d = (short) (k2[k] - v1); // vsubq_s16 wraps to int16
        int md = (2 * d * strength2) >> 16; // vqdmulhq_n_s16
        md = md > 32767 ? 32767 : (md < -32768 ? -32768 : md); // …saturating to int16
        kv[k] = (short) (v1 + md); // vaddq_s16 wraps to int16
      }

      int mask = (1 << bandCycleSizeMagnitude) - 1;
      int whichValueCentral = (currentPhase >>> (32 - bandCycleSizeMagnitude));
      int startValue = whichValueCentral - 8;

      int[] vals = scratch.vals;
      for (int c = 0; c < 2; c++) {
        int tableOffset = (c == 0) ? table1Offset : table2Offset;
        int l0 = 0, l1 = 0, l2 = 0, l3 = 0; // vmull_s16 int32 lanes
        for (int k = 0; k < 16; k++) {
          int prod = kv[k] * bandData[tableOffset + ((startValue + k) & mask)];
          switch (k & 3) {
            case 0 -> l0 += prod;
            case 1 -> l1 += prod;
            case 2 -> l2 += prod;
            default -> l3 += prod;
          }
        }
        vals[c] = (l0 + l2) + (l1 + l3); // vadd_s32 (twosies[c])
      }

      int diff = vals[1] - vals[0];
      // C wave_table.cpp:1014-1016: crossCycleStrength2 is uint32_t, so `>> 1` is LOGICAL. Java's
      // arithmetic >> on a high-bit-set value (e.g. 0x80000000) would give a negative half → wrong
      // cross-cycle blend. Use >>> to match the C's unsigned shift.
      outputBuffer[offset + i] =
          Functions.multiply_accumulate_32x32_rshift32_rounded(
              vals[0] >> 1, diff, currentCrossCycleStrength2 >>> 1);

      currentCrossCycleStrength2 += crossCycleStrength2Increment;
    }
  }
}
