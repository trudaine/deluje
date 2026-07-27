// Minimal link support for the desktop oscillator harness (basic waves only).
#include "processing/engines/audio_engine.h"
#include "storage/wave_table/wave_table.h"

#include <cstdint>

// audio_engine.h: extern int32_t cpuDireness; — 0 = normal (non-CPU-starved) render path.
namespace AudioEngine {
int32_t cpuDireness = 0;
}

// WaveTable::render is only reached for OscType::WAVETABLE; basic waves pass waveTable=nullptr and
// never call it. Provide a never-executed stub so the (unused) reference links.
uint32_t WaveTable::render(int32_t*, int32_t, uint32_t, uint32_t, bool, uint32_t, uint32_t, int32_t,
                           uint32_t, int32_t, int32_t) {
  return 0;
}
