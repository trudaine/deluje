# Firmware Sync Audit — DelugeFirmware (last 3 weeks) vs. the Java port

**Date:** 2026-07-04 · **Upstream range:** ~55 commits from mid-June 2026 to 2026-07-04 on
`../DelugeFirmware` (community `main`).

This audit reviews recent Synthstrom **DelugeFirmware** C/C++ commits and checks, for each one that
could affect sound or behavior, whether the pure-Java port (`org.deluge`) already has it, needs it,
or is unaffected. The port's north star is **sound parity**, so hardware-only and C-implementation
bugs (OLED, encoders, memory-safety, host-sim/SIMD) are out of scope by design.

## Verdict

**The Java port is current — no fidelity gaps found.** The three sound-affecting fixes are already
ported, and the two most fidelity-critical DSP commits are already correct in Java by construction.
The only open items are minor UX behaviors with no bearing on sound parity.

---

## 1. Already ported ✅

Landed via `765579fd` ("Port community C++ firmware bug fixes"), verified present in the Java tree:

| Upstream | What | Java location |
| :--- | :--- | :--- |
| #4623 (`9456095b`) | TimeStretcher E078 OOB read on looping time-stretched samples | [`TimeStretcher.java`](../src/main/java/org/deluge/firmware2/TimeStretcher.java) — coarse-table position clamp |
| #4606 (`c0a037fa`) | Auditioning cut off sequenced notes | [`ClipGridPanel.java`](../src/main/java/org/deluge/ui/ClipGridPanel.java) — `auditionSounded` guard |
| #4558 (`c59e7580`) | Early clamp when loading `swingInterval` from song file | [`ProjectModel.java`](../src/main/java/org/deluge/model/ProjectModel.java), [`DelugeXmlUtil.java`](../src/main/java/org/deluge/xml/DelugeXmlUtil.java), [`SongXmlParser.java`](../src/main/java/org/deluge/xml/SongXmlParser.java) |

## 2. Already correct in Java ✅ (verified against code, no action needed)

- **Osc sync + unison stereo spread (#4532, `d86adc4c`).** The C fix makes the *stereo* source-render
  path apply osc sync (previously it passed `nullptr`, so osc B wasn't synced under stereo unison
  spread). Our [`Voice.renderInStereo`](../src/main/java/org/deluge/firmware2/Voice.java) already does
  this — `getPhaseIncrements = (s == 0) && doingOscSync`, `doOscSyncThisSource = (s == 1) &&
  doingOscSync`, capturing `oscSyncPhaseIncrement[u]`. Not buggy.
- **Fixed-point host fallbacks + FM sine OOB (#4531, `16de8295`).** These C bugs are **host-sim /
  SIMD-library specific and do not exist in Java**:
  - `signed_saturate` in [`Functions.java`](../src/main/java/org/deluge/firmware2/Functions.java) clamps
    to the correct SSAT range (`[-limit-1, limit]`), not the buggy `1<<bits` wrap.
  - `clz(0)`: Java uses `Integer.numberOfLeadingZeros(0)`, which already returns 32 (matching ARM `clz`).
  - FM sine table read: [`SineOsc.doFMVector4`](../src/main/java/org/deluge/firmware2/SineOsc.java) masks
    the table index with `& 0b0111111110` (max 510), so it cannot read out of the 512-entry table the
    way the C's argon `LoadGatherOffsetIndexInterleaved<2>` double-scaling did.

  Java's GC, bounds-checked arrays, its own `jdk.incubator.vector` gather, and correctly-written
  fixed-point ops immunize the port against this whole class of C bug.

## 3. Not applicable to a pure-Java Swing port

Roughly 40 commits are hardware- or C-implementation-specific with no sound/behavior effect on the
desktop app:

- **Display / hardware:** OLED DMA watchdog & low-level cleanup (`bfc9bc90`, `c02493ff`, `78451fb8`),
  CV OLED priority slot (`07a53208`), encoder overhaul + gold-knob acceleration (`e460593c`).
- **Physical MIDI/device UI:** device settings read/write + per-device relative toggle (`024449fd`),
  disconnected-device selection (`e3a44a44`), MIDI/GATE clock burst guard (`fb8cf806`).
- **Hardware-navigation UI:** performance-view stutter mode (`9268a9b4`), favourites/banks
  (`e22abd64`, `82e106dd`), browser forward-search (`e98cf69b`), macro bright/dim (`57a627c4`),
  section-repeat green mode (`da5274b8`), scale selection on keyboard layouts (`dd773f4f`).
- **C memory safety / tooling:** cppcheck fixes (`237a1ac8`), kit-copy & general memory safety
  (`622ab8e9`, `b79a2e38`, `063ec873`), dangling-pointer in `homogenizeRegion` (`dd911e86`),
  fatfs/uintptr_t/argon host-sim fixes (part of `16de8295`).
- **Cross-screen arranger from bar 1 (`0261dcc2`)** — the desktop app does not implement cross-screen
  editing (see the guidebook's §1.9 note), so N/A.
- Website/docs/CI/dependency bumps (many).

## 4. Optional, low-impact behavior items (not fidelity)

Worth a look only if we want closer *workflow* parity; none affect sound:

| Upstream | What | Notes |
| :--- | :--- | :--- |
| #4541 (`089a1d5b`) | Preserve custom knob mappings when swapping a wavetable osc's file | Minor UX; our wavetable-swap path is unverified for this. |
| #4587 (`f69525aa`) | Toggle the "fill" setting for *held* notes | Note-fill behavior in `song.cpp`. |
| #4540 / #4593 / #4615 | Tempo-automation undo, mod-encoder automation action, `homogenizeRegion` edit-drop | Our automation is a simpler `float[]` model, so these C `param_set`/`auto_param` structural fixes likely don't map. |

## Method

For each upstream commit touching `src/deluge/{dsp,model/voice,model/song,processing,modulation}`,
the corresponding Java subsystem was read and compared. Hardware/UI paths (`gui/`, `hid/`, `display/`)
were treated as out of scope. "Already correct" entries were verified by reading the Java source, not
inferred from commit messages.
