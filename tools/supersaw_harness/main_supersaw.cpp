// Standalone desktop golden-buffer harness for the Deluge C supersaw oscillator
// (dsp/oscillators/supersaw.cpp, upstream 451c9f82f). Sibling of tools/osc_harness.
//
// WHY A SEPARATE HARNESS. The supersaw is the first oscillator whose per-sample output depends on
// FLOAT NEON ops, so unlike the integer wave paths it needs strict float flags and one patched
// detail (see build.sh): SIMDE's x86 vrecpe_f32 is a bit-trick, not the ARM table estimate the
// hardware computes, and that reciprocal reaches the output through the polyBLEP's `t`. build.sh
// substitutes the ARM ARM's own FPRecipEstimate so the golden is what a Deluge renders, which is
// also what Supersaw.reciprocalEstimate ports.
//
// Dumps the raw render output — no amplitude stage — because that is the new DSP under test.

#include "dsp/oscillators/supersaw.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using deluge::dsp::Supersaw;

int main(int argc, char** argv) {
  // phaseIncrement spread numSaws bandLimit(0|1) nsamp blocks out
  if (argc < 8) {
    fprintf(stderr,
            "usage: %s <phaseIncrement> <spread> <numSaws> <bandLimit 0|1> <nsamp> <blocks> <out>\n",
            argv[0]);
    return 2;
  }
  auto phaseIncrement = static_cast<uint32_t>(strtoul(argv[1], nullptr, 10));
  auto spread = static_cast<int32_t>(strtol(argv[2], nullptr, 10));
  int32_t numSaws = atoi(argv[3]);
  bool bandLimit = atoi(argv[4]) != 0;
  int32_t nsamp = atoi(argv[5]);
  int32_t blocks = atoi(argv[6]);
  const char* outpath = argv[7];

  // The naive pass writes in blocks of 4, so give it room for numSamples rounded up (supersaw.h:56)
  std::vector<int32_t> buffer(nsamp + 4);

  // A retriggered supersaw starts from sidePhasesFrom(phase) (voice_unison_part_source.cpp:90-91);
  // phase 0 keeps the golden reproducible.
  uint32_t centrePhase = 0;
  Supersaw::SidePhases sidePhases = Supersaw::sidePhasesFrom(centrePhase);

  std::vector<int32_t> all;
  all.reserve(static_cast<size_t>(nsamp) * blocks);
  for (int32_t b = 0; b < blocks; b++) {
    // Render several consecutive blocks so the phase carry-over between calls is covered too
    Supersaw::render(buffer.data(), nsamp, phaseIncrement, spread, numSaws, centrePhase, sidePhases,
                     bandLimit);
    all.insert(all.end(), buffer.begin(), buffer.begin() + nsamp);
  }

  FILE* f = fopen(outpath, "wb");
  if (!f) {
    perror("fopen");
    return 1;
  }
  fwrite(all.data(), sizeof(int32_t), all.size(), f);
  fclose(f);
  fprintf(stderr, "wrote %zu supersaw samples to %s (inc=%u spread=%d saws=%d bandLimit=%d)\n",
          all.size(), outpath, phaseIncrement, spread, numSaws, (int)bandLimit);
  return 0;
}
