// Standalone desktop golden-buffer harness for the Deluge C Freeverb reverb model.
// Sibling of the filter harnesses. The freeverb per-sample path is pure integer
// (comb/allpass multiply_32x32_rshift32_rounded); its setup uses only deterministic
// float arithmetic (*, /, -, no transcendentals), and both sides use
// (float)INT32_MAX == 2147483648.0f, so it is a valid bit-exact target (unlike the
// compressor, whose audio path depends on libm exp/log).

#include "dsp/reverb/freeverb/freeverb.hpp"
#include "dsp/stereo_sample.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp::reverb;

int main(int argc, char** argv) {
  // Params: roomsize_f damping_f width_f nsamp signal out  (floats in 0..1)
  double roomsize = (argc > 1) ? atof(argv[1]) : 0.7;
  double damping = (argc > 2) ? atof(argv[2]) : 0.5;
  double widthf = (argc > 3) ? atof(argv[3]) : 1.0;
  int nsamp = (argc > 4) ? atoi(argv[4]) : 512;
  const char* signal = (argc > 5) ? argv[5] : "impulse";
  const char* outpath = (argc > 6) ? argv[6] : "c_reverb_golden.bin";

  std::vector<int32_t> input(nsamp);
  const int32_t AMP = 1 << 27;
  for (int i = 0; i < nsamp; i++) {
    if (strcmp(signal, "step") == 0) {
      input[i] = AMP;
    } else if (strcmp(signal, "impulse") == 0) {
      input[i] = (i == 0) ? AMP : 0;
    } else {  // "square": sustained large-amplitude PURE-INTEGER signal (period 32) — no
              // transcendental, so C and Java generate byte-identical input (Java Math.sin does
              // NOT match libm at the last ULP, which would otherwise diverge under sustained drive).
      input[i] = ((i / 16) & 1) ? -(AMP >> 4) : (AMP >> 4);  // reduced amplitude: stay within the reverb's integer range (full-scale sustained drive overflows the comb feedback accumulators, where C signed-overflow UB and Java defined-wrap legitimately differ)
    }
  }

  Freeverb rv;  // constructor sets defaults + mutes
  rv.setRoomSize((float)roomsize);
  rv.setDamping((float)damping);
  rv.setWidth((float)widthf);
  // ProcessOne mixes via getPanLeft()/getPanRight() (base Reverb pan, default 0). Set a fixed
  // centered pan so the reverb actually contributes; the Java test sets the SAME value.
  rv.setPanLevels(1 << 30, 1 << 30);

  std::vector<StereoSample> output(nsamp);
  rv.process(std::span<int32_t>(input.data(), nsamp),
             std::span<StereoSample>(output.data(), nsamp));

  // Dump interleaved L,R int32.
  std::vector<int32_t> lr(nsamp * 2);
  for (int i = 0; i < nsamp; i++) {
    lr[2 * i] = output[i].l;
    lr[2 * i + 1] = output[i].r;
  }
  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(lr.data(), sizeof(int32_t), nsamp * 2, f);
  fclose(f);
  fprintf(stderr, "wrote %d reverb frames to %s (room=%.3f damp=%.3f width=%.3f signal=%s)\n",
          nsamp, outpath, roomsize, damping, widthf, signal);
  return 0;
}
