#!/usr/bin/env bash
# Build the standalone C FilterSet golden-buffer harness and regenerate the goldens under
# src/test/resources/fidelity/filterset/.
#
# This harnesses the whole FilterSet CONTROL PATH (setConfig + renderLongStereo), not an individual
# filter. The individual cores are already proven bit-exact against the C, yet the CALIB hardware
# corpus scores the HPF at median 0.677 with nine NEGATIVE-cosine cases — so the defect lives in the
# glue (mode dispatch, the HPF morph inversion, the filterGain chain, resonance quantisation,
# routing). See docs/FIDELITY_GAP_ANALYSIS.md §4.2quinsexagies.
#
# Links the REAL firmware filter_set.cpp + lpladder.cpp + hpladder.cpp + svf.cpp. The filters use
# ARM NEON via Argon, so as with the oscillator harness we bridge through SIMDE (NEON-on-x86).
#
# Renders are ONE audio block (128 samples): the PARALLEL route uses the firmware global
# tempRenderBuffer, sized SSI_TX_BUFFER_NUM_SAMPLES*2. gen_fs hard-errors above that.
#
# Usage:  FW=/path/to/DelugeFirmware tools/filterset_harness/build.sh
# Then:   mvn test -Pslow-tests -Dtest=FilterSetGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/filterset"
BUILD="$HERE/build"
ARGON="$FW/build/_deps/argon-src/include"
SIMDE="${SIMDE:-$REPO/tools/osc_harness/build/simde}"

if [ ! -f "$FW/src/deluge/dsp/filter/filter_set.cpp" ]; then
  echo "error: firmware not found at FW=$FW" >&2; exit 1
fi

mkdir -p "$BUILD" "$OUT"

# SIMDE + the <arm_neon.h> shim are shared with the oscillator harness; fetch if absent.
if [ ! -f "$SIMDE/simde/arm/neon.h" ]; then
  echo "fetching SIMDE (header-only) ..."
  git clone --depth 1 https://github.com/simd-everywhere/simde.git "$SIMDE" >/dev/null 2>&1
fi
mkdir -p "$BUILD/shim"
cat > "$BUILD/shim/arm_neon.h" <<'SHIM'
#ifndef SIMDE_ENABLE_NATIVE_ALIASES
#define SIMDE_ENABLE_NATIVE_ALIASES
#endif
#include <simde/arm/neon.h>
SHIM

INC=(-I"$BUILD/shim" -I"$SIMDE" -I"$SIMDE/simde" -I"$ARGON" -I"$FW/src/deluge" -I"$FW/src")
STD="-std=c++23 -O2 -w -march=native"

echo "compiling real lookuptables.cpp ..."
g++ $STD "${INC[@]}" -c "$FW/src/deluge/util/lookuptables/lookuptables.cpp" -o "$BUILD/lookuptables.o"

echo "linking harness against real filter_set.cpp + lpladder + hpladder + svf ..."
g++ $STD "${INC[@]}" \
  "$HERE/main_fs.cpp" "$REPO/tools/ladder_harness/support.cpp" \
  "$FW/src/deluge/dsp/filter/filter_set.cpp" \
  "$FW/src/deluge/dsp/filter/lpladder.cpp" \
  "$FW/src/deluge/dsp/filter/hpladder.cpp" \
  "$FW/src/deluge/dsp/filter/svf.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_fs"

GEN="$BUILD/gen_fs"
MIN=-2147483648      # q31 minimum == the initParams default for morph/resonance/hpfFreq ("off")

# gen <hpFreq> <hpRes> <hpMode> <hpMorph> <tag>
# LPF is bypassed (mode off) so only the HPF path is under test — this mirrors the failing CALIB
# cases, which set lpfFrequency to max and leave lpfMorph off.
gen() {
  "$GEN" 2147483647 $MIN off $MIN "$1" "$2" "$3" "$4" 0 0 128 saw "$OUT/c_fs_$5.bin"
}

echo "regenerating FilterSet golden matrix -> $OUT"
# Cutoffs must be NON-NEGATIVE. curveFrequency (filter.h:128-136) feeds instantTan, which indexes
# tanTable with `input >> 25` — an arithmetic shift, so a negative frequency is a negative index and
# the C reads out of bounds (deterministic on desktop, but it is reading whatever .rodata precedes
# the table, which differs on ARM). Our port clamps that index for array safety
# (Functions.instantTan), so negative-cutoff behaviour is unportable by construction and must not be
# golden-tested. Real presets only ever use non-negative cutoffs; 0x80000000 is the "off" sentinel
# that doHPF filters out before the filter is ever configured.
for m in HPLadder SVF_Band SVF_Notch; do
  gen 268435456   $MIN        "$m" $MIN "${m}_flow_q00"
  gen 1073741824  $MIN        "$m" $MIN "${m}_fmid_q00"
  gen 1879048192  $MIN        "$m" $MIN "${m}_fhigh_q00"
  gen 1879048192  0           "$m" $MIN "${m}_fhigh_q50"
done
# Morph sweep: the leading hypothesis is that the HPF morph inversion ((1<<29)-1 - morph) overflows
# at the q31 minimum, so sweep morph explicitly across its range.
for mo in -1073741824 0 536870911 1073741824; do
  gen 1073741824 0 SVF_Band  "$mo" "SVF_Band_morph$( [ "$mo" -lt 0 ] && echo n${mo#-} || echo $mo )"
  gen 1073741824 0 SVF_Notch "$mo" "SVF_Notch_morph$( [ "$mo" -lt 0 ] && echo n${mo#-} || echo $mo )"
done
# Resonance sweep on the ladder, plus the two routings with both filters live.
gen 1073741824 1073741824 HPLadder $MIN "HPLadder_fmid_q75"
"$GEN" 1073741824 0 24dB $MIN 1073741824 0 HPLadder $MIN 0 1 128 saw "$OUT/c_fs_route_L2H.bin"
"$GEN" 1073741824 0 24dB $MIN 1073741824 0 HPLadder $MIN 0 2 128 saw "$OUT/c_fs_route_PARA.bin"

echo "done. FilterSet golden buffers written to $OUT"
