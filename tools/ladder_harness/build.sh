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
# geng: same, but with an explicit non-zero filterGain (arg 3), to exercise configure()'s RETURN.
geng() { "$GEN" "$1" "$2" "$4" 0 "$3" 512 "$5" "$OUT/$6"; }

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

# Drive mode WITH 2x oversampling engaged. The three drive cases above all land on
# doOversampling=FALSE (verified by instrumenting setConfig), so until these were added the entire
# oversampled branch of lpladder.cpp:198-229 was ungoldened: the lpfFrequency halving and the
# `* 34` logFreq correction, the 39056384 cap, the resonanceLimitTable clamp, the double
# doDriveLPFOnSample call and the crude every-second-sample decimation. That branch is exactly
# where CALIB "14 LPF DRIVE" sits (issue #5), so it is the branch worth proving.
#
# doOversampling needs (logFreq>>24) > 51 AND processedResonance > the interpolated threshold;
# these two points straddle the interesting part of that table.
gen 1500000000 300000000  2 step    c_drive_os_f1500_r300_step.bin
gen 1500000000 300000000  2 impulse c_drive_os_f1500_r300_impulse.bin
gen 1200000000 1500000000 2 impulse c_drive_os_f1200_r1500_impulse.bin
gen 1200000000 1500000000 2 sine    c_drive_os_f1200_r1500_sine.bin

# Non-zero filterGain, so configure()'s RETURN is covered. Every case above passes gain=0, which
# makes drive mode's `filterGain *= 0.8` (lpladder.cpp:169) return 0 either way — so the goldens
# could not see that we had translated that line with a `0.8f` float literal instead of C's double.
# 1234567891 is chosen to have significant low bits: float's 24-bit mantissa rounds the operand
# before multiplying, so float and double results differ here and the golden discriminates.
geng 1200000000 1500000000 1234567891 2 impulse c_drive_os_gain_f1200_r1500_impulse.bin
geng 800000000  1000000000 1234567891 2 impulse c_drive_gain_f800_r1000_impulse.bin
geng 800000000  1000000000 1234567891 1 impulse c_24db_gain_f800_r1000_impulse.bin

echo "done. LP golden buffers written to $OUT"

# --- HP ladder (sibling harness: hpladder.cpp; no cpuDireness, no getNoise) ---
HPOUT="$REPO/src/test/resources/fidelity/hpladder"
mkdir -p "$HPOUT"
echo "linking harness against real hpladder.cpp ..."
g++ $STD "${INC[@]}" \
  "$HERE/main_hp.cpp" "$HERE/support.cpp" \
  "$FW/src/deluge/dsp/filter/hpladder.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_hp"

HPGEN="$BUILD/gen_hp"
# gen_hp: <freq_q31> <res_q31> <morph_q28> <gain> <nsamp> <signal> <out>
hpgen() { "$HPGEN" "$1" "$2" 0 0 512 "$3" "$HPOUT/$4"; }

echo "regenerating HP golden matrix -> $HPOUT"
hpgen 800000000  1000000000 step    c_hp_f800_r1000_step.bin
hpgen 800000000  1000000000 impulse c_hp_f800_r1000_impulse.bin
hpgen 400000000  2000000000 impulse c_hp_f400_r2000_impulse.bin
hpgen 1500000000 300000000  step    c_hp_f1500_r300_step.bin
hpgen 600000000  1900000000 sine    c_hp_f600_r1900_sine.bin

echo "done. HP golden buffers written to $HPOUT"

# --- SVF (state-variable: svf.cpp; pure integer math, no cpuDireness/getNoise/float) ---
SVFOUT="$REPO/src/test/resources/fidelity/svf"
mkdir -p "$SVFOUT"
echo "linking harness against real svf.cpp ..."
g++ $STD "${INC[@]}" \
  "$HERE/main_svf.cpp" "$HERE/support.cpp" \
  "$FW/src/deluge/dsp/filter/svf.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_svf"

SVFGEN="$BUILD/gen_svf"
# gen_svf: <freq_q31> <res_q31> <mode 3=SVF_BAND 4=SVF_NOTCH> <morph_q28> <gain> <nsamp> <signal> <out>
svfgen() { "$SVFGEN" "$1" "$2" "$3" "$4" 0 512 "$5" "$SVFOUT/$6"; }

echo "regenerating SVF golden matrix -> $SVFOUT"
svfgen 800000000 1000000000 3 0         step    c_svf_band_f800_r1000_m0_step.bin
svfgen 800000000 1000000000 3 134217728 impulse c_svf_band_f800_r1000_mhalf_impulse.bin
svfgen 400000000 2000000000 3 268435455 impulse c_svf_band_f400_r2000_mfull_impulse.bin
svfgen 800000000 1000000000 4 0         step    c_svf_notch_f800_r1000_m0_step.bin
svfgen 600000000 1500000000 4 134217728 sine    c_svf_notch_f600_r1500_mhalf_sine.bin

echo "done. SVF golden buffers written to $SVFOUT"

# --- Freeverb reverb (freeverb.cpp; integer per-sample, deterministic-float setup) ---
RVOUT="$REPO/src/test/resources/fidelity/reverb"
mkdir -p "$RVOUT"
echo "linking harness against real freeverb.cpp ..."
g++ $STD "${INC[@]}" \
  "$HERE/main_reverb.cpp" "$HERE/support.cpp" \
  "$FW/src/deluge/dsp/reverb/freeverb/freeverb.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_reverb"

RVGEN="$BUILD/gen_reverb"
# gen_reverb: <room_f> <damp_f> <width_f> <nsamp> <signal> <out>  (4096 samples: comb delays ~1116-1617)
rvgen() { "$RVGEN" "$1" "$2" "$3" 4096 "$4" "$RVOUT/$5"; }

echo "regenerating Freeverb golden matrix -> $RVOUT"
rvgen 0.7 0.5 1.0 impulse c_reverb_r70_d50_w100_impulse.bin
rvgen 0.9 0.2 1.0 impulse c_reverb_r90_d20_w100_impulse.bin
rvgen 0.5 0.8 0.5 impulse c_reverb_r50_d80_w50_impulse.bin
rvgen 0.7 0.5 1.0 square  c_reverb_r70_d50_w100_square.bin

echo "done. Freeverb golden buffers written to $RVOUT"
