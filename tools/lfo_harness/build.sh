#!/usr/bin/env bash
# Build the standalone C LFO golden-buffer harness and regenerate the goldens under
# src/test/resources/fidelity/lfo/.
#
# lfo.h is header-only, so this compiles the REAL firmware header and instantiates the REAL LFO
# class — no re-implementation, no hand-copied constants. The CONG PRNG (jcong) is seeded to
# 380116160 to match Functions.resetNoiseSeed() on the Java side, so SAMPLE_AND_HOLD / RANDOM_WALK /
# WARBLER are deterministic and bit-comparable.
#
# Usage:  FW=/path/to/DelugeFirmware tools/lfo_harness/build.sh
# Then:   mvn test -Pslow-tests -Dtest=LfoGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/lfo"
BUILD="$HERE/build"

if [ ! -f "$FW/src/deluge/modulation/lfo.h" ]; then
  echo "error: firmware not found at FW=$FW (set FW=/path/to/DelugeFirmware)" >&2
  exit 1
fi

mkdir -p "$BUILD" "$OUT"
INC=(-I"$FW/src/deluge" -I"$FW/src")
STD="-std=c++23 -O2 -w"

echo "compiling real lookuptables.cpp (provides sineWaveSmall, triangle tables) ..."
g++ $STD "${INC[@]}" -c "$FW/src/deluge/util/lookuptables/lookuptables.cpp" -o "$BUILD/lookuptables.o"

echo "linking harness against real modulation/lfo.h + lfo.cpp ..."
g++ $STD "${INC[@]}" \
  "$HERE/main_lfo.cpp" \
  "$FW/src/deluge/modulation/lfo.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_lfo"

GEN="$BUILD/gen_lfo"
# gen: <type> <phaseIncrement> <numSamplesPerBlock> <numBlocks> <init local|global> <out>
gen() { "$GEN" "$1" "$2" "$3" "$4" "$5" "$OUT/$6"; }

echo "regenerating LFO golden matrix -> $OUT"
# Deterministic shapes, local (voice) and global (song) initial phase. 8388608 at 64 samples/block
# wraps the 32-bit phase every ~8 blocks, so the wrap path is exercised inside a 64-block run.
for t in SINE TRIANGLE SQUARE SAW; do
  lower=$(echo "$t" | tr 'A-Z' 'a-z')
  gen "$t" 8388608 64 64 local  "c_lfo_${lower}_i8388608_n64_local.bin"
  gen "$t" 8388608 64 64 global "c_lfo_${lower}_i8388608_n64_global.bin"
done

# Random shapes. These consume CONG, so they also pin the PRNG call COUNT and ORDER, not just the
# arithmetic: a divergence in how often getNoise() is called desynchronises every later value.
# Two rates: one that wraps often (many CONG draws) and one that wraps rarely.
for t in SAMPLE_AND_HOLD RANDOM_WALK WARBLER; do
  lower=$(echo "$t" | tr 'A-Z' 'a-z')
  gen "$t" 8388608  64 64 local "c_lfo_${lower}_i8388608_n64_local.bin"
  gen "$t" 71582788 64 64 local "c_lfo_${lower}_i71582788_n64_local.bin"
done

echo "done. LFO golden buffers written to $OUT"
