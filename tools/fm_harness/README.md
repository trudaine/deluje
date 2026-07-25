# FM operator-kernel golden-buffer harness

Compiles the **real** Deluge firmware `fm_op_kernel.cpp` (+ `math_lut.cpp`) with
system `g++` and emits per-sample golden buffers, so the Java
`org.deluge.firmware2.FmCore` operator kernel (`computeNormal` / `computePure` /
`computeFb`) can be **bit-diffed sample-exact** against the C — offline.

Same pattern as `tools/ladder_harness/` (see its README). The FM op kernel is the
FM **sideband generator**: pure phase-accumulate + `Sin::lookup` + gain, with a
feedback recurrence. It is the exact place a "too-bright FM" divergence would
live, so a bit-exact result there rules the kernel out.

## What is / isn't real C

- **Real, linked from the firmware:** `fm_op_kernel.cpp` (scalar path), and
  `math_lut.cpp` which fills the real `sintab` (SIN_DELTA interpolated table) via
  `dx_init_lut_data()`. So `Sin::lookup` runs against the firmware's own table.
- **Stubbed in `support_fm.cpp`:** the `dxEngine` global (we skip `engine.cpp` /
  the voice allocator and just point `dxEngine` at zeroed storage whose table
  arrays `dx_init_lut_data` fills), and the ARM `neon_fm_kernel` asm symbol —
  never executed, because the harness always calls with `neon = false`; it only
  satisfies the linker (and `abort()`s if ever hit).

## Notes / gotchas

- `-include cstdint` on the g++ line: `aligned_buf.h` uses `intptr_t` and relies
  on the ARM toolchain to pull in `<cstdint>` transitively.
- No PRNG and no runtime state beyond the op params, so — unlike the ladder — no
  seed coordination is needed; the goldens are fully deterministic.
- The Java compute methods are `private static`; the test invokes them by
  reflection. Params in `FmKernelGoldenBufferTest` must mirror `main_fm.cpp`.

## Run

```bash
FW=/path/to/DelugeFirmware tools/fm_harness/build.sh    # regenerate goldens
mvn test -Pslow-tests -Dtest=FmKernelGoldenBufferTest   # bit-diff Java vs C
```

## Result (2026-07-06)

All 3 operator modes — feedback (`compute_fb`), pure carrier (`compute_pure`),
and modulated (`compute`) — match the C firmware **bit-exact** (`maxAbsDiff = 0`).
The FM operator kernel and `Sin::lookup` are sample-identical to the C, so the
"too-bright FM" scorecard gap is **not** in the kernel — it is upstream in the
FM voice layers (envelope level→gain via `exp2Lookup`, algorithm routing,
operator ratios, or pitch).
