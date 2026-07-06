// Minimal support definitions to link the real lpladder.cpp on desktop.
// Everything here is either (a) a verbatim copy of the real firmware body
// (instantTan, quickLog — cited below, cross-checkable) or (b) a global the
// firmware defines elsewhere in a module we deliberately do NOT link.
//
// The lookup TABLES (tanHSmall, tanTable) are NOT here — they come from the
// real lookuptables.cpp so no table can be silently mis-transcribed.

#include "util/functions.h"

#include <cstdint>

// --- globals the firmware defines in modules we don't link ---

// filter.h: extern q31_t blendBuffer[SSI_TX_BUFFER_NUM_SAMPLES * 2];
namespace deluge::dsp::filter {
q31_t blendBuffer[SSI_TX_BUFFER_NUM_SAMPLES * 2] = {0};
}

// audio_engine.h: namespace AudioEngine { extern int32_t cpuDireness; }
// Stub to 0 => the ladder takes its normal (non-CPU-starved) path.
namespace AudioEngine {
int32_t cpuDireness = 0;
}

// waves.h: extern uint32_t z, w, jcong;  (CONG PRNG state)
// Seed jcong to the SAME value the Java port's resetNoiseSeed() uses
// (Functions.jcongSeed = 380116160) so the noise-modulated moveability is
// bit-reproducible across the C harness and the Java engine.
uint32_t z = 362436069, w = 521288629, jcong = 380116160;

// --- verbatim firmware function bodies (functions.cpp) ---

// functions.cpp:1462 instantTan — reads tanTable (from lookuptables.cpp).
int32_t instantTan(int32_t input) {
  int32_t whichValue = input >> 25;                   // 25
  int32_t howMuchFurther = (input << 6) & 2147483647; // 6
  int32_t value1 = tanTable[whichValue];
  int32_t value2 = tanTable[whichValue + 1];
  return (multiply_32x32_rshift32(value2, howMuchFurther)
          + multiply_32x32_rshift32(value1, 2147483647 - howMuchFurther))
         << 1;
}

// functions.cpp:567 quickLog — helpers (getMagnitudeOld, increaseMagnitude)
// are inline in functions.h.
int32_t quickLog(uint32_t input) {
  uint32_t magnitude = getMagnitudeOld(input);
  uint32_t inputLSBs = increaseMagnitude(input, 26 - magnitude);
  return (magnitude << 25) + (inputLSBs & ~((uint32_t)1 << 26));
}
