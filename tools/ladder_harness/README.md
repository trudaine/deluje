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
