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
  # The commit whose closure CONTENT was audited and is captured by FV_EXPECTED_DIGEST below. It is
  # not $FV_ARGON_COMMIT: the pinned build takes only five files from the pin and everything else
  # from HEAD, so what was actually audited is HEAD's closure at the time of the audit. Keeping this
  # at the argon commit made the failure hint list e223d19d5 (#5296, which touched
  # definitions_cxx.hpp in the closure) as unaudited, contradicting a digest that already included
  # it. Move this and FV_EXPECTED_DIGEST together, never apart.
  FV_AUDITED_THROUGH="03e4beb21"
  FV_DIR="$BUILD/freeverb-pinned"

  # WHAT THE GUARD CHECKS, and why it is a content digest rather than a list of paths.
  #
  # The pinned build is a MIX: -I"$FV_DIR" shadows only the five copied freeverb files, while
  # everything else they include resolves from $FW at HEAD, and lookuptables.cpp is compiled at HEAD
  # and linked in too. A hand-written list of "the headers that matter" was tried here and was wrong
  # by its own criterion: the real include closure is 38 firmware files, and naming three of them
  # left dsp/stereo_sample.h unwatched - the file defining the q31_t / StereoSample fields the
  # pinned Freeverb::ProcessOne writes into, and one upstream has edited more than once. A path list
  # also cannot see an UNCOMMITTED edit in $FW, which matters because CLAUDE.md has firmware changes
  # made on local fork branches: a working-tree probe in fixedpoint.h would bake itself into the
  # goldens while git log reported nothing at all.
  #
  # So: derive the closure with `g++ -M` and hash the CONTENT of every file in it that comes from
  # $FW. Closure-correct, needs no hand-maintaining, and reads the filesystem, so it catches
  # uncommitted edits. Hashing the preprocessed output would also work but would change on a g++ or
  # libstdc++ upgrade, since that pulls standard headers into the text; hashing only the firmware's
  # own files keeps the digest about the firmware.
  # No 2>/dev/null and no `|| true` anywhere in here: a g++ -M that fails must abort the run under
  # `set -euo pipefail`, not quietly degrade the closure to whatever still resolved. Swallowing it
  # is precisely how the first version of this guard passed while covering none of freeverb's
  # headers.
  # The five pinned files have to be hashed EXPLICITLY, from $FW, and leaving them out is the one
  # mistake this guard cannot afford. They resolve under $FV_DIR in the closure above (that is the
  # point of -I"$FV_DIR"), so `grep "^$FW/"` drops every one of them, and the first version of this
  # digest therefore passed with HEAD's freeverb.cpp AND comb.hpp both edited — the exact scenario
  # the guard exists for, and one the path list it replaced did cover. Hash $FW's copies so any
  # upstream change to them trips the gate, whether committed or not.
  fv_pinned_at_head() {
    for f in freeverb.cpp freeverb.hpp comb.hpp allpass.hpp tuning.h; do
      echo "$FW/src/deluge/dsp/reverb/freeverb/$f"
    done
  }
  fv_build_digest() {
    {
      {
        g++ $STD -fwrapv ${FV_INC[@]+"${FV_INC[@]}"} "${INC[@]}" -M "$FV_SRC/freeverb.cpp"
        g++ $STD "${INC[@]}" -M "$FW/src/deluge/util/lookuptables/lookuptables.cpp"
      } | tr ' \\' '\n\n' | grep "^$FW/"
      fv_pinned_at_head
    } | sort -u | xargs cat | sha256sum | cut -d' ' -f1
  }
  # The digest of those inputs as they stood when the goldens were audited. Update this ONLY
  # together with FV_AUDITED_THROUGH, after reading what changed against the Java Freeverb.
  FV_EXPECTED_DIGEST="21d3e986f899af2ebf20e04dfe15106139168c3eb2c30d74581a2b1bddb39e88"

  # Both revs must resolve, not just the frontier: in a clone grafted at $FV_ARGON_COMMIT the
  # frontier exists while its PARENT - the pin we actually build from - does not, and the run would
  # then die at the bare `git show "$FV_PIN:..."` below with git's own "invalid object name" rather
  # than the explanation written for exactly this case.
  # $FV_PIN must resolve: it is what we build from, and there is no way to proceed without it.
  if ! git -C "$FW" rev-parse --verify -q "$FV_PIN^{commit}" >/dev/null; then
    echo "error: cannot resolve the freeverb pin $FV_PIN in $FW." >&2
    echo "       That commit predates the argon include, so a checkout without it is either shallow" >&2
    echo "       or has had its history rewritten. Nothing can be regenerated from the pin here." >&2
    exit 1
  fi
  # $FV_AUDITED_THROUGH is only used for the failure hint, and it is an upstream HEAD from audit
  # time, so a $FW that simply PREDATES it — or a fork branch cut before it, which CLAUDE.md makes
  # the normal state for local firmware work — is perfectly ordinary and must not abort the run.
  # The digest below is the gate and does not need it.
  if ! git -C "$FW" rev-parse --verify -q "$FV_AUDITED_THROUGH^{commit}" >/dev/null; then
    echo "note: audit frontier $FV_AUDITED_THROUGH is not in $FW (an older or forked checkout)." >&2
    echo "      The digest gate still applies; only the failure hint will be less specific." >&2
    FV_AUDITED_THROUGH=""
  fi

  echo "note: freeverb.cpp at C HEAD needs argon/NEON; pinning the freeverb harness to $FV_PIN" >&2
  mkdir -p "$FV_DIR/dsp/reverb/freeverb"
  for f in freeverb.cpp freeverb.hpp comb.hpp allpass.hpp tuning.h; do
    git -C "$FW" show "$FV_PIN:src/deluge/dsp/reverb/freeverb/$f" > "$FV_DIR/dsp/reverb/freeverb/$f"
  done
  FV_SRC="$FV_DIR/dsp/reverb/freeverb"
  FV_INC=(-I"$FV_DIR")

  # The digest gate runs HERE, not before the copy above, and the ordering is load-bearing: the
  # closure has to be taken from the PINNED translation unit with $FV_SRC and $FV_INC pointing at
  # it. Computed any earlier, `g++ -M` runs on HEAD's freeverb.cpp, dies on the missing argon.hpp,
  # and yields a closure covering only lookuptables - which is how the first version of this guard
  # silently failed to notice an edited dsp/stereo_sample.h. Still ahead of every freeverb golden,
  # which is what the gate has to be ahead of.
  FV_DIGEST=$(fv_build_digest)
  if [ "$FV_DIGEST" != "$FV_EXPECTED_DIGEST" ]; then
    echo "error: the freeverb build inputs have changed since they were audited." >&2
    echo "       expected $FV_EXPECTED_DIGEST" >&2
    echo "       actual   $FV_DIGEST" >&2
    echo "       (content of every \$FW file in the include closure, so this covers uncommitted edits)" >&2
    # Non-authoritative hint; the digest is the gate. Lists COMMITTED changes in the closure since
    # the frontier - an uncommitted edit shows nothing here, which is itself the answer.
    # Same set the digest hashes, pinned files included — without them this listing could never
    # name a commit that touched src/deluge/dsp/reverb/freeverb/, and would print "nothing
    # committed in the closure" with a freeverb arithmetic change sitting at HEAD.
    FV_CLOSURE=$({
      {
        g++ $STD -fwrapv ${FV_INC[@]+"${FV_INC[@]}"} "${INC[@]}" -M "$FV_SRC/freeverb.cpp"
        g++ $STD "${INC[@]}" -M "$FW/src/deluge/util/lookuptables/lookuptables.cpp"
      } | tr ' \\' '\n\n' | grep "^$FW/"
      fv_pinned_at_head
    } | sed "s|^$FW/||" | sort -u)
    if [ -n "$FV_AUDITED_THROUGH" ]; then
      FV_SINCE=$( (cd "$FW" && git log --oneline "$FV_AUDITED_THROUGH..HEAD" -- $FV_CLOSURE) || true)
      if [ -n "$FV_SINCE" ]; then
        echo "       committed changes in the closure since $FV_AUDITED_THROUGH:" >&2
        echo "$FV_SINCE" | sed 's/^/         /' >&2
      else
        echo "       nothing committed in the closure since $FV_AUDITED_THROUGH - check git status in $FW" >&2
      fi
    else
      echo "       (no audit frontier in this checkout, so no commit listing - check git status in $FW)" >&2
    fi
    cat >&2 <<EOF
Regenerating from the pin ($FV_PIN) now would bake those changes into the goldens while the
provenance still claimed the audited state. Read them against the Java Freeverb. If they are
arithmetic-preserving, update FV_AUDITED_THROUGH *and* FV_EXPECTED_DIGEST in this script together.
Do NOT advance FV_PIN - see the comment there, it cannot move.
Better: put SIMDe on the include path and drop the pin entirely, which is how the firmware's own
host tests build this file - see issue #176 and docs/firmware_sync_audit.md 12c.
EOF
    exit 1
  fi

  FV_PROV_TEXT="source=pinned
commit=$(git -C "$FW" rev-parse --short=9 "$FV_PIN")
reason=freeverb.cpp at C HEAD includes <argon.hpp> (NEON/MVE only) and does not compile on desktop g++.
audited_through=$FV_AUDITED_THROUGH ($(git -C "$FW" log -1 --format=%s "$FV_AUDITED_THROUGH")) - the include closure's content at this commit was audited, and is what the digest below captures; see docs/firmware_sync_audit.md 12c
build_inputs_digest=$FV_DIGEST (sha256 of every firmware file in the include closure)
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
# Mark the set as mid-regeneration BEFORE writing a single golden, and replace it with the real
# provenance only once all five succeed. "Write it only after the goldens exist" closes the hazard
# only for all-or-nothing failure: if rvgen dies on, say, the third of the five cases, `set -e`
# exits leaving two fresh goldens, three stale ones, and the OLD provenance still on disk,
# describing bytes that are only partly there. Deleting it instead would overload "absent", which
# already means "generated before this script recorded provenance" - a harmless case. An explicit
# marker keeps the two apart, so the test can say which it is.
cat > "$FV_PROV" <<EOF
# Written by tools/ladder_harness/build.sh at the START of a freeverb regeneration.
source=incomplete
note=A regeneration began and did not finish, so these c_reverb_*.bin are a MIX of fresh and stale
note=bytes and must not be trusted. Re-run tools/ladder_harness/build.sh.
EOF
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
