// Standalone desktop golden-buffer harness for the Deluge C Oscillator::renderOsc.
// UNBLOCKS the ARM-NEON (Argon) barrier via SIMDE (NEON-on-x86): oscillator.cpp + render_wave.h
// compile on desktop g++ when SIMDE provides <arm/neon.h>/<arm_neon.h> with native aliases (see
// build.sh). SIMDE is bit-accurate for the integer NEON ops the oscillator uses (phase accumulate +
// int16 wavetable/band interpolation, saturating multiplies), so the C golden matches real ARM and
// can bit-diff the Java scalar port (Oscillator.renderOsc).
//
// Two modes:
//   plain: waveTable=nullptr, doOscSync=false  — basic waves.
//   sync : doOscSync=true with a resetter phase/increment — the hard-sync path
//          (render_wave.h:26-90 renderOscSync for the table waves, or the per-sample crude reset
//          loops in oscillator.cpp for tableNumber<6).
//
// HARD CONSTRAINT (learned the hard way — see docs/FIDELITY_GAP_ANALYSIS.md §4.2novemquinquagies):
// the sync branches route through the firmware GLOBAL `oscSyncRenderingBuffer`, which is only
// SSI_TX_BUFFER_NUM_SAMPLES+4 == 132 int32 long, because the firmware never renders more than one
// 128-sample audio block per call. Both sync branches then call applyAmplitudeVectorToBuffer() over
// `numSamples` reading that global *unconditionally* (even when applyAmplitude==false, a case the
// firmware never hits). So asking this harness for numSamples > 128 makes the C read off the end of
// that global and the golden becomes nondeterministic garbage from ~sample 132 on — which looks
// exactly like a Java port bug but is not. Sync renders are therefore capped at 128 below.

#include "definitions_cxx.hpp"
#include "dsp/oscillators/oscillator.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp;

// oscillator.cpp's global scratch. The sync branches call applyAmplitudeVectorToBuffer()
// UNCONDITIONALLY, reading this buffer even when applyAmplitude==false (in the firmware that case
// never occurs for sync, since bufferStartThisSync then *is* this buffer). Zero it so a raw sync
// render doesn't accumulate uninitialised memory.
namespace deluge::dsp {
extern int32_t oscSyncRenderingBuffer[];
}

int main(int argc, char** argv) {
  // Params: type(0=SINE,1=TRI,2=SQ,3=ANSQ,4=SAW) phaseInc pulseWidth amplitude ampInc nsamp out
  //         [resetterPhaseInc [resetterPhase [retriggerPhase [applyAmp]]]]
  // Supplying resetterPhaseInc (arg 8) non-zero enables doOscSync. applyAmp (arg 11) defaults to 0
  // (raw) for sync so the golden is the bare oscillator; pass 1 to also cover the amplitude stage.
  int type = (argc > 1) ? atoi(argv[1]) : 4;                          // SAW
  uint32_t phaseInc = (argc > 2) ? (uint32_t)strtoul(argv[2], 0, 0) : 0x004ec4ec;
  uint32_t pulseWidth = (argc > 3) ? (uint32_t)strtoul(argv[3], 0, 0) : 0;
  int32_t amplitude = (argc > 4) ? (int32_t)strtol(argv[4], 0, 0) : (1 << 27);
  int32_t ampInc = (argc > 5) ? (int32_t)strtol(argv[5], 0, 0) : 0;
  int nsamp = (argc > 6) ? atoi(argv[6]) : 512;
  const char* outpath = (argc > 7) ? argv[7] : "c_osc_golden.bin";
  uint32_t resetterPhaseInc = (argc > 8) ? (uint32_t)strtoul(argv[8], 0, 0) : 0;
  uint32_t resetterPhase = (argc > 9) ? (uint32_t)strtoul(argv[9], 0, 0) : 0;
  uint32_t retriggerPhase = (argc > 10) ? (uint32_t)strtoul(argv[10], 0, 0) : 0;
  bool applyAmp = (argc > 11) ? (atoi(argv[11]) != 0) : false;

  bool doOscSync = (resetterPhaseInc != 0);
  if (!doOscSync) { applyAmp = true; }

  // See the HARD CONSTRAINT note at the top: the sync path reads the 132-sample global
  // oscSyncRenderingBuffer over `numSamples`, so anything past one audio block is out of bounds.
  if (doOscSync && nsamp > SSI_TX_BUFFER_NUM_SAMPLES) {
    fprintf(stderr,
            "error: sync renders are limited to SSI_TX_BUFFER_NUM_SAMPLES (%d) samples; asked for "
            "%d. Beyond that the C reads past the end of the global oscSyncRenderingBuffer and the "
            "golden is nondeterministic garbage.\n",
            SSI_TX_BUFFER_NUM_SAMPLES, nsamp);
    return 2;
  }

  // The sync storage lambdas store 4-wide (vst1q_s32) and can overrun the nominal window end by up
  // to 3 samples, so pad.
  std::vector<int32_t> buf(nsamp + 16, 0);
  memset(oscSyncRenderingBuffer, 0, sizeof(int32_t) * (SSI_TX_BUFFER_NUM_SAMPLES + 4));
  uint32_t phase = 0;

  Oscillator::renderOsc((OscType)type, amplitude, buf.data(), buf.data() + nsamp, nsamp, phaseInc,
                        pulseWidth, &phase, applyAmp, ampInc, doOscSync,
                        resetterPhase, resetterPhaseInc, retriggerPhase,
                        /*waveIndexIncrement=*/0, /*sourceWaveIndexLastTime=*/0,
                        /*waveTable=*/nullptr);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr,
          "wrote %d osc samples to %s (type=%d phaseInc=0x%08x pw=0x%08x amp=%d sync=%d "
          "rstInc=0x%08x rstPhase=0x%08x retrig=0x%08x) endPhase=0x%08x\n",
          nsamp, outpath, type, phaseInc, pulseWidth, amplitude, (int)doOscSync, resetterPhaseInc,
          resetterPhase, retriggerPhase, phase);
  return 0;
}
