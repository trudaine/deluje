#!/usr/bin/env bash
# Build the standalone C FM-operator-kernel golden-buffer harness and regenerate
# the golden buffers under src/test/resources/fidelity/fm/.
#
# Links the REAL Deluge firmware fm_op_kernel.cpp + math_lut.cpp on desktop g++.
# The FM op kernel is the FM sideband generator (compute / compute_pure /
# compute_fb) — pure phase/gain/Sin::lookup math, no PRNG, no AudioEngine. The
# harness fills the real firmware sintab via dx_init_lut_data() (support_fm.cpp),
# so Sin::lookup runs against the firmware's own table. Only the ARM neon asm
# kernel is stubbed (never called: the harness passes neon=false), plus the
# dxEngine global (we skip engine.cpp / the voice allocator).
#
# `-include cstdint` works around aligned_buf.h relying on the ARM toolchain to
# pull in <cstdint> transitively for intptr_t.
#
# Usage:
#   FW=/path/to/DelugeFirmware tools/fm_harness/build.sh
# Then: mvn test -Pslow-tests -Dtest=FmKernelGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/fm"
BUILD="$HERE/build"

if [ ! -f "$FW/src/deluge/dsp/dx/fm_op_kernel.cpp" ]; then
  echo "error: firmware not found at FW=$FW (set FW=/path/to/DelugeFirmware)" >&2
  exit 1
fi

mkdir -p "$BUILD" "$OUT"
INC=(-I"$FW/src/deluge" -I"$FW/src" -I"$FW/src/deluge/dsp/dx")
STD="-std=c++23 -O2 -w -include cstdint"

echo "compiling real math_lut.cpp (real sintab/freq_lut/tanh/exp2) ..."
g++ $STD "${INC[@]}" -c "$FW/src/deluge/dsp/dx/math_lut.cpp" -o "$BUILD/math_lut.o"

echo "linking harness against real fm_op_kernel.cpp ..."
g++ $STD "${INC[@]}" \
  "$HERE/main_fm.cpp" "$HERE/support_fm.cpp" \
  "$FW/src/deluge/dsp/dx/fm_op_kernel.cpp" \
  "$BUILD/math_lut.o" \
  -o "$BUILD/gen_fm"

GEN="$BUILD/gen_fm"
echo "regenerating FM goldens -> $OUT"
"$GEN" fb   512 "$OUT/c_fm_fb.bin"
"$GEN" pure 512 "$OUT/c_fm_pure.bin"
"$GEN" mod  512 "$OUT/c_fm_mod.bin"

echo "done. FM golden buffers written to $OUT"
