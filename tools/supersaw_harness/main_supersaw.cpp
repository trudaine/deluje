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

#include "definitions.h"
#include "dsp/oscillators/oscillator.h"
#include "dsp/oscillators/supersaw.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <vector>

using deluge::dsp::Supersaw;

// Mode 2: the same oscillator through Oscillator::renderOsc, which is how a Voice reaches it —
// the supersaw branch at oscillator.cpp:31-46, including its amplitude stage ("supersaw comes out
// at a quarter of a saw's level", so amplitude << 2 through applyAmplitudeVectorToBuffer). That
// stage is the reason this mode exists: a golden over Supersaw::render alone cannot see it.
static int renderThroughOsc(int argc, char** argv) {
  // renderosc phaseIncrement pulseWidth waveIndex amplitude ampInc applyAmp nsamp blocks out
  if (argc < 11) {
    fprintf(stderr,
            "usage: %s renderosc <phaseIncrement> <pulseWidth> <waveIndex> <amplitude> <ampInc>"
            " <applyAmp 0|1> <nsamp> <blocks> <out>\n",
            argv[0]);
    return 2;
  }
  auto phaseIncrement = static_cast<uint32_t>(strtoul(argv[2], nullptr, 10));
  auto pulseWidth = static_cast<uint32_t>(strtoul(argv[3], nullptr, 10));
  auto waveIndex = static_cast<int32_t>(strtol(argv[4], nullptr, 10));
  auto amplitude = static_cast<int32_t>(strtol(argv[5], nullptr, 10));
  auto ampInc = static_cast<int32_t>(strtol(argv[6], nullptr, 10));
  bool applyAmp = atoi(argv[7]) != 0;
  int32_t nsamp = atoi(argv[8]);
  int32_t blocks = atoi(argv[9]);
  const char* outpath = argv[10];

  // HARD CONSTRAINT, same one tools/osc_harness/main_osc.cpp documents for its sync modes: with
  // applyAmplitude the C renders through the firmware GLOBAL oscSyncRenderingBuffer, which is
  // SSI_TX_BUFFER_NUM_SAMPLES + 4 == 132 int32 long (oscillator.cpp:25), because the firmware never
  // renders more than one 128-sample audio block per call. A larger request writes past that global
  // and the golden becomes garbage that looks exactly like a Java port bug. Refuse instead.
  if (applyAmp && nsamp > SSI_TX_BUFFER_NUM_SAMPLES) {
    fprintf(stderr,
            "error: nsamp %d exceeds SSI_TX_BUFFER_NUM_SAMPLES (%d); with applyAmp the C renders "
            "through the fixed oscSyncRenderingBuffer\n",
            nsamp, SSI_TX_BUFFER_NUM_SAMPLES);
    return 2;
  }

  std::vector<int32_t> buffer(nsamp + 4);
  uint32_t centrePhase = 0;
  Supersaw::SidePhases sidePhases = Supersaw::sidePhasesFrom(centrePhase);

  std::vector<int32_t> all;
  for (int32_t b = 0; b < blocks; b++) {
    // applyAmplitude accumulates into the buffer, so start each block from zero like the voice's
    // freshly cleared per-source buffer
    std::fill(buffer.begin(), buffer.end(), 0);
    deluge::dsp::Oscillator::renderOsc((OscType)util::to_underlying(OscType::SUPERSAW), amplitude,
                                       buffer.data(), buffer.data() + nsamp, nsamp, phaseIncrement,
                                       pulseWidth, &centrePhase, applyAmp, ampInc,
                                       /*doOscSync=*/false, /*resetterPhase=*/0,
                                       /*resetterPhaseIncrement=*/0, /*retriggerPhase=*/0,
                                       /*waveIndexIncrement=*/0, /*sourceWaveIndexLastTime=*/waveIndex,
                                       /*waveTable=*/nullptr, &sidePhases);
    all.insert(all.end(), buffer.begin(), buffer.begin() + nsamp);
  }

  FILE* f = fopen(outpath, "wb");
  if (!f) {
    perror("fopen");
    return 1;
  }
  fwrite(all.data(), sizeof(int32_t), all.size(), f);
  fclose(f);
  fprintf(stderr, "wrote %zu renderOsc supersaw samples to %s (inc=%u pw=%u amp=%d applyAmp=%d)\n",
          all.size(), outpath, phaseIncrement, pulseWidth, amplitude, (int)applyAmp);
  return 0;
}

int main(int argc, char** argv) {
  if (argc > 1 && !strcmp(argv[1], "renderosc")) {
    return renderThroughOsc(argc, argv);
  }
  // phaseIncrement spread numSaws bandLimit(0|1) nsamp blocks out
  if (argc < 8) {
    fprintf(stderr,
            "usage: %s <phaseIncrement> <spread> <numSaws> <bandLimit 0|1> <nsamp> <blocks> <out>\n"
            "       %s renderosc <phaseIncrement> <pulseWidth> <waveIndex> <amplitude> <ampInc>"
            " <applyAmp 0|1> <nsamp> <blocks> <out>\n",
            argv[0], argv[0]);
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
