// Standalone desktop golden-buffer harness for the Deluge C WaveTable single-cycle render path.
// UNBLOCKS the ARM-NEON (Argon) barrier via SIMDE (NEON-on-x86), like tools/osc_harness. The real
// wave_table.cpp cannot be compiled directly (it drags in NE10/FFT, the memory allocator, Sample,
// storage, clusters), so we re-host the two functions on the single-cycle render path — getKernel
// and doRenderingLoopSingleCycle — VERBATIM as free functions (only the WaveTableBand* deref is
// replaced by parameters; the body is byte-for-byte the firmware source, incl. the real NEON
// intrinsics via SIMDE and the real windowedSincKernel table linked from kernel_table.cpp).
//
// Emits a golden buffer that OscGoldenBufferTest's sibling (WaveTableGoldenBufferTest) bit-diffs
// against the Java WaveTable.render single-cycle path.
//
//   getKernel:                 wave_table.cpp:1026-1048 (NUM_OCTAVES_BETWEEN_WAVETABLE_BANDS==1 branch)
//   doRenderingLoopSingleCycle: wave_table.cpp:816-905

#include <arm_neon.h> // -> SIMDE shim (see build.sh)

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

// The real firmware kernel table, linked from kernel_table.cpp (verbatim interpolate.cpp data).
extern const int16_t windowedSincKernel[7][17][16];

// definitions_cxx.hpp:789-790
static constexpr int32_t kInterpolationMaxNumSamples = 16;
static constexpr int32_t kInterpolationMaxNumSamplesMagnitude = 4;

// ── VERBATIM wave_table.cpp:1026-1048 (the #else / NUM_OCTAVES==1 branch) ──
static const int16_t* getKernel(int32_t phaseIncrement, int32_t bandMaxPhaseIncrement) {
  int32_t whichKernel = 0;
  uint32_t phaseIncrementHere = phaseIncrement;
  while (phaseIncrementHere >= (uint32_t)bandMaxPhaseIncrement && whichKernel < 6) {
    whichKernel += 2;
    phaseIncrementHere >>= 1;
  }
  if (whichKernel < 6 && phaseIncrementHere >= (bandMaxPhaseIncrement * 0.707)) {
    whichKernel++;
  }
  return &windowedSincKernel[whichKernel][0][0];
}

// ── VERBATIM wave_table.cpp:816-905 (only the two bandHere-> derefs lifted to params) ──
static void doRenderingLoopSingleCycle(int32_t* __restrict__ thisSample, int32_t const* bufferEnd,
                                       int32_t bandCycleSizeMagnitude, const int16_t* __restrict__ table,
                                       uint32_t phase, uint32_t phaseIncrement,
                                       const int16_t* __restrict__ kernel) {
  do {
    phase += phaseIncrement;

    // Work out the location of the waveform data in memory
    int32_t whichValueCentral = (phase >> (32 - bandCycleSizeMagnitude));
    uint32_t whichValue = whichValueCentral - (kInterpolationMaxNumSamples >> 1);
    int32_t whichValueStored[2];

    for (int32_t i = 0; i < (kInterpolationMaxNumSamples >> 3); i++) {
      whichValue = whichValue & ((1 << bandCycleSizeMagnitude) - 1);
      whichValueStored[i] = whichValue;
      whichValue += 8;
    }

    // Grab the actual waveform data from memory
    int16x8x2_t interpolationBuffer;
    for (int32_t i = 0; i < (kInterpolationMaxNumSamples >> 3); i++) {
      interpolationBuffer.val[i] = vld1q_s16(&table[whichValueStored[i]]);
    }

#define numBitsInWindowedSyncTableSize 8
#define rshiftAmount ((32 + kInterpolationMaxNumSamplesMagnitude) - 16 - numBitsInWindowedSyncTableSize + 1)

    uint32_t rshifted = ((uint32_t)-phase) >> (rshiftAmount - bandCycleSizeMagnitude);
    int16_t strength2 = rshifted & 32767;

    int32_t windowedSincTableLineOffsetBytes =
        ((uint32_t)-phase)
        >> (32 + kInterpolationMaxNumSamplesMagnitude - numBitsInWindowedSyncTableSize - 5
            - bandCycleSizeMagnitude);
    windowedSincTableLineOffsetBytes &= 0b111100000;
    int16_t const* __restrict__ sincKernelReadPos =
        (int16_t const*)((uintptr_t)&kernel[0] + windowedSincTableLineOffsetBytes);

    int16x8_t kernelVector[kInterpolationMaxNumSamples >> 3];

    for (int32_t i = 0; i < (kInterpolationMaxNumSamples >> 3); i++) {
      int16x8_t value1 = vld1q_s16(sincKernelReadPos + (i << 3));
      int16x8_t value2 = vld1q_s16(sincKernelReadPos + 16 + (i << 3));

      int16x8_t difference = vsubq_s16(value2, value1);
      int16x8_t multipliedDifference = vqdmulhq_n_s16(difference, strength2);
      kernelVector[i] = vaddq_s16(value1, multipliedDifference);
    }

    int32x4_t multiplied;
    for (int32_t i = 0; i < (kInterpolationMaxNumSamples >> 3); i++) {
      if (i == 0) {
        multiplied = vmull_s16(vget_low_s16(kernelVector[i]), vget_low_s16(interpolationBuffer.val[i]));
      } else {
        multiplied = vmlal_s16(multiplied, vget_low_s16(kernelVector[i]), vget_low_s16(interpolationBuffer.val[i]));
      }
      multiplied = vmlal_s16(multiplied, vget_high_s16(kernelVector[i]), vget_high_s16(interpolationBuffer.val[i]));
    }

    int32x2_t twosies = vadd_s32(vget_high_s32(multiplied), vget_low_s32(multiplied));
    int32x2_t onesie = vpadd_s32(twosies, twosies);
    int32_t singleCycleFinalValue = vget_lane_s32(onesie, 0);

    *thisSample = singleCycleFinalValue;
  } while (++thisSample != bufferEnd);
}

// ── VERBATIM fixedpoint.h:154,181 (portable rounded Q31 helpers) ──
static inline int32_t multiply_32x32_rshift32_rounded(int32_t a, int32_t b) {
  return (int32_t)(((int64_t)a * b + 0x80000000) >> 32);
}
static inline int32_t multiply_accumulate_32x32_rshift32_rounded(int32_t sum, int32_t a, int32_t b) {
  return (int32_t)(sum + (((int64_t)a * b + 0x80000000) >> 32));
}

#define WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE 7

// ── VERBATIM wave_table.cpp:913-1024 (the multi-cycle loop; bandHere-> derefs lifted to params) ──
static void doRenderingLoop(int32_t* __restrict__ thisSample, int32_t const* bufferEnd,
                            int32_t firstCycleNumber, int32_t bandCycleSizeMagnitude,
                            int32_t bandCycleSizeNoDuplicates, const int16_t* __restrict__ bandData,
                            uint32_t phase, uint32_t phaseIncrement, uint32_t crossCycleStrength2,
                            int32_t crossCycleStrength2Increment, const int16_t* __restrict__ kernel) {
  int32_t bandCycleSizeWithDuplicates =
      bandCycleSizeNoDuplicates + WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE;
  int16_t const* __restrict__ table1 = &bandData[firstCycleNumber * bandCycleSizeWithDuplicates];
  int16_t const* __restrict__ table2 = table1 + bandCycleSizeWithDuplicates;

  do {
    phase += phaseIncrement;

    int32_t whichValueCentral = (phase >> (32 - bandCycleSizeMagnitude));
    uint32_t whichValue = whichValueCentral - (kInterpolationMaxNumSamples >> 1);
    int32_t whichValueStored[2];

    for (int32_t i = 0; i < kInterpolationMaxNumSamples >> 3; i++) {
      whichValue = whichValue & ((1 << bandCycleSizeMagnitude) - 1);
      whichValueStored[i] = whichValue;
      whichValue += 8;
    }

    int16x8x2_t interpolationBuffer[2];
    for (int32_t i = 0; i < kInterpolationMaxNumSamples >> 3; i++) {
      interpolationBuffer[0].val[i] = vld1q_s16(&table1[whichValueStored[i]]);
    }
    for (int32_t i = 0; i < kInterpolationMaxNumSamples >> 3; i++) {
      interpolationBuffer[1].val[i] = vld1q_s16(&table2[whichValueStored[i]]);
    }

    uint32_t rshifted = ((uint32_t)-phase) >> (rshiftAmount - bandCycleSizeMagnitude);
    int16_t strength2 = rshifted & 32767;

    int32_t windowedSincTableLineOffsetBytes =
        ((uint32_t)-phase)
        >> (32 + kInterpolationMaxNumSamplesMagnitude - numBitsInWindowedSyncTableSize - 5
            - bandCycleSizeMagnitude);
    windowedSincTableLineOffsetBytes &= 0b111100000;
    int16_t const* __restrict__ sincKernelReadPos =
        (int16_t const*)((uintptr_t)&kernel[0] + windowedSincTableLineOffsetBytes);

    int16x8_t kernelVector[kInterpolationMaxNumSamples >> 3];

    for (int32_t i = 0; i < (kInterpolationMaxNumSamples >> 3); i++) {
      int16x8_t value1 = vld1q_s16(sincKernelReadPos + (i << 3));
      int16x8_t value2 = vld1q_s16(sincKernelReadPos + 16 + (i << 3));

      int16x8_t difference = vsubq_s16(value2, value1);
      int16x8_t multipliedDifference = vqdmulhq_n_s16(difference, strength2);
      kernelVector[i] = vaddq_s16(value1, multipliedDifference);
    }

    int32x2_t twosies[2];
    for (int32_t c = 0; c < 2; c++) {
      int32x4_t multiplied;
      for (int32_t i = 0; i < (kInterpolationMaxNumSamples >> 3); i++) {
        if (i == 0) {
          multiplied = vmull_s16(vget_low_s16(kernelVector[i]), vget_low_s16(interpolationBuffer[c].val[i]));
        } else {
          multiplied = vmlal_s16(multiplied, vget_low_s16(kernelVector[i]), vget_low_s16(interpolationBuffer[c].val[i]));
        }
        multiplied = vmlal_s16(multiplied, vget_high_s16(kernelVector[i]), vget_high_s16(interpolationBuffer[c].val[i]));
      }
      twosies[c] = vadd_s32(vget_high_s32(multiplied), vget_low_s32(multiplied));
    }

    int32x2_t onesie = vpadd_s32(twosies[0], twosies[1]);
    int32_t value1 = vget_lane_s32(onesie, 0);
    int32_t difference = vget_lane_s32(onesie, 1) - value1;

    int32_t waveTableFinalValue =
        multiply_accumulate_32x32_rshift32_rounded(value1 >> 1, difference, crossCycleStrength2 >> 1);

    *thisSample = waveTableFinalValue;
    crossCycleStrength2 += crossCycleStrength2Increment;
  } while (++thisSample != bufferEnd);
}

// Deterministic synthetic single cycle, bit-identically reproducible in Java (int wrap + >>> logical).
static int16_t synthCycle(int i) {
  uint32_t x = (uint32_t)i * 1103515245u + 12345u;
  x ^= x >> 16;
  return (int16_t)(x & 0xFFFF);
}

// Distinct waveform per cycle so the cross-cycle blend is observable; reproducible in Java.
// MUST use uint32_t: `int * 1103515245` overflows (signed UB) and at -O2 diverges from Java's
// defined int wrap — the unsigned wrap here matches Java's `int x = i*1103515245+…` bit-for-bit.
static int16_t synthCycleMulti(int c, int i) {
  uint32_t x = (uint32_t)i * 1103515245u + 12345u;
  x ^= (uint32_t)c * 0x9E3779B1u; // 2654435761u — same low 32 bits as Java `c * 0x9E3779B1`
  x ^= x >> 16;
  return (int16_t)(x & 0xFFFF);
}

static int runMulti(int argc, char** argv) {
  // multi: mag phaseInc numCycles waveIndex nsamp out   (waveIndexIncrement = 0)
  int mag = (argc > 2) ? atoi(argv[2]) : 10;
  uint32_t phaseInc = (argc > 3) ? (uint32_t)strtoul(argv[3], 0, 0) : 0x001abcde;
  int numCycles = (argc > 4) ? atoi(argv[4]) : 4;
  uint32_t waveIndex = (argc > 5) ? (uint32_t)strtoul(argv[5], 0, 0) : 0x30000000;
  int nsamp = (argc > 6) ? atoi(argv[6]) : 512;
  const char* outpath = (argc > 7) ? argv[7] : "c_wt_multi.bin";

  int cycleSize = 1 << mag;
  const int STRIDE = cycleSize + 7; // WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE
  std::vector<int16_t> data((size_t)numCycles * STRIDE);
  for (int c = 0; c < numCycles; c++) {
    for (int i = 0; i < cycleSize; i++) data[(size_t)c * STRIDE + i] = synthCycleMulti(c, i);
    for (int g = 0; g < 7; g++) data[(size_t)c * STRIDE + cycleSize + g] = data[(size_t)c * STRIDE + g];
  }

  // render() numCycles>1 setup (wave_table.cpp:749-752,1071-1090), waveIndexIncrement = 0.
  int numCycleTransitions = numCycles - 1;
  int magT = 32 - __builtin_clz(numCycleTransitions); // getMagnitudeOld = 32 - clz
  int waveIndexMultiplier = numCycleTransitions << (31 - magT);
  int32_t waveIndexScaled = multiply_32x32_rshift32_rounded(waveIndexMultiplier, (int32_t)waveIndex);
  int lshift = 32 + magT - 30; // NUM_BITS_IN_WAVE_INDEX_SCALED_INPUT = 30
  int firstCycleNumber = waveIndexScaled >> (30 - magT);
  uint32_t crossCycleStrength2 = (uint32_t)(waveIndexScaled << lshift);

  int32_t maxPhaseInc = (int32_t)((0xFFFFFFFFull >> mag) * 1.25);
  const int16_t* kernel = getKernel((int32_t)phaseInc, maxPhaseInc);

  std::vector<int32_t> buf(nsamp, 0);
  doRenderingLoop(buf.data(), buf.data() + nsamp, firstCycleNumber, mag, cycleSize, data.data(), 0, phaseInc,
                  crossCycleStrength2, 0, kernel);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr,
          "wrote %d wt-multi samples to %s (mag=%d phaseInc=0x%08x numCycles=%d waveIndex=0x%08x "
          "firstCycle=%d crossStr=0x%08x)\n",
          nsamp, outpath, mag, phaseInc, numCycles, waveIndex, firstCycleNumber, crossCycleStrength2);
  return 0;
}

int main(int argc, char** argv) {
  if (argc > 1 && std::string(argv[1]) == "multi") return runMulti(argc, argv);
  // Params: cycleSizeMagnitude phaseInc maxPhaseInc phase nsamp out
  int mag = (argc > 1) ? atoi(argv[1]) : 11;
  uint32_t phaseInc = (argc > 2) ? (uint32_t)strtoul(argv[2], 0, 0) : 0x00100000;
  int32_t maxPhaseInc = (argc > 3) ? (int32_t)strtoul(argv[3], 0, 0) : 0;
  uint32_t phase = (argc > 4) ? (uint32_t)strtoul(argv[4], 0, 0) : 0;
  int nsamp = (argc > 5) ? atoi(argv[5]) : 512;
  const char* outpath = (argc > 6) ? argv[6] : "c_wt_golden.bin";

  int cycleSize = 1 << mag;
  const int GUARD = 7; // WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE
  std::vector<int16_t> table(cycleSize + GUARD);
  for (int i = 0; i < cycleSize; i++) table[i] = synthCycle(i);
  for (int g = 0; g < GUARD; g++) table[cycleSize + g] = table[g]; // wrap duplicates

  if (maxPhaseInc == 0) maxPhaseInc = (int32_t)((0xFFFFFFFFull >> mag) * 1.25);

  const int16_t* kernel = getKernel((int32_t)phaseInc, maxPhaseInc);

  std::vector<int32_t> buf(nsamp, 0);
  doRenderingLoopSingleCycle(buf.data(), buf.data() + nsamp, mag, table.data(), phase, phaseInc, kernel);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr, "wrote %d wt samples to %s (mag=%d phaseInc=0x%08x maxPhaseInc=%d whichKernel=%ld)\n", nsamp,
          outpath, mag, phaseInc, maxPhaseInc, (long)((kernel - &windowedSincKernel[0][0][0]) / (17 * 16)));
  return 0;
}
