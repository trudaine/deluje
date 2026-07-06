// Standalone desktop golden-buffer harness for the Deluge C FM operator kernel
// (FmOpKernel::compute / compute_pure / compute_fb, fm_op_kernel.cpp) — the core
// FM sideband generator. Dumps q?-domain int32 output so the Java FmCore port
// (computeNormal/computePure/computeFb) can be bit-diffed against it.
//
// No PRNG, no AudioEngine — the kernel is pure phase/gain/Sin::lookup math over
// the real firmware sintab (filled by dx_init_lut_data).

#include "dsp/dx/fm_op_kernel.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace fmharness {
void initTables();
}

int main(int argc, char** argv) {
  fmharness::initTables();

  const char* which = (argc > 1) ? argv[1] : "fb";
  int n = (argc > 2) ? atoi(argv[2]) : 512;
  const char* outpath = (argc > 3) ? argv[3] : "c_fm.bin";

  // Fixed, meaningful params. freq = phase increment/sample. Pick a value that
  // sweeps several cycles across n so sidebands are exercised. gain in the
  // kernel's linear-step domain (gain for sample i = gain1 + (i+1)/64*(gain2-gain1)).
  int32_t phase0 = 0;
  int32_t freq = 0x004ec4ec;     // ~ a few hundred Hz worth of phase step
  int32_t gain1 = 1 << 22;       // constant gain (dgain=0 => gain1==gain2)
  int32_t gain2 = 1 << 22;
  int32_t dgain = 0;

  std::vector<int32_t> out(n, 0);

  if (strcmp(which, "fb") == 0) {
    int32_t fb_buf[2] = {0, 0};
    int fb_gain = 2;  // feedback shift
    FmOpKernel::compute_fb(out.data(), n, phase0, freq, gain1, gain2, dgain, fb_buf, fb_gain, false);
  } else if (strcmp(which, "pure") == 0) {
    FmOpKernel::compute_pure(out.data(), n, phase0, freq, gain1, gain2, dgain, false, false);
  } else {  // "mod" — modulated by a ramp input
    std::vector<int32_t> in(n);
    for (int i = 0; i < n; i++) in[i] = (int32_t)((int64_t)i * 0x00300000);
    FmOpKernel::compute(out.data(), n, in.data(), phase0, freq, gain1, gain2, dgain, false, false);
  }

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(out.data(), sizeof(int32_t), n, f);
  fclose(f);
  fprintf(stderr, "wrote %d samples to %s (which=%s freq=%d gain=%d)\n", n, outpath, which, freq,
          gain1);
  return 0;
}
