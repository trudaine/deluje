#!/usr/bin/env bash
# Build the standalone C WaveTable single-cycle golden-buffer harness and regenerate the goldens
# under src/test/resources/fidelity/wt/.
#
# The real wave_table.cpp cannot be compiled standalone (NE10/FFT + memory allocator + Sample +
# storage + clusters), so getKernel + doRenderingLoopSingleCycle are re-hosted VERBATIM in
# main_wt.cpp and the real windowedSincKernel table is linked from kernel_table.cpp (verbatim
# interpolate.cpp data). The NEON intrinsics resolve through SIMDE (NEON-on-x86), which is
# bit-accurate for the integer NEON ops used here (vld1q/vsubq/vqdmulhq/vmull/vmlal), so the golden
# matches real ARM.
#
# Usage:  tools/wt_harness/build.sh
# Then:   mvn test -Pslow-tests -Dtest=WaveTableGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$REPO/src/test/resources/fidelity/wt"
BUILD="$HERE/build"
SIMDE="${SIMDE:-$BUILD/simde}"

mkdir -p "$BUILD" "$OUT"

# SIMDE (header-only) — cached under build/simde (shared clone with osc_harness if present).
if [ ! -f "$SIMDE/simde/arm/neon.h" ]; then
  if [ -f "$REPO/tools/osc_harness/build/simde/simde/arm/neon.h" ]; then
    SIMDE="$REPO/tools/osc_harness/build/simde"
  else
    echo "fetching SIMDE (header-only) ..."
    git clone --depth 1 https://github.com/simd-everywhere/simde.git "$SIMDE" >/dev/null 2>&1
  fi
fi

# <arm_neon.h> shim -> SIMDE with native aliases.
mkdir -p "$BUILD/shim"
cat > "$BUILD/shim/arm_neon.h" <<'SHIM'
#ifndef SIMDE_ENABLE_NATIVE_ALIASES
#define SIMDE_ENABLE_NATIVE_ALIASES
#endif
#include <simde/arm/neon.h>
SHIM

INC=(-I"$BUILD/shim" -I"$SIMDE" -I"$SIMDE/simde")
STD="-std=c++23 -O2 -w -march=native"

echo "compiling wavetable harness (via SIMDE) ..."
g++ $STD "${INC[@]}" -c "$HERE/kernel_table.cpp" -o "$BUILD/kernel_table.o"
g++ $STD "${INC[@]}" "$HERE/main_wt.cpp" "$BUILD/kernel_table.o" -o "$BUILD/gen_wt"

GEN="$BUILD/gen_wt"
# gen_wt: cycleSizeMagnitude phaseInc maxPhaseInc phase nsamp out
# NB: phaseInc MUST have rich low bits or strength2 = ((-phase)>>>(13-mag))&32767 stays 0 for every
# sample and the vqdmulh interpolation path is never exercised (a round 2^k phaseInc is useless here).
echo "regenerating wavetable golden matrix -> $OUT"
"$GEN" 11 0x00123456 0 0 512 "$OUT/c_wt_mag11_low.bin"    # whichKernel 0, strength2 sweeps
"$GEN" 11 0x002ABCDE 0 0 512 "$OUT/c_wt_mag11_mid.bin"    # whichKernel 2
"$GEN" 10 0x001ABCDE 0 0 512 "$OUT/c_wt_mag10.bin"
"$GEN"  9 0x0034ABCD 0 0 512 "$OUT/c_wt_mag9.bin"

echo "done. wavetable golden buffers written to $OUT"
