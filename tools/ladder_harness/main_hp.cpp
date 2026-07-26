// Standalone desktop golden-buffer harness for the Deluge C HpLadderFilter.
// Sibling of main.cpp (LP ladder) — drives a fixed test signal through the REAL
// firmware hpladder.cpp and dumps q31 samples for bit-diffing the Java port.
//
// The HP ladder is even simpler to harness than the LP: no AudioEngine::cpuDireness
// and no getNoise()/CONG (so no PRNG-seed coordination), just tanh-table math.

#include "dsp/filter/hpladder.h"
#include "model/mod_controllable/filters/filter_config.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp::filter;

int main(int argc, char** argv) {
  // Params (overridable via argv): freq_q31 res_q31 morph_q28 gain_q31 nsamp signal out
  int64_t freq = (argc > 1) ? atoll(argv[1]) : 800000000LL;
  int64_t res = (argc > 2) ? atoll(argv[2]) : 1000000000LL;
  int64_t morph = (argc > 3) ? atoll(argv[3]) : 0;
  int64_t gain = (argc > 4) ? atoll(argv[4]) : 0;
  int nsamp = (argc > 5) ? atoi(argv[5]) : 512;
  const char* signal = (argc > 6) ? argv[6] : "step";
  const char* outpath = (argc > 7) ? argv[7] : "c_hp_golden.bin";

  std::vector<int32_t> buf(nsamp);
  const int32_t AMP = 1 << 27;
  for (int i = 0; i < nsamp; i++) {
    if (strcmp(signal, "step") == 0) {
      buf[i] = AMP;
    } else if (strcmp(signal, "impulse") == 0) {
      buf[i] = (i == 0) ? AMP : 0;
    } else {  // "sine" ~ period 16
      double ph = 2.0 * 3.14159265358979 * i / 16.0;
      buf[i] = (int32_t)(AMP * __builtin_sin(ph));
    }
  }

  HpLadderFilter filt;
  // Match the FilterSet zeroing (see main.cpp): dryFade=0 (direct path) AND
  // hpfLastWorkingValue=0. The Java port initializes hpfLastWorkingValue to
  // 0x80000000 — this harness is the ground truth for whether the C actually
  // starts it at 0 (FilterSet-zeroed) as read from hpladder.h / Filter's comment.
  memset(&filt, 0, sizeof(filt));
  filt.reset();
  filt.configure((q31_t)freq, (q31_t)res, FilterMode::HPLADDER, (q31_t)morph, (q31_t)gain);

  filt.filterMono(buf.data(), buf.data() + nsamp, 1);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr, "wrote %d HP samples to %s (freq=%lld res=%lld morph=%lld signal=%s)\n",
          nsamp, outpath, (long long)freq, (long long)res, (long long)morph, signal);
  return 0;
}
