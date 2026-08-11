// Standalone desktop golden-buffer harness for the Deluge C LFO (modulation/lfo.h).
//
// WHY. Issue #3 ("moving pulse-width diverges from hardware") named LFO waveform accuracy as one of
// its two remaining unverified suspects, and the LFO was the last major DSP block with no
// golden-buffer harness — the nine existing ones cover Dx7Env, FilterSet, FmKernel, HpLadder,
// Ladder, Osc, Reverb, Svf and WaveTable, but nothing modulation-side. A read-audit of Lfo.java
// against lfo.h is not sufficient evidence in this repo (see CLAUDE.md); this makes it bit-checkable.
//
// lfo.h is header-only (render() and warble() are inline in the class), so the harness includes the
// REAL header and instantiates the REAL class — there is no re-implementation here. The CONG PRNG
// (jcong) is seeded to 380116160 to match Functions.resetNoiseSeed() on the Java side, so the
// random wave types (SAMPLE_AND_HOLD, RANDOM_WALK, WARBLER) are deterministic and comparable.

#include "modulation/lfo.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

// waves.h: extern uint32_t z, w, jcong; (CONG PRNG state). Same seed as the ladder harness.
uint32_t z = 362436069, w = 521288629, jcong = 380116160;

static LFOType typeFromName(const char* s) {
  if (!strcmp(s, "SINE")) { return LFOType::SINE; }
  if (!strcmp(s, "TRIANGLE")) { return LFOType::TRIANGLE; }
  if (!strcmp(s, "SQUARE")) { return LFOType::SQUARE; }
  if (!strcmp(s, "SAW")) { return LFOType::SAW; }
  if (!strcmp(s, "SAMPLE_AND_HOLD")) { return LFOType::SAMPLE_AND_HOLD; }
  if (!strcmp(s, "RANDOM_WALK")) { return LFOType::RANDOM_WALK; }
  if (!strcmp(s, "WARBLER")) { return LFOType::WARBLER; }
  fprintf(stderr, "unknown LFO type '%s'\n", s);
  exit(2);
}

int main(int argc, char** argv) {
  // type phaseIncrement numSamplesPerBlock numBlocks initialPhaseMode out
  if (argc < 7) {
    fprintf(stderr,
            "usage: %s type phaseIncrement numSamplesPerBlock numBlocks initPhase out\n"
            "  type: SINE|TRIANGLE|SQUARE|SAW|SAMPLE_AND_HOLD|RANDOM_WALK|WARBLER\n"
            "  initPhase: local|global\n",
            argv[0]);
    return 2;
  }
  LFOType type = typeFromName(argv[1]);
  uint32_t phaseIncrement = (uint32_t)strtoull(argv[2], 0, 0);
  int32_t numSamples = atoi(argv[3]);
  int numBlocks = atoi(argv[4]);
  const char* initPhase = argv[5];
  const char* outpath = argv[6];

  LFOConfig config(type);
  LFO lfo;
  if (!strcmp(initPhase, "global")) {
    lfo.setGlobalInitialPhase(config);
  } else {
    lfo.setLocalInitialPhase(config);
  }

  // One value per render() call — render returns the block's LFO value, which is what the patcher
  // consumes per audio block. Also dump the phase after each block: a divergence in the phase
  // accumulator shows up there even when the returned value happens to agree.
  std::vector<int32_t> vals(numBlocks);
  std::vector<uint32_t> phases(numBlocks);
  for (int b = 0; b < numBlocks; b++) {
    vals[b] = lfo.render(numSamples, config, phaseIncrement);
    phases[b] = lfo.phase;
  }

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(vals.data(), sizeof(int32_t), numBlocks, f);
  fwrite(phases.data(), sizeof(uint32_t), numBlocks, f);
  fclose(f);
  fprintf(stderr, "wrote %d values + %d phases to %s (type=%s inc=%u nsamp=%d init=%s)\n", numBlocks,
          numBlocks, outpath, argv[1], phaseIncrement, numSamples, initPhase);
  return 0;
}
