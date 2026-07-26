# Ladder golden-buffer harness

A standalone desktop harness that compiles the **real** Deluge firmware
`lpladder.cpp` (+ the real `lookuptables.cpp`) with system `g++` and emits
per-sample golden buffers, so the Java `org.deluge.firmware2.LpLadderFilter`
port can be **bit-diffed sample-exact** against the C — offline, no hardware in
the loop.

This is the "standalone C golden-buffer harness" the fidelity docs kept asking
for (see `docs/FIDELITY_GAP_ANALYSIS.md`). It is the *faithful* instrument: it
reuses the firmware's own DSP and tables verbatim, so no re-implementation and
no hand-transcribed lookup table can drift.

## What is / isn't real C

- **Real, linked from the firmware:** `lpladder.cpp`, `filter.h` (curveFrequency,
  filterMono blend), `lookuptables.cpp` (`tanHSmall`, `tanTable`), and all the
  inline math in `functions.h`/`fixedpoint.h`/`waves.h`.
- **Stubbed in `support.cpp`:** `AudioEngine::cpuDireness` (=0, the normal
  non-CPU-starved path), the `blendBuffer` scratch array, the CONG PRNG globals
  (`jcong` seeded to 380116160 to match `Functions.resetNoiseSeed()`), and
  verbatim copies of two tiny non-inline functions (`instantTan`, `quickLog`,
  each cited to `functions.cpp`).

## Two gotchas the harness has to honor (both are real port lessons)

1. **`dryFade` must start at 0, not 1.** A directly-constructed `Filter` keeps
   the member initializer `dryFade = 1`, which engages the dry→wet blend fade. In
   the firmware the filter lives in a `FilterSet` whose memory is **zeroed**
   before use (so `dryFade = 0`, direct path). `main.cpp` `memset`s the filter to
   0 to replicate that. The Java port already uses `dryFade = 0.0f`, so this
   only bit the harness.
2. **The ladder calls `getNoise()` (CONG) every sample** to dither moveability,
   so bit-exactness requires the same PRNG seed on both sides — hence the shared
   380116160 seed and `Functions.resetNoiseSeed()` in the test.

## Run

```bash
FW=/path/to/DelugeFirmware tools/ladder_harness/build.sh   # regenerate goldens
mvn test -Pslow-tests -Dtest=LadderGoldenBufferTest        # bit-diff Java vs C
```

`FW` defaults to `../DelugeFirmware`. Golden buffers are committed under
`src/test/resources/fidelity/ladder/` (2 KB each); the test reads them from the
classpath and needs no C compiler.

## Result (2026-07-06)

All 9 cases — 12dB / 24dB / drive ladder modes, across cutoff/resonance points
including the high-resonance self-oscillation regime — match the C firmware
**bit-exact** (`maxAbsDiff = 0`). The Java ladder is sample-identical to the C.

## HP ladder sibling (`main_hp.cpp`, 2026-07-26)

`build.sh` also builds an HP-ladder harness (`main_hp.cpp` → `hpladder.cpp`) and
`HpLadderGoldenBufferTest` bit-diffs the Java `HpLadderFilter`. The HP ladder is
even simpler to harness than the LP: it touches neither `AudioEngine::cpuDireness`
nor `getNoise()`, so the C output is fully deterministic (no PRNG seeding).

This immediately found a **real port bug**: Java initialized
`HpLadderState.hpfLastWorkingValue` to `0x80000000` (and re-set it every note in
`reset()`), but the C `HPLadderState::reset()` never touches it — the FilterSet
zeroes filter memory, so C starts it at **0**. Because HP resonance is almost
always > 900M (966M even at res 300M → the antialiasing `getTanHAntialiased` path
is nearly always active), the wrong initial `lastWorkingValue` corrupted the onset
transient of essentially every HP-filtered note. Fixed to init `0` and to not
re-set in `reset()`; the harness confirms all 5 HP cases now `maxAbsDiff = 0`.

Note: this fix is **scorecard-neutral** — the ALLSYN scorecard corpus runs the HP
ladder inert (all 188 instruments carry `hpfMode "12dB"`, which loads as an inert
high-pass; see `docs/FIDELITY_GAP_ANALYSIS.md` §4.2nonies), so the corpus never
activates the HP ladder. It is a faithful bit-exact fix that matters for any real
song with an *active* HP ladder filter.

## SVF sibling (`main_svf.cpp`, 2026-07-26)

`build.sh` also builds an SVF harness (`main_svf.cpp` → `svf.cpp`) and
`SvfGoldenBufferTest` bit-diffs the Java `SVFilter` across SVF_BAND / SVF_NOTCH modes
and morph values. Like the HP ladder, the SVF is pure integer math (getTanHUnknown +
fixed-point multiplies) with no `cpuDireness`, no `getNoise()`, and no float — fully
deterministic. **Result: all 5 cases `maxAbsDiff = 0`** — the Java SVF is bit-exact to
the C, upgrading the earlier behavioral `SvfParityTest` to a bit-exact guarantee (no
bug found here, unlike the HP ladder).

## Freeverb reverb (`main_reverb.cpp`, 2026-07-26)

`build.sh` also builds a Freeverb harness (`main_reverb.cpp` → `freeverb.cpp`) and
`ReverbGoldenBufferTest` bit-diffs the Java `Freeverb` (the FREEVERB reverb model).
The freeverb per-sample path is pure integer (comb/allpass `multiply_32x32_rshift32_rounded`)
and its setup uses only deterministic float arithmetic (`*`,`/`,`-`, no transcendentals),
with both sides using `(float)INT32_MAX == 2147483648.0f` — so it's a valid bit-exact
target (unlike the compressor, whose audio path depends on libm exp/log).

**Result: all 4 cases `maxAbsDiff = 0`** (4096-sample buffers — the comb delays are
~1116-1617 samples, so shorter windows are all-zero before any tail emerges).

Two harness lessons (both documented in `docs/FIDELITY_GAP_ANALYSIS.md`):
1. `ProcessOne` mixes via `getPanLeft()/getPanRight()` (base-`Reverb` pan, default 0) —
   the harness must `setPanLevels()` on both sides or the output is all zero.
2. **Do not use a `sin`-generated test input for a cross-JVM/libm bit-diff:** Java
   `Math.sin` is not bit-identical to C/libm `sin` at the last ULP, and near sine peaks
   `AMP*sin` cast to int flips by 1 LSB — which under sustained drive tips a reverb
   accumulator over an overflow boundary and diverges. Use a pure-integer signal. Also
   keep sustained-signal amplitude in range: full-scale sustained drive overflows the
   integer comb accumulators, where C signed-overflow (UB) and Java defined-wrap
   legitimately differ (not a port bug; outside realistic reverb send levels).
