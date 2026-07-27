#!/usr/bin/env bash
# Build the standalone C DX7-envelope golden-buffer harness and regenerate the golden
# buffers under src/test/resources/fidelity/dxenv/.
#
# Links the REAL firmware env.cpp (the per-operator DX7 QRATE/QLEVEL envelope) on desktop
# g++ — it is self-contained (only <math.h>, levellut + scaleoutlevel inline), pure integer
# per-sample, no ARM-SIMD — and emits per-sample envelope levels so the Java port
# (Dx7Voice.Dx7Env) can be bit-diffed sample-exact.  sr_multiplier == 1<<24 at 44100.
#
# Usage:  FW=/path/to/DelugeFirmware tools/env_harness/build.sh
# Then:   mvn test -Pslow-tests -Dtest=Dx7EnvGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/dxenv"
BUILD="$HERE/build"

if [ ! -f "$FW/src/deluge/dsp/dx/env.cpp" ]; then
  echo "error: firmware not found at FW=$FW (set FW=/path/to/DelugeFirmware)" >&2
  exit 1
fi

mkdir -p "$BUILD" "$OUT"
echo "linking harness against real env.cpp ..."
g++ -std=c++23 -O2 -w -I"$FW/src/deluge" -I"$FW/src" \
  "$HERE/main_env.cpp" "$FW/src/deluge/dsp/dx/env.cpp" -o "$BUILD/gen_env"

GEN="$BUILD/gen_env"
# gen_env: r0 r1 r2 r3 l0 l1 l2 l3 outlevel rateScaling nsamp n keyoffAt out
echo "regenerating DX7 envelope golden matrix -> $OUT"
"$GEN" 95 60 40 70 99 85 70 0 3168 20 512 64 300 "$OUT/c_env_default.bin"
"$GEN" 20 30 25 40 99 90 80 0 3168 0  512 64 300 "$OUT/c_env_slow.bin"
"$GEN" 99 99 99 99 99 99 99 0 4256 40 512 64 300 "$OUT/c_env_fast.bin"

echo "done. DX7 envelope golden buffers written to $OUT"
