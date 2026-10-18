#!/usr/bin/env bash
# Build the standalone C NE10 fixed-point int32 FFT golden-buffer harness and regenerate the
# goldens under src/test/resources/fidelity/ne10_fft/.
#
# Links directly against the real ../DelugeFirmware/src/NE10/modules/dsp/NE10_fft.c and
# NE10_fft_int32.c with a minimal deluge/deluge.h shim for routineWithClusterLoading().
#
# Usage:  tools/ne10_fft_harness/build.sh
# Then:   ./build.sh test -Pslow-tests -Dtest=Ne10FftInt32GoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${DELUGE_FW:-$REPO/../DelugeFirmware}"
NE10="$FW/src/NE10"
OUT="$REPO/src/test/resources/fidelity/ne10_fft"
BUILD="$HERE/build"

mkdir -p "$BUILD/shim/deluge" "$OUT"

cat > "$BUILD/shim/deluge/deluge.h" <<'SHIM'
#ifndef DELUGE_H_SHIM
#define DELUGE_H_SHIM
#ifdef __cplusplus
extern "C" {
#endif
void routineWithClusterLoading(void);
#ifdef __cplusplus
}
#endif
#endif
SHIM

INC=(
  -I"$BUILD/shim"
  -I"$NE10/inc"
  -I"$NE10/common"
  -I"$NE10/modules"
  -I"$NE10/modules/dsp"
)

echo "compiling NE10 int32 FFT harness ..."
cc -std=gnu11 -O2 -w "${INC[@]}" -c "$NE10/modules/dsp/NE10_fft.c" -o "$BUILD/NE10_fft.o"
cc -std=gnu11 -O2 -w "${INC[@]}" -c "$NE10/modules/dsp/NE10_fft_int32.c" -o "$BUILD/NE10_fft_int32.o"
c++ -std=c++20 -O2 -w "${INC[@]}" "$HERE/main_ne10_fft.cpp" "$BUILD/NE10_fft.o" "$BUILD/NE10_fft_int32.o" -lm -o "$BUILD/gen_ne10_fft"

GEN="$BUILD/gen_ne10_fft"
echo "regenerating NE10 int32 FFT golden buffers -> $OUT"
for nfft in 8 16 32 64 128 256 512 1024 2048; do
  "$GEN" r2c "$nfft" "$OUT/c_ne10_r2c_n${nfft}.bin"
  "$GEN" c2r "$nfft" "$OUT/c_ne10_c2r_n${nfft}.bin"
done

echo "done. NE10 int32 FFT golden buffers written to $OUT"
