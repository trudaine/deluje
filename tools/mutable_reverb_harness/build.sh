#!/usr/bin/env bash
# Build the C Mutable/Digital reverb golden-buffer harness and regenerate the goldens under
# src/test/resources/fidelity/reverb/ (alongside the Freeverb ones).
#
# The models are header-only and use Argon SIMD types (DualCosineOscillator), so, like the
# FilterSet harness, this bridges NEON to x86 through SIMDE.
#
# Usage:  FW=/path/to/DelugeFirmware tools/mutable_reverb_harness/build.sh
# Then:   mvn test -Pslow-tests -Dtest=MutableReverbGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/reverb"
BUILD="$HERE/build"
ARGON="$FW/build/_deps/argon-src/include"
SIMDE="${SIMDE:-$REPO/tools/osc_harness/build/simde}"

if [ ! -f "$FW/src/deluge/dsp/reverb/digital.hpp" ]; then
  echo "error: firmware not found at FW=$FW" >&2; exit 1
fi
if [ ! -f "$SIMDE/simde/arm/neon.h" ]; then
  echo "fetching SIMDE (header-only) ..."
  git clone --depth 1 https://github.com/simd-everywhere/simde.git "$SIMDE" >/dev/null 2>&1
fi

mkdir -p "$BUILD/shim" "$OUT"
cat > "$BUILD/shim/arm_neon.h" <<'SHIM'
#ifndef SIMDE_ENABLE_NATIVE_ALIASES
#define SIMDE_ENABLE_NATIVE_ALIASES
#endif
#include <simde/arm/neon.h>
SHIM

# HOST-COMPILER WORKAROUND. cosine_oscillator.hpp declares two `constexpr` functions (the
# DualCosineOscillator constructor and Start()) that call Argon constructors which are not
# constexpr. That is valid C++23 under P2448R2 ("relaxed constexpr"), which the firmware's own
# toolchain implements but host GCC 12 does not, so it rejects the header. The fix is to compile a
# COPY of dsp/reverb/ with only those two `constexpr` keywords stripped. `constexpr` on a function
# that is only ever called at run time has no effect on the generated arithmetic, and the copy is
# regenerated from the real firmware on every build, so it tracks upstream. `if constexpr` is a
# compile-time branch and is left untouched.
PATCHED="$BUILD/patched"
rm -rf "$PATCHED" && mkdir -p "$PATCHED/dsp/reverb"
cp "$FW"/src/deluge/dsp/reverb/*.hpp "$PATCHED/dsp/reverb/"
sed -i -E 's/\bconstexpr (DualCosineOscillator\(|void Start\()/\1/' "$PATCHED/dsp/reverb/cosine_oscillator.hpp"
if [ "$(grep -cE '^\s*constexpr (DualCosineOscillator\(|void Start\()' "$PATCHED/dsp/reverb/cosine_oscillator.hpp")" != "0" ]; then
  echo "error: constexpr workaround did not apply — upstream cosine_oscillator.hpp changed shape" >&2
  exit 1
fi

# Patched copy FIRST so "dsp/reverb/*.hpp" resolves to it; its sibling quoted includes then resolve
# within the copy. Everything else (dsp/util.hpp, definitions, stereo_sample) comes from the firmware.
INC=(-I"$PATCHED" -I"$BUILD/shim" -I"$SIMDE" -I"$SIMDE/simde" -I"$ARGON" -I"$FW/src/deluge" -I"$FW/src")
# No -march=native and no -ffast-math, and -ffp-contract=off: keep float evaluation strict so the
# golden is the C SOURCE's semantics (no FMA contraction). The firmware itself builds with
# -ffast-math since dc0c74916, so hardware float paths may differ slightly from this golden —
# expected, and not a port bug.
STD="-std=c++23 -O2 -w -ffp-contract=off"

echo "compiling harness against real dsp/reverb/{mutable,digital}.hpp ..."
g++ $STD "${INC[@]}" "$HERE/main_mutable.cpp" -o "$BUILD/gen_mutable"

GEN="$BUILD/gen_mutable"
# gen <model> <room> <damp> <width> <hpf> <lpf> <signal> <out>   (8192 frames)
gen() { "$GEN" "$1" "$2" "$3" "$4" "$5" "$6" 8192 "$7" "$OUT/$8"; }

echo "regenerating Mutable/Digital reverb goldens -> $OUT"
# damping 0 (no log2 in setup) and hpf 0 (exp(0) == 1 exactly); lpf 1.0 is the one exp call.
for m in digital mutable; do
  gen $m 0.7 0 1.0 0 1.0 impulse "c_${m}_r70_d00_w100_impulse.bin"
  gen $m 0.9 0 0.5 0 1.0 impulse "c_${m}_r90_d00_w50_impulse.bin"
  gen $m 0.7 0 1.0 0 1.0 square  "c_${m}_r70_d00_w100_square.bin"
done
echo "done."
