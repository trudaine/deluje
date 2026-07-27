// Standalone desktop golden-buffer harness for the Deluge C Oscillator::renderOsc.
// UNBLOCKS the ARM-NEON (Argon) barrier via SIMDE (NEON-on-x86): oscillator.cpp + render_wave.h
// compile on desktop g++ when SIMDE provides <arm/neon.h>/<arm_neon.h> with native aliases (see
// build.sh). SIMDE is bit-accurate for the integer NEON ops the oscillator uses (phase accumulate +
// int16 wavetable/band interpolation, saturating multiplies), so the C golden matches real ARM and
// can bit-diff the Java scalar port (Oscillator.renderOsc). Basic waves only (waveTable=nullptr,
// doOscSync=false) — the wavetable/sync paths are separate targets.

#include "definitions_cxx.hpp"
#include "dsp/oscillators/oscillator.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp;

int main(int argc, char** argv) {
  // Params: type(0=SINE,1=TRI,2=SQ,3=ANSQ,4=SAW) phaseInc pulseWidth amplitude ampInc nsamp out
  int type = (argc > 1) ? atoi(argv[1]) : 4;                          // SAW
  uint32_t phaseInc = (argc > 2) ? (uint32_t)strtoul(argv[2], 0, 0) : 0x004ec4ec;
  uint32_t pulseWidth = (argc > 3) ? (uint32_t)strtoul(argv[3], 0, 0) : 0;
  int32_t amplitude = (argc > 4) ? (int32_t)strtol(argv[4], 0, 0) : (1 << 27);
  int32_t ampInc = (argc > 5) ? (int32_t)strtol(argv[5], 0, 0) : 0;
  int nsamp = (argc > 6) ? atoi(argv[6]) : 512;
  const char* outpath = (argc > 7) ? argv[7] : "c_osc_golden.bin";

  std::vector<int32_t> buf(nsamp, 0);
  uint32_t phase = 0;

  Oscillator::renderOsc((OscType)type, amplitude, buf.data(), buf.data() + nsamp, nsamp, phaseInc,
                        pulseWidth, &phase, /*applyAmplitude=*/true, ampInc, /*doOscSync=*/false,
                        /*resetterPhase=*/0, /*resetterPhaseIncrement=*/0, /*retriggerPhase=*/0,
                        /*waveIndexIncrement=*/0, /*sourceWaveIndexLastTime=*/0,
                        /*waveTable=*/nullptr);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr, "wrote %d osc samples to %s (type=%d phaseInc=0x%08x pw=0x%08x amp=%d)\n", nsamp,
          outpath, type, phaseInc, pulseWidth, amplitude);
  return 0;
}
