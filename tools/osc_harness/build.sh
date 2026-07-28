#!/usr/bin/env bash
# Build the standalone C oscillator golden-buffer harness and regenerate the goldens under
# src/test/resources/fidelity/osc/.
#
# The oscillator (oscillator.cpp + render_wave.h) uses ARM-NEON via the Argon SIMD wrapper, which
# previously blocked a desktop harness. Argon is designed to fall back to SIMDE (SIMD-Everywhere,
# NEON-on-x86) on non-ARM: its arm_simd path does `#include <arm/neon.h>` with
# SIMDE_ENABLE_NATIVE_ALIASES. So this script:
#   1. Fetches SIMDE (header-only) into a cache dir if absent.
#   2. Writes a tiny <arm_neon.h> shim that maps to <simde/arm/neon.h> with native aliases
#      (render_wave.h includes the real ARM header name <arm_neon.h> directly).
#   3. Links oscillator.cpp + basic_waves.cpp + sine_osc.cpp + the lookuptable wave tables + a
#      minimal support.cpp (AudioEngine::cpuDireness=0, an unused WaveTable::render stub).
#
# SIMDE is bit-accurate for the integer NEON ops the oscillator uses, so the golden matches real ARM
# for SQUARE/TRIANGLE/ANALOG_SQUARE (verified bit-exact). SAW is phase-shifted (spectrally benign)
# and SINE differs by ~26 LSB (likely a SIMDE rounding artifact) — see §4.2quaterquadragies.
#
# Usage:  FW=/path/to/DelugeFirmware tools/osc_harness/build.sh
# Then:   mvn test -Pslow-tests -Dtest=OscGoldenBufferTest
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FW="${FW:-$REPO/../DelugeFirmware}"
OUT="$REPO/src/test/resources/fidelity/osc"
BUILD="$HERE/build"
ARGON="$FW/build/_deps/argon-src/include"
SIMDE="${SIMDE:-$BUILD/simde}"

if [ ! -f "$FW/src/deluge/dsp/oscillators/oscillator.cpp" ]; then
  echo "error: firmware not found at FW=$FW" >&2; exit 1
fi
if [ ! -d "$ARGON" ]; then
  echo "error: Argon headers not at $ARGON (build the firmware once so CMake fetches them)" >&2
  exit 1
fi

mkdir -p "$BUILD" "$OUT"

# 1. SIMDE (header-only) — cached under build/simde.
if [ ! -f "$SIMDE/simde/arm/neon.h" ]; then
  echo "fetching SIMDE (header-only) ..."
  git clone --depth 1 https://github.com/simd-everywhere/simde.git "$SIMDE" >/dev/null 2>&1
fi

# 2. <arm_neon.h> shim.
mkdir -p "$BUILD/shim"
cat > "$BUILD/shim/arm_neon.h" <<'SHIM'
#ifndef SIMDE_ENABLE_NATIVE_ALIASES
#define SIMDE_ENABLE_NATIVE_ALIASES
#endif
#include <simde/arm/neon.h>
SHIM

INC=(-I"$BUILD/shim" -I"$SIMDE" -I"$SIMDE/simde" -I"$ARGON" -I"$FW/src/deluge" -I"$FW/src")
STD="-std=c++23 -O2 -w -march=native"

echo "compiling oscillator + basic_waves + sine_osc + wave tables (via SIMDE) ..."
g++ $STD "${INC[@]}" -c "$FW/src/deluge/dsp/oscillators/oscillator.cpp" -o "$BUILD/oscillator.o"
g++ $STD "${INC[@]}" -c "$FW/src/deluge/dsp/oscillators/basic_waves.cpp" -o "$BUILD/basic_waves.o"
g++ $STD "${INC[@]}" -c "$FW/src/deluge/dsp/oscillators/sine_osc.cpp" -o "$BUILD/sine_osc.o"
mkdir -p "$BUILD/lut"
for f in "$FW"/src/deluge/util/lookuptables/*.cpp; do
  g++ $STD "${INC[@]}" -c "$f" -o "$BUILD/lut/$(basename "$f" .cpp).o"
done

echo "linking oscillator harness ..."
g++ $STD "${INC[@]}" "$HERE/main_osc.cpp" "$HERE/support.cpp" \
  "$BUILD/oscillator.o" "$BUILD/basic_waves.o" "$BUILD/sine_osc.o" "$BUILD"/lut/*.o \
  -o "$BUILD/gen_osc"

GEN="$BUILD/gen_osc"
# gen_osc: type(0=SINE,1=TRI,2=SQ,3=ANSQ,4=SAW) phaseInc pulseWidth amplitude ampInc nsamp out
echo "regenerating oscillator golden matrix -> $OUT"
"$GEN" 4 0x004ec4ec 0          $((1<<27)) 0 512 "$OUT/c_osc_saw_f5162220.bin"
"$GEN" 4 0x00a00000 0          $((1<<27)) 0 512 "$OUT/c_osc_saw_bandlimited.bin"
"$GEN" 2 0x004ec4ec 0          $((1<<27)) 0 512 "$OUT/c_osc_square_f5162220.bin"
"$GEN" 0 0x004ec4ec 0          $((1<<27)) 0 512 "$OUT/c_osc_sine_f5162220.bin"
"$GEN" 1 0x004ec4ec 0          $((1<<27)) 0 512 "$OUT/c_osc_triangle_f5162220.bin"
"$GEN" 3 0x00a00000 0          $((1<<27)) 0 512 "$OUT/c_osc_analogsquare_f.bin"
"$GEN" 2 0x004ec4ec 0x40000000 $((1<<27)) 0 512 "$OUT/c_osc_square_pw25.bin"

# Band-limited (tableNumber>=6) at pitches with rich low bits. The cases above do NOT exercise the
# band interpolation: 0x004ec4ec lands in the crude tableNumber<6 path for saw/square, and
# 0x00a00000 is a multiple of 2^21 so at tableSizeMagnitude 11 the interpolation fraction is 0 on
# every sample. Without these, waveRenderingFunctionGeneral's int16 lerp is untested.
"$GEN" 4 0x00a12345 0 $((1<<27)) 0 512 "$OUT/c_osc_saw_interp.bin"
"$GEN" 2 0x00a12345 0 $((1<<27)) 0 512 "$OUT/c_osc_square_interp.bin"
"$GEN" 3 0x0212abcd 0 $((1<<27)) 0 512 "$OUT/c_osc_analogsquare_interp.bin"
"$GEN" 1 0x0212abcd 0 $((1<<27)) 0 512 "$OUT/c_osc_triangle_interp.bin"
"$GEN" 0 0x0212abcd 0 $((1<<27)) 0 512 "$OUT/c_osc_sine_interp.bin"

# ── Oscillator hard sync (render_wave.h renderOscSync + the crude per-sample reset loops) ──
# gen_osc: ... nsamp out resetterPhaseInc [resetterPhase [retriggerPhase [applyAmp]]]
#
# nsamp is 128, NOT 512: the sync path reads the firmware global oscSyncRenderingBuffer
# (SSI_TX_BUFFER_NUM_SAMPLES+4 == 132 int32) over numSamples, so a longer render walks off the end
# of it and produces nondeterministic goldens. gen_osc hard-errors above 128.
#
# The pitches matter. 0x00a00000 is a multiple of 2^21, so at tableSizeMagnitude 11 the
# interpolation fraction is 0 on EVERY sample — the band interpolation is never exercised and the
# case passes vacuously (0/128 samples with a non-zero fraction). The pitches below carry rich low
# bits (>=95% of samples interpolate) and span the crude (tableNumber<6) and band-limited paths.
SYNC_PITCHES="0x00a12345 0x004ec4ec 0x0212abcd"
SYNC_RESETTERS="0x04000000 0x02000000 0x0a000000"
for pi in $SYNC_PITCHES; do
  for r in $SYNC_RESETTERS; do
    for t in 1 2 3 4; do
      "$GEN" $t $pi 0 $((1<<27)) 0 128 "$OUT/c_osc_sync_t${t}_p${pi}_r${r}.bin" $r 0 0 0
    done
  done
done
# Same, with the amplitude stage applied (applyAmp=1) — the way the firmware actually calls it.
"$GEN" 4 0x00a12345 0 $((1<<27)) 0 128 "$OUT/c_osc_sync_amp_saw.bin"    0x04000000 0 0 1
"$GEN" 2 0x0212abcd 0 $((1<<27)) 0 128 "$OUT/c_osc_sync_amp_square.bin" 0x02000000 0 0 1

echo "done. oscillator golden buffers written to $OUT"
