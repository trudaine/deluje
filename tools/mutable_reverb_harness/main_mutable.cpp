// Standalone desktop golden-buffer harness for the Deluge C Mutable and Digital (Dattorro) reverb
// models (dsp/reverb/mutable.hpp, digital.hpp). Sibling of the Freeverb harness in
// tools/ladder_harness/main_reverb.cpp.
//
// WHY. The only reverb golden coverage was Freeverb. Mutable and Digital had none, which is how the
// Digital model's right channel came to run on the LEFT channel's filter state (upstream fix
// f36ae0809, 2026-08-24) with the Java port faithfully carrying the same bug.
//
// BIT-EXACTNESS. Unlike Freeverb these models are float DSP, but their per-sample path uses no
// libm: the LFO is a recursive DualCosineOscillator, and everything else is + - * /. libm appears
// only in SETUP — std::log2 in setDamping and std::exp in calcFilterCutoff — and Java's
// Math.log/Math.exp need not agree with glibc at the last ULP. The generator therefore uses
// parameters that keep setup exact: damping 0 takes the `value == 0` branch (no log2) and HPF 0
// makes exp(0) == 1 exactly. LPF must be > 0 or the one-pole passes nothing, so it is the one
// unavoidable exp call; that is measured by the Java test, not assumed.
//
// Renders in 128-sample blocks, as the audio engine does, with centred pan levels (without
// setPanLevels the output is multiplied by a zero pan amplitude and is silent).

#include "dsp/reverb/digital.hpp"
#include "dsp/reverb/mutable.hpp"
#include "dsp/stereo_sample.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

using namespace deluge::dsp::reverb;

int main(int argc, char** argv) {
  // model room damp width hpf lpf nsamp signal out
  if (argc < 10) {
    fprintf(stderr, "usage: %s mutable|digital room damp width hpf lpf nsamp impulse|square out\n",
            argv[0]);
    return 2;
  }
  const char* model = argv[1];
  float room = (float)atof(argv[2]);
  float damp = (float)atof(argv[3]);
  float width = (float)atof(argv[4]);
  float hpf = (float)atof(argv[5]);
  float lpf = (float)atof(argv[6]);
  int nsamp = atoi(argv[7]);
  const char* signal = argv[8];
  const char* outpath = argv[9];

  // Mutable holds a 32768-float buffer; keep it off the stack.
  std::unique_ptr<Mutable> rv;
  if (!strcmp(model, "digital")) {
    rv = std::make_unique<Digital>();
  } else {
    rv = std::make_unique<Mutable>();
  }
  rv->setRoomSize(room);
  rv->setDamping(damp);
  rv->setWidth(width);
  rv->setHPF(hpf);
  rv->setLPF(lpf);
  rv->setPanLevels(1 << 30, 1 << 30);

  const int32_t AMP = 1 << 27;
  std::vector<int32_t> input(nsamp);
  for (int i = 0; i < nsamp; i++) {
    if (!strcmp(signal, "impulse")) {
      input[i] = (i == 0) ? AMP : 0;
    } else {  // pure-integer square, period 32 — no transcendental, byte-identical in Java
      input[i] = ((i / 16) & 1) ? -(AMP >> 4) : (AMP >> 4);
    }
  }

  std::vector<StereoSample> output(nsamp);
  const int kBlock = 128;
  for (int pos = 0; pos < nsamp; pos += kBlock) {
    int n = std::min(kBlock, nsamp - pos);
    rv->process(std::span<int32_t>(input.data() + pos, n),
                std::span<StereoSample>(output.data() + pos, n));
  }

  std::vector<int32_t> lr(nsamp * 2);
  for (int i = 0; i < nsamp; i++) {
    lr[2 * i] = output[i].l;
    lr[2 * i + 1] = output[i].r;
  }
  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(lr.data(), sizeof(int32_t), nsamp * 2, f);
  fclose(f);
  fprintf(stderr, "wrote %d %s reverb frames to %s (room=%.3f damp=%.3f width=%.3f hpf=%.3f lpf=%.3f %s)\n",
          nsamp, model, outpath, room, damp, width, hpf, lpf, signal);
  return 0;
}
