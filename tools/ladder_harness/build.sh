#!/usr/bin/env bash
# Build the standalone C ladder golden-buffer harness and regenerate the golden
# buffers under src/test/resources/fidelity/ladder/.
#
# The harness links the REAL Deluge firmware lpladder.cpp + lookuptables.cpp on
# desktop g++, so the golden buffers are the firmware's own DSP output — there is
# no re-implementation and no hand-copied lookup table. Only AudioEngine::cpuDireness
# and a couple of globals are stubbed (see support.cpp). The CONG PRNG (jcong) is
# seeded to 380116160 to match Functions.resetNoiseSeed() on the Java side, so the
# noise-modulated ladder moveability is deterministic and bit-comparable.
#
# Usage:
#   FW=/path/to/DelugeFirmware tools/ladder_harness/build.sh
# (FW defaults to ../DelugeFirmware relative to the repo root.)
#
# Then: mvn test -Pslow-tests -Dtest=LadderGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/ladder"
BUILD="$HERE/build"

if [ ! -f "$FW/src/deluge/dsp/filter/lpladder.cpp" ]; then
  echo "error: firmware not found at FW=$FW (set FW=/path/to/DelugeFirmware)" >&2
  exit 1
fi

mkdir -p "$BUILD" "$OUT"
INC=(-I"$FW/src/deluge" -I"$FW/src")
STD="-std=c++23 -O2 -w"

echo "compiling real lookuptables.cpp (provides tanHSmall, tanTable) ..."
g++ $STD "${INC[@]}" -c "$FW/src/deluge/util/lookuptables/lookuptables.cpp" -o "$BUILD/lookuptables.o"

echo "linking harness against real lpladder.cpp ..."
g++ $STD "${INC[@]}" \
  "$HERE/main.cpp" "$HERE/support.cpp" \
  "$FW/src/deluge/dsp/filter/lpladder.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_golden"

GEN="$BUILD/gen_golden"
# gen: <freq_q31> <res_q31> <mode 0=12dB 1=24dB 2=drive> <morph_q28> <gain> <nsamp> <signal> <out>
gen() { "$GEN" "$1" "$2" "$3" 0 0 512 "$4" "$OUT/$5"; }

echo "regenerating golden matrix -> $OUT"
gen 800000000  1000000000 0 step    c_12db_f800_r1000_step.bin
gen 800000000  1000000000 0 impulse c_12db_f800_r1000_impulse.bin
gen 800000000  1000000000 1 step    c_24db_f800_r1000_step.bin
gen 800000000  1000000000 1 impulse c_24db_f800_r1000_impulse.bin
gen 800000000  1000000000 2 step    c_drive_f800_r1000_step.bin
gen 800000000  1000000000 2 impulse c_drive_f800_r1000_impulse.bin
gen 400000000  2000000000 1 impulse c_24db_f400_r2000_impulse.bin
gen 400000000  2000000000 2 impulse c_drive_f400_r2000_impulse.bin
gen 1500000000 300000000  1 step    c_24db_f1500_r300_step.bin

echo "done. golden buffers written to $OUT"
