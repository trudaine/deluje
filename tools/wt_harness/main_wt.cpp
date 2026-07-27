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

// Deterministic synthetic single cycle, bit-identically reproducible in Java (int wrap + >>> logical).
static int16_t synthCycle(int i) {
  uint32_t x = (uint32_t)i * 1103515245u + 12345u;
  x ^= x >> 16;
  return (int16_t)(x & 0xFFFF);
}

int main(int argc, char** argv) {
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
