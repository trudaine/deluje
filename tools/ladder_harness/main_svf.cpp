// Standalone desktop golden-buffer harness for the Deluge C SVFilter (state-variable).
// Sibling of main.cpp / main_hp.cpp. Pure integer math (getTanHUnknown + multiplies),
// no AudioEngine, no getNoise, no float — fully deterministic.

#include "dsp/filter/svf.h"
#include "model/mod_controllable/filters/filter_config.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp::filter;

int main(int argc, char** argv) {
  // Params: freq_q31 res_q31 mode(3=SVF_BAND,4=SVF_NOTCH) morph_q28 gain nsamp signal out
  int64_t freq = (argc > 1) ? atoll(argv[1]) : 800000000LL;
  int64_t res = (argc > 2) ? atoll(argv[2]) : 1000000000LL;
  int mode = (argc > 3) ? atoi(argv[3]) : 3;  // 3 = SVF_BAND
  int64_t morph = (argc > 4) ? atoll(argv[4]) : 0;
  int64_t gain = (argc > 5) ? atoll(argv[5]) : 0;
  int nsamp = (argc > 6) ? atoi(argv[6]) : 512;
  const char* signal = (argc > 7) ? argv[7] : "step";
  const char* outpath = (argc > 8) ? argv[8] : "c_svf_golden.bin";

  std::vector<int32_t> buf(nsamp);
  const int32_t AMP = 1 << 27;
  for (int i = 0; i < nsamp; i++) {
    if (strcmp(signal, "step") == 0) {
      buf[i] = AMP;
    } else if (strcmp(signal, "impulse") == 0) {
      buf[i] = (i == 0) ? AMP : 0;
    } else {  // sine ~ period 16
      double ph = 2.0 * 3.14159265358979 * i / 16.0;
      buf[i] = (int32_t)(AMP * __builtin_sin(ph));
    }
  }

  SVFilter filt;
  memset(&filt, 0, sizeof(filt));  // match FilterSet zeroing (dryFade=0, state=0)
  filt.reset();
  filt.configure((q31_t)freq, (q31_t)res, (FilterMode)mode, (q31_t)morph, (q31_t)gain);

  filt.filterMono(buf.data(), buf.data() + nsamp, 1);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr, "wrote %d SVF samples to %s (freq=%lld res=%lld mode=%d morph=%lld signal=%s)\n",
          nsamp, outpath, (long long)freq, (long long)res, mode, (long long)morph, signal);
  return 0;
}
