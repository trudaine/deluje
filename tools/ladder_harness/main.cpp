// Standalone desktop golden-buffer harness for the Deluge C LpLadderFilter.
// Drives a fixed test signal through the REAL firmware ladder DSP and dumps
// q31 samples so the Java port can be bit-diffed against it.
//
// Build: see build.sh (compiles the real lpladder.cpp against the real headers,
// linking only the minimal support .cpp / tables it actually needs).

#include "dsp/filter/lpladder.h"
#include "model/mod_controllable/filters/filter_config.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp::filter;

// q31 one = 2^31-1. Cutoff/resonance/morph are the raw knob-domain q31 values
// the firmware's configure() expects (frequency & resonance in q31, morph q28).
int main(int argc, char** argv) {
  // Params (overridable via argv): freq_q31 res_q31 mode morph_q28 gain_q31 nsamp signal
  int64_t freq = (argc > 1) ? atoll(argv[1]) : 800000000LL;   // ~0.37 of full
  int64_t res = (argc > 2) ? atoll(argv[2]) : 1000000000LL;   // ~0.47 of full
  int mode = (argc > 3) ? atoi(argv[3]) : 1;                  // 1 = TRANSISTOR_24DB
  int64_t morph = (argc > 4) ? atoll(argv[4]) : 0;
  int64_t gain = (argc > 5) ? atoll(argv[5]) : 0;
  int nsamp = (argc > 6) ? atoi(argv[6]) : 512;
  const char* signal = (argc > 7) ? argv[7] : "step";
  const char* outpath = (argc > 8) ? argv[8] : "c_golden.bin";

  std::vector<int32_t> buf(nsamp);
  const int32_t AMP = 1 << 27;  // well below full-scale to stay in range
  for (int i = 0; i < nsamp; i++) {
    if (strcmp(signal, "step") == 0) {
      buf[i] = AMP;
    } else if (strcmp(signal, "impulse") == 0) {
      buf[i] = (i == 0) ? AMP : 0;
    } else {  // "sine" at ~ nsamp/16 period
      double ph = 2.0 * 3.14159265358979 * i / 16.0;
      buf[i] = (int32_t)(AMP * __builtin_sin(ph));
    }
  }

  LpLadderFilter filt;
  // The real firmware keeps filters in a FilterSet whose memory is ZEROED before
  // use (see Filter::reset comment: "All zeroes must be a valid reset state as the
  // filter data will be zeroed by the filterset"). A directly-constructed object
  // instead keeps the member initializer dryFade=1, which wrongly engages the
  // dry->wet blend fade. memset replicates the FilterSet zeroing so dryFade=0 and
  // we take the same direct path the Java port (dryFade=0.0f) does.
  memset(&filt, 0, sizeof(filt));
  filt.reset();
  // configure() maps user params -> internal (mirrors Voice's call site).
  filt.configure((q31_t)freq, (q31_t)res, (FilterMode)mode, (q31_t)morph, (q31_t)gain);

  // Filter in place, mono, increment 1 — same entry the voice uses.
  filt.filterMono(buf.data(), buf.data() + nsamp, 1);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  fwrite(buf.data(), sizeof(int32_t), nsamp, f);
  fclose(f);
  fprintf(stderr, "wrote %d samples to %s (freq=%lld res=%lld mode=%d morph=%lld signal=%s)\n",
          nsamp, outpath, (long long)freq, (long long)res, mode, (long long)morph, signal);
  return 0;
}
