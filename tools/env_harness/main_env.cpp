// Standalone desktop golden-buffer harness for the Deluge DX7 operator envelope (env.cpp).
// The FM op kernel is already proven bit-exact (tools/fm_harness); dx7note.cpp is ARM-SIMD-blocked;
// but env.cpp — the per-operator QRATE/QLEVEL envelope that controls operator amplitude over time
// (hence FM sideband brightness/decay) — is self-contained (only <math.h>), pure integer per-sample,
// and compiles clean on desktop. This bit-diffs the Java Dx7Voice.Dx7Env against the real C Env.

#include "dsp/dx/env.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

int main(int argc, char** argv) {
  // Params: r0 r1 r2 r3 l0 l1 l2 l3 outlevel rateScaling nsamp n keyoffAt out
  int r0 = (argc > 1) ? atoi(argv[1]) : 95;
  int r1 = (argc > 2) ? atoi(argv[2]) : 60;
  int r2 = (argc > 3) ? atoi(argv[3]) : 40;
  int r3 = (argc > 4) ? atoi(argv[4]) : 70;
  int l0 = (argc > 5) ? atoi(argv[5]) : 99;
  int l1 = (argc > 6) ? atoi(argv[6]) : 85;
  int l2 = (argc > 7) ? atoi(argv[7]) : 70;
  int l3 = (argc > 8) ? atoi(argv[8]) : 0;
  int outlevel = (argc > 9) ? atoi(argv[9]) : 3168;
  int rateScaling = (argc > 10) ? atoi(argv[10]) : 20;
  int nsamp = (argc > 11) ? atoi(argv[11]) : 512;
  int n = (argc > 12) ? atoi(argv[12]) : 64;  // subsample block size
  int keyoffAt = (argc > 13) ? atoi(argv[13]) : 300;
  const char* outpath = (argc > 14) ? argv[14] : "c_env_golden.bin";

  Env::init_sr(44100.0);  // sr_multiplier = 1<<24 at 44100 (matches Java's fixed SR_MULTIPLIER)

  Env env;
  memset(&env, 0, sizeof(env));  // fresh, zeroed object (matches a fresh Java Dx7Env)

  EnvParams p;
  p.rates[0] = (uint8_t)r0; p.rates[1] = (uint8_t)r1; p.rates[2] = (uint8_t)r2; p.rates[3] = (uint8_t)r3;
  p.levels[0] = (uint8_t)l0; p.levels[1] = (uint8_t)l1; p.levels[2] = (uint8_t)l2; p.levels[3] = (uint8_t)l3;

  env.init(p, outlevel, rateScaling);

  std::vector<int32_t> out(nsamp);
  for (int i = 0; i < nsamp; i++) {
    if (i == keyoffAt) env.keydown(p, false);  // release
    out[i] = env.getsample(p, n, 0);
  }

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(out.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr, "wrote %d env samples to %s (rates=%d,%d,%d,%d levels=%d,%d,%d,%d ol=%d rs=%d n=%d off=%d)\n",
          nsamp, outpath, r0, r1, r2, r3, l0, l1, l2, l3, outlevel, rateScaling, n, keyoffAt);
  return 0;
}
