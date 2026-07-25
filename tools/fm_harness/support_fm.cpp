// Minimal support to link fm_op_kernel.cpp + math_lut.cpp on desktop.
// We deliberately do NOT link engine.cpp (it drags the voice allocator); instead
// we provide the dxEngine global ourselves and fill only the lookup tables via
// the firmware's own dx_init_lut_data() (math_lut.cpp), so Sin::lookup reads the
// real firmware sintab.
#include "dsp/dx/engine.h"

#include <cstdint>
#include <new>

// engine.cpp: DxEngine* dxEngine = nullptr;
DxEngine* dxEngine = nullptr;

// fm_op_kernel.cpp references the ARM NEON asm kernel in the neon==true branch.
// The harness always calls with neon=false, so this stub is never executed; it
// only satisfies the linker. (abort() makes any accidental call loud.)
#include <cstdlib>
extern "C" void neon_fm_kernel(const int32_t*, const int32_t*, int32_t*, int, int32_t, int32_t,
                               int32_t, int32_t) {
  std::abort();
}

// math_lut.cpp: fills dxEngine->{exp2tab,tanhtab,sintab,freq_lut}.
void dx_init_lut_data();

namespace fmharness {
// Raw aligned storage for a DxEngine; we skip the constructor (EngineMkI/FmCore
// members) because dx_init_lut_data only writes the table arrays.
alignas(DxEngine) static unsigned char engineMem[sizeof(DxEngine)];

void initTables() {
  for (unsigned i = 0; i < sizeof(engineMem); i++) engineMem[i] = 0;
  dxEngine = reinterpret_cast<DxEngine*>(engineMem);
  dx_init_lut_data();
}
}  // namespace fmharness
