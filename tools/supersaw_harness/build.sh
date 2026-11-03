#!/usr/bin/env bash
# Build the C supersaw golden-buffer harness and regenerate the goldens under
# src/test/resources/fidelity/osc/.
#
# Usage:  FW=/path/to/DelugeFirmware tools/supersaw_harness/build.sh
# Then:   ./build.sh test -Pslow-tests -Dtest=SupersawGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/osc"
BUILD="$HERE/build"
ARGON="$FW/build/_deps/argon-src/include"
SIMDE="${SIMDE:-$REPO/tools/osc_harness/build/simde}"

if [ ! -f "$FW/src/deluge/dsp/oscillators/supersaw.cpp" ]; then
  echo "error: supersaw.cpp not found at FW=$FW (upstream 451c9f82f or later)" >&2; exit 1
fi

mkdir -p "$BUILD/shim" "$OUT"
if [ ! -f "$SIMDE/simde/arm/neon.h" ]; then
  echo "fetching SIMDE (header-only) ..."
  mkdir -p "$(dirname "$SIMDE")"
  git clone --depth 1 https://github.com/simd-everywhere/simde.git "$SIMDE" >/dev/null 2>&1
fi
cat > "$BUILD/shim/arm_neon.h" <<'SHIM'
#ifndef SIMDE_ENABLE_NATIVE_ALIASES
#define SIMDE_ENABLE_NATIVE_ALIASES
#endif
#include <simde/arm/neon.h>
SHIM

# RECIPROCAL PATCH — the one place SIMDE is not bit-accurate for this file.
#
# supersaw.cpp takes each saw's increment reciprocal with Argon's ReciprocalEstimateRefine(2):
# ARM's vrecpe.f32 table estimate plus two Newton steps (vrecps). SIMDE's x86 vrecpe_f32 is a
# bit-trick plus one step instead (simde/arm/neon/recpe.h), so it lands on different last bits —
# and those bits reach the output, because the polyBLEP scales its residual by t = x * reciprocal.
# (The edge POSITIONS are safe either way: the code corrects the float quotient with the exact
# integer remainder.) So compile a COPY of supersaw.cpp with that one line computing the
# reciprocals through the ARM ARM's FPRecipEstimate pseudocode, which is what the hardware does and
# what Supersaw.reciprocalEstimate ports. Everything else in the file is integer NEON, where SIMDE
# is bit-accurate. The copy is regenerated from the firmware on every build, so it tracks upstream.
PATCHED="$BUILD/patched"
rm -rf "$PATCHED" && mkdir -p "$PATCHED"
cat > "$PATCHED/arm_recip.h" <<'RECIP'
#pragma once
#include <cstdint>
#include <cstring>
// ARM ARM FPRecipEstimate (single precision) + two vrecps Newton steps.
inline float harness_arm_recip_estimate(float value) {
  uint32_t bits;
  std::memcpy(&bits, &value, sizeof(bits));
  uint32_t sign = bits & 0x80000000u;
  uint32_t exponent = (bits >> 23) & 0xFFu;
  uint32_t fraction = bits & 0x7FFFFFu;
  float result;
  if (exponent == 0xFFu) {
    uint32_t out = (fraction != 0) ? 0x7FC00000u : sign;
    std::memcpy(&result, &out, sizeof(result));
    return result;
  }
  if (exponent == 0u) { // zero or denormal -> infinity
    uint32_t out = sign | 0x7F800000u;
    std::memcpy(&result, &out, sizeof(result));
    return result;
  }
  if (exponent >= 253u) { // result would be denormal -> zero
    std::memcpy(&result, &sign, sizeof(result));
    return result;
  }
  // ARM's `a`: the input normalised into [0.5, 1) and scaled by 512 — the implicit 1 plus
  // fraction bits <22:15>, eight of them.
  uint32_t a = 256u + (fraction >> 15);  // 256 <= a < 512
  uint32_t b = (1u << 19) / (a * 2u + 1u);
  uint32_t r = (b + 1u) >> 1;            // 256 <= r < 512
  uint32_t out = sign | ((253u - exponent) << 23) | ((r - 256u) << 15);
  std::memcpy(&result, &out, sizeof(result));
  return result;
}
inline float harness_arm_recip_refine2(float value) {
  float est = harness_arm_recip_estimate(value);
  est = est * (2.f - value * est);
  est = est * (2.f - value * est);
  return est;
}
RECIP

RECIP_LINE='sawIncrements.ReciprocalEstimateRefine(2).StoreTo(&reciprocals[v]);'
if ! grep -qF "$RECIP_LINE" "$FW/src/deluge/dsp/oscillators/supersaw.cpp"; then
  echo "error: reciprocal patch did not apply — upstream supersaw.cpp changed shape" >&2
  exit 1
fi
# Keep the firmware's own directory layout in the copy so its quoted includes still resolve
mkdir -p "$PATCHED/dsp/oscillators"
cp "$FW/src/deluge/dsp/oscillators/supersaw.h" "$PATCHED/dsp/oscillators/supersaw.h"
{ echo '#include "arm_recip.h"'; cat "$FW/src/deluge/dsp/oscillators/supersaw.cpp"; } \
  > "$PATCHED/dsp/oscillators/supersaw.cpp"
python3 - "$PATCHED/dsp/oscillators/supersaw.cpp" <<'PY'
import sys
path = sys.argv[1]
old = "sawIncrements.ReciprocalEstimateRefine(2).StoreTo(&reciprocals[v]);"
new = ("{ alignas(16) float harnessIncrements[4];\n"
       "\t\t  sawIncrements.StoreTo(harnessIncrements);\n"
       "\t\t  for (size_t harnessLane = 0; harnessLane < 4; harnessLane++) {\n"
       "\t\t    reciprocals[v + harnessLane] = harness_arm_recip_refine2(harnessIncrements[harnessLane]);\n"
       "\t\t  } }")
text = open(path).read()
assert text.count(old) == 1, text.count(old)
open(path, "w").write(text.replace(old, new))
PY

INC=(-I"$PATCHED" -I"$BUILD/shim" -I"$SIMDE" -I"$SIMDE/simde" -I"$ARGON" -I"$FW/src/deluge" -I"$FW/src")
# Strict float: no -march=native, no -ffast-math, -ffp-contract=off. NEON VMLA.F32 on the target is
# NOT fused (the product is rounded first), so contraction here would diverge from hardware as well
# as from the Java port. The firmware itself builds with -ffast-math (dc0c74916); that reorders
# nothing in this file's straight-line float arithmetic.
STD="-std=c++23 -O2 -w -ffp-contract=off"

echo "compiling supersaw harness against real dsp/oscillators/supersaw.cpp ..."
g++ $STD "${INC[@]}" -c "$PATCHED/dsp/oscillators/supersaw.cpp" -o "$BUILD/supersaw.o"
g++ $STD "${INC[@]}" "$HERE/main_supersaw.cpp" "$BUILD/supersaw.o" -o "$BUILD/gen_supersaw"

GEN="$BUILD/gen_supersaw"
# gen <phaseIncrement> <spread> <numSaws> <bandLimit> <nsamp> <blocks> <out>
gen() { "$GEN" "$1" "$2" "$3" "$4" 128 4 "$OUT/$5"; }

echo "regenerating supersaw goldens -> $OUT"
# C4 (~261.6 Hz at 44.1 kHz) is phaseIncrement ~25.5M; a low note and a high one bracket the
# band-limit behaviour. Spread 0 is the param midpoint, ±2^30 the ends.
gen 25480253 0           7 1 c_supersaw_c4_spread0_saws7_bl.bin
gen 25480253 0           7 0 c_supersaw_c4_spread0_saws7_raw.bin
gen 25480253 1073741823  7 1 c_supersaw_c4_spreadmax_saws7_bl.bin
gen 25480253 -1073741824 7 1 c_supersaw_c4_spreadmin_saws7_bl.bin
gen 25480253 0           1 1 c_supersaw_c4_spread0_saws1_bl.bin
gen 25480253 0           3 1 c_supersaw_c4_spread0_saws3_bl.bin
gen 25480253 0           4 1 c_supersaw_c4_spread0_saws4_bl.bin
gen 3185031  536870912   7 1 c_supersaw_c1_spreadhalf_saws7_bl.bin
gen 203842024 536870912  7 1 c_supersaw_c7_spreadhalf_saws7_bl.bin
echo "done."
