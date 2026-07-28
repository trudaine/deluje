// Standalone desktop golden-buffer harness for the Deluge C FilterSet — the whole
// setConfig + render path, not an individual filter.
//
// WHY THIS LEVEL. The individual filter cores are already proven bit-exact against the C
// (HpLadderGoldenBufferTest, SvfGoldenBufferTest, LadderGoldenBufferTest — all maxAbsDiff=0), yet
// the CALIB hardware corpus scores the HPF at median 0.677 with nine cases at NEGATIVE cosine
// (docs/FIDELITY_GAP_ANALYSIS.md §4.2quattuorsexagies/§4.2quinsexagies). So the defect is in the
// glue: FilterSet::setConfig's mode dispatch, the HPF morph inversion, the filterGain chain, the
// resonance quantisation, or the routing. Harnessing FilterSet itself is what isolates that.
//
// Links the REAL firmware filter_set.cpp + lpladder.cpp + hpladder.cpp + svf.cpp, so the golden is
// the firmware's own output through its own control path.

#include "dsp/filter/filter_set.h"
#include "model/mod_controllable/filters/filter_config.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

using namespace deluge::dsp::filter;

static FilterMode modeFromName(const char* s) {
  if (!strcmp(s, "off")) { return FilterMode::OFF; }
  if (!strcmp(s, "12dB")) { return FilterMode::TRANSISTOR_12DB; }
  if (!strcmp(s, "24dB")) { return FilterMode::TRANSISTOR_24DB; }
  if (!strcmp(s, "24dBDrive")) { return FilterMode::TRANSISTOR_24DB_DRIVE; }
  if (!strcmp(s, "HPLadder")) { return FilterMode::HPLADDER; }
  if (!strcmp(s, "SVF_Band")) { return FilterMode::SVF_BAND; }
  if (!strcmp(s, "SVF_Notch")) { return FilterMode::SVF_NOTCH; }
  fprintf(stderr, "unknown filter mode '%s'\n", s);
  exit(2);
}

int main(int argc, char** argv) {
  // lpFreq lpRes lpMode lpMorph  hpFreq hpRes hpMode hpMorph  gain route nsamp signal out
  if (argc < 14) {
    fprintf(stderr,
            "usage: %s lpFreq lpRes lpMode lpMorph hpFreq hpRes hpMode hpMorph gain route nsamp "
            "signal out\n  modes: off|12dB|24dB|24dBDrive|HPLadder|SVF_Band|SVF_Notch\n"
            "  route: 0=HIGH_TO_LOW 1=LOW_TO_HIGH 2=PARALLEL\n",
            argv[0]);
    return 2;
  }
  int32_t lpFreq = (int32_t)strtoll(argv[1], 0, 0);
  int32_t lpRes = (int32_t)strtoll(argv[2], 0, 0);
  FilterMode lpMode = modeFromName(argv[3]);
  int32_t lpMorph = (int32_t)strtoll(argv[4], 0, 0);
  int32_t hpFreq = (int32_t)strtoll(argv[5], 0, 0);
  int32_t hpRes = (int32_t)strtoll(argv[6], 0, 0);
  FilterMode hpMode = modeFromName(argv[7]);
  int32_t hpMorph = (int32_t)strtoll(argv[8], 0, 0);
  int32_t gain = (int32_t)strtoll(argv[9], 0, 0);
  FilterRoute route = (FilterRoute)atoi(argv[10]);
  int nsamp = atoi(argv[11]);
  const char* signal = argv[12];
  const char* outpath = argv[13];

  // HARD CONSTRAINT, same class as the oscillator sync harness: the PARALLEL route copies through
  // the firmware global `tempRenderBuffer`, which is only SSI_TX_BUFFER_NUM_SAMPLES*2 q31 long
  // (filter_set.cpp:22) because the firmware never renders more than one 128-sample audio block per
  // call. Asking for more walks off the end of it — a segfault here, silent garbage on hardware.
  if (nsamp > SSI_TX_BUFFER_NUM_SAMPLES) {
    fprintf(stderr,
            "error: FilterSet renders are limited to SSI_TX_BUFFER_NUM_SAMPLES (%d) samples; asked "
            "for %d. The PARALLEL route's tempRenderBuffer is sized for exactly one block.\n",
            SSI_TX_BUFFER_NUM_SAMPLES, nsamp);
    return 2;
  }

  // Stereo interleaved, as renderLongStereo expects.
  std::vector<int32_t> buf(nsamp * 2);
  const int32_t AMP = 1 << 27;
  for (int i = 0; i < nsamp; i++) {
    int32_t v;
    if (!strcmp(signal, "step")) {
      v = AMP;
    } else if (!strcmp(signal, "impulse")) {
      v = (i == 0) ? AMP : 0;
    } else if (!strcmp(signal, "saw")) {
      // ~262 Hz saw at 44100: the C4 the calibration corpus actually plays, so the harness
      // exercises the same spectral content the failing CALIB cases do.
      uint32_t phase = (uint32_t)((uint64_t)i * 4294967296ull * 262ull / 44100ull);
      v = (int32_t)(phase) >> 4;
    } else {
      double ph = 2.0 * 3.14159265358979 * i / 16.0;
      v = (int32_t)(AMP * __builtin_sin(ph));
    }
    buf[2 * i] = v;
    buf[2 * i + 1] = v;
  }

  FilterSet fs;
  int32_t filterGain = fs.setConfig(lpFreq, lpRes, lpMode, lpMorph, hpFreq, hpRes, hpMode, hpMorph,
                                    gain, route, false, nullptr);
  fs.renderLongStereo(buf.data(), buf.data() + nsamp * 2);

  FILE* f = fopen(outpath, "wb");
  if (!f) { perror("fopen"); return 1; }
  // Left channel only — the harness feeds both channels identically.
  for (int i = 0; i < nsamp; i++) { fwrite(&buf[2 * i], sizeof(int32_t), 1, f); }
  fclose(f);
  fprintf(stderr, "wrote %d samples -> %s (hpMode=%s hpFreq=%d hpRes=%d hpMorph=%d gain->%d)\n",
          nsamp, outpath, argv[7], hpFreq, hpRes, hpMorph, filterGain);
  return 0;
}
