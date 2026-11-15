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
# -ffp-contract=off matches Cortex-A9 VFPv3-D16 (no fused FMADD) and Java float mul+add.
STD="-std=c++23 -O2 -ffp-contract=off -w"

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
# Compile with -fwrapv to match ARM Cortex-A9 two's-complement wrapping on loud signals.
#
# Upstream 24d4128f9 ("vectorize freeverb", 2026-11-08) made freeverb.cpp `#include <argon.hpp>`,
# whose only backends are NEON and MVE (argon's include/arm_simd.hpp falls through to
# arm_simd/neon/vfpv3.hpp, which needs <arm/neon.h>) — so freeverb.cpp NO LONGER COMPILES on a
# desktop g++ and the golden cannot be regenerated from C HEAD. Its portability backend, SIMDe,
# is not vendored in the firmware tree. The per-filter arithmetic was unchanged by that commit
# (same multiply_32x32_rshift32_rounded damping, same `input + (bufout >> 1)` allpass shortcut,
# and DelayLines keeps a per-line pos/begin/end so each line still wraps at its own length), so
# until SIMDe is available we regenerate from the last commit that builds here and say so.
# See docs/firmware_sync_audit.md §12c.
RVOUT="$REPO/src/test/resources/fidelity/reverb"
mkdir -p "$RVOUT"
FV_SRC="$FW/src/deluge/dsp/reverb/freeverb"
FV_INC=()
FV_PROV="$RVOUT/FREEVERB_PROVENANCE.txt"
if grep -q "argon.hpp" "$FV_SRC/freeverb.cpp" 2>/dev/null; then
  # TWO different things, which must not be one variable:
  #   FV_PIN is FIXED. 24d4128f9 is the commit that ADDED the argon include, so its parent is the
  #   last revision of freeverb.cpp that compiles on a desktop g++. The pin cannot track the audit
  #   frontier: any later commit X has X^ at or after 24d4128f9, which carries the include again and
  #   fails to compile, so "advance the pin" is never a valid response to the guard below.
  #   FV_AUDITED_THROUGH is the frontier, and MAY advance. It asserts: every upstream commit from
  #   FV_PIN up to here was read and found arithmetic-preserving, so goldens built at FV_PIN still
  #   describe HEAD's behaviour. Auditing a new freeverb commit means moving THIS, not the pin.
  FV_ARGON_COMMIT="24d4128f9"
  FV_PIN="$FV_ARGON_COMMIT^"
  FV_AUDITED_THROUGH="$FV_ARGON_COMMIT"
  FV_DIR="$BUILD/freeverb-pinned"

  # What the guard watches. The pinned build is a MIX: -I"$FV_DIR" shadows only the five copied
  # files, while the pinned sources still pull dsp/reverb/base.hpp (freeverb.hpp),
  # util/fixedpoint.h (comb.hpp) and util/functions.h (allpass.hpp) from $FW at HEAD. A change to
  # multiply_32x32_rshift32_rounded would therefore alter the goldens' arithmetic without touching
  # the freeverb directory at all, which is the same stale-in-silence failure this guard exists to
  # stop, so those headers are watched too.
  FV_WATCH=(
    src/deluge/dsp/reverb/freeverb/
    src/deluge/dsp/reverb/base.hpp
    src/deluge/util/fixedpoint.h
    src/deluge/util/functions.h
  )

  # Keying the pin on the mere PRESENCE of the argon include would let it go stale in silence: a
  # later upstream commit could change the arithmetic, still include <argon.hpp>, and this script
  # would keep regenerating from the old pin — identical goldens, a green ReverbGoldenBufferTest,
  # and a Java Freeverb quietly diverged from C HEAD. So refuse to pin past anything newer than the
  # audit frontier, loudly, at the only moment it matters.
  #
  # No `|| true` on the git call: if the frontier cannot be resolved in this firmware checkout
  # (shallow clone, or a fork branch whose history was rewritten — and per CLAUDE.md firmware edits
  # do live on fork branches), that is "the guard could not be evaluated", which must not read as
  # "the guard passed".
  if ! git -C "$FW" rev-parse --verify -q "$FV_AUDITED_THROUGH^{commit}" >/dev/null; then
    echo "error: cannot resolve the freeverb audit frontier $FV_AUDITED_THROUGH in $FW." >&2
    echo "       A shallow clone or a rewritten branch cannot answer whether the pin is stale," >&2
    echo "       so this refuses to regenerate rather than assume it is fine." >&2
    exit 1
  fi
  FV_NEWER=$(git -C "$FW" log --oneline "$FV_AUDITED_THROUGH..HEAD" -- "${FV_WATCH[@]}")
  if [ -n "$FV_NEWER" ]; then
    echo "error: upstream changed the freeverb build inputs since the audit frontier $FV_AUDITED_THROUGH:" >&2
    echo "$FV_NEWER" | sed 's/^/  /' >&2
    cat >&2 <<EOF
Regenerating from the pin ($FV_PIN) now would hide whatever those commits did.
Read them against the Java Freeverb. If they are arithmetic-preserving, advance
FV_AUDITED_THROUGH in this script (NOT FV_PIN — see the comment there, the pin cannot move).
Better: put SIMDe on the include path and drop the pin entirely, which is how the firmware's own
host tests build this file — see issue #176 and docs/firmware_sync_audit.md 12c.
EOF
    exit 1
  fi

  echo "note: freeverb.cpp at C HEAD needs argon/NEON; pinning the freeverb harness to $FV_PIN" >&2
  mkdir -p "$FV_DIR/dsp/reverb/freeverb"
  for f in freeverb.cpp freeverb.hpp comb.hpp allpass.hpp tuning.h; do
    git -C "$FW" show "$FV_PIN:src/deluge/dsp/reverb/freeverb/$f" > "$FV_DIR/dsp/reverb/freeverb/$f"
  done
  FV_SRC="$FV_DIR/dsp/reverb/freeverb"
  FV_INC=(-I"$FV_DIR")
  FV_PROV_TEXT="source=pinned
commit=$(git -C "$FW" rev-parse --short=9 "$FV_PIN")
reason=freeverb.cpp at C HEAD includes <argon.hpp> (NEON/MVE only) and does not compile on desktop g++.
audited_through=$FV_AUDITED_THROUGH ($(git -C "$FW" log -1 --format=%s "$FV_AUDITED_THROUGH")) - every commit from the pin to here read as arithmetic-preserving; see docs/firmware_sync_audit.md 12c
watched_paths=${FV_WATCH[*]}
head_at_generation=$(git -C "$FW" rev-parse --short=9 HEAD)"
else
  # No git required on this branch: an exported or tarball firmware tree is a supported input (the
  # script's only firmware precondition is that lpladder.cpp exists), and under `set -euo pipefail`
  # a failing command substitution inside an assignment would abort the whole run here, after the
  # LP/HP/SVF goldens have already been rewritten.
  FV_PROV_TEXT="source=head
commit=$(git -C "$FW" rev-parse --short=9 HEAD 2>/dev/null || echo unknown)
reason=freeverb.cpp compiled directly on desktop g++ - no pin needed."
fi
echo "linking harness against real freeverb.cpp ..."
# ${FV_INC[@]+...} rather than "${FV_INC[@]}": expanding an EMPTY array under `set -u` is an
# error on bash 3.2, which is still the default shell on macOS, and FV_INC is empty whenever the
# firmware checkout predates the argon include.
g++ $STD -fwrapv ${FV_INC[@]+"${FV_INC[@]}"} "${INC[@]}" \
  "$HERE/main_reverb.cpp" "$HERE/support.cpp" \
  "$FV_SRC/freeverb.cpp" \
  "$BUILD/lookuptables.o" \
  -o "$BUILD/gen_reverb"

RVGEN="$BUILD/gen_reverb"
# gen_reverb: <room_f> <damp_f> <width_f> <nsamp> <signal> <out>  (4096 samples: comb delays ~1116-1617)
rvgen() { "$RVGEN" "$1" "$2" "$3" 4096 "$4" "$RVOUT/$5"; }

echo "regenerating Freeverb golden matrix -> $RVOUT"
# Drop any previous provenance BEFORE writing a single golden. "Write it only after the goldens
# exist" closes the hazard only for all-or-nothing failure: if rvgen dies on, say, the third of the
# five cases, `set -e` exits leaving two fresh goldens, three stale ones, and the OLD provenance
# still on disk, describing bytes that are now only partly there. Absent provenance is reported by
# ReverbGoldenBufferTest as absent, which is honest; a stale one lies.
rm -f "$FV_PROV"
rvgen 0.7 0.5 1.0 impulse    c_reverb_r70_d50_w100_impulse.bin
rvgen 0.9 0.2 1.0 impulse    c_reverb_r90_d20_w100_impulse.bin
rvgen 0.5 0.8 0.5 impulse    c_reverb_r50_d80_w50_impulse.bin
rvgen 0.7 0.5 1.0 square     c_reverb_r70_d50_w100_square.bin
rvgen 0.99 0.0 1.0 loud_train c_reverb_r99_d0_w100_loud_train.bin

# Only now that the goldens actually exist: a provenance file written before the generators ran
# would survive a later compile or generator failure and then describe bytes that were never
# produced — which is exactly what happened while testing this, leaving a provenance naming one
# commit beside goldens from another.
{
  echo "# Which C these c_reverb_*.bin goldens were generated from. Written by tools/ladder_harness/build.sh."
  echo "# Read by ReverbGoldenBufferTest so a failure says which C the bytes describe."
  echo "$FV_PROV_TEXT"
} > "$FV_PROV"

echo "done. Freeverb golden buffers written to $RVOUT (provenance: $FV_PROV)"
