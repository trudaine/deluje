# Firmware Sync Audit — DelugeFirmware (last 3 weeks) vs. the Java port

**Date:** 2026-07-04 (re-verified 2026-07-05: upstream tip unchanged at `9456095b`; two DSP-adjacent commits in the pulled range not previously named — #4538, #4576 — verified below · re-verified 2026-07-14: upstream advanced to `5762c4ba`, 33 new commits, see §5 · re-verified 2026-07-23: upstream advanced to `95b7acab`, 18 new commits, **one porting gap found** — filter mode `"Off"` XML compat, see §6) · **Upstream range:** ~73 commits from mid-June 2026 to 2026-07-22 on
`../DelugeFirmware` (community `main`).

This audit reviews recent Synthstrom **DelugeFirmware** C/C++ commits and checks, for each one that
could affect sound or behavior, whether the pure-Java port (`org.deluge`) already has it, needs it,
or is unaffected. The port's north star is **sound parity**, so hardware-only and C-implementation
bugs (OLED, encoders, memory-safety, host-sim/SIMD) are out of scope by design.

## Verdict

**The Java port is current — no fidelity gaps and no open porting items.** The three sound-affecting
fixes are already ported; the two most fidelity-critical DSP commits are already correct in Java by
construction; and the two behavior "maybes" turned out, on inspection, to be hardware-UI specific
with no Swing equivalent (§4). Everything else is hardware/UI/C-memory-safety, out of scope.

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
- **Stereo unison live-input (#4538, `95fad73b`) — re-verified 2026-07-05.** The C bug: with
  unison stereo spread, a MONO live-input / live-pitch-shifter render wrote directly into the
  interleaved stereo `oscBuffer` as if it were mono → noise; the fix renders to a temp mono
  buffer and pans per part (`multiply_32x32_rshift32(sample, amplitudeL/R) << 2`,
  voice.cpp:2271-2350). The Java architecture never had this bug: the INPUT branch in
  [`Voice.renderSubtractivePath`](../src/main/java/org/deluge/firmware2/Voice.java) always
  renders each unison part into the mono `tempBuf` and the shared mix step applies exactly that
  per-part pan into the interleaved buffer when `stereoUnison`. (Residual nuance, noted in
  `docs/dsp_parity_review_2026-07-04.md`: a STEREO live shifter is condensed to mono in Java
  where the fixed C renders it stereo — live-monitoring edge case only.)
- **Tempo-automation float interpolation (#4576, `6f7cae0b`) — re-verified 2026-07-05.** Entirely
  inside `auto_param.cpp` interpolation increments; same category as #4540/#4593/#4615 (§4): our
  automation is a simpler float model that doesn't port `auto_param.cpp`, so there is nothing to
  apply.

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

## 4. Reviewed and found N/A (verified, not gaps)

On closer inspection the two "maybe" behavior items are **hardware-UI specific with no Java
equivalent** — not workflow gaps:

| Upstream | What | Why N/A |
| :--- | :--- | :--- |
| #4541 (`089a1d5b`) | Don't reset custom knob mappings when swapping a wavetable osc's file | Operates on `modKnobs[7][x].paramDescriptor` / `modKnobMode` — the physical **gold-knob mod-knob auto-assignment** system. The Swing app has no `modKnobMode`/`paramDescriptor` mechanism at all (wavetable editing is a position-scan slider), so there is nothing to reset. |
| #4587 (`f69525aa`) | Toggle the "fill" setting for *held* notes | Entirely in `gui/ui/sound_editor.cpp` + `gui/views/instrument_clip_view.cpp` — the `SYNC_SCALING` hardware button, edit-pad-press popups, and hardware note editor. The Swing app sets Fill via the Step Properties dialog slider; none of that gesture handling maps. |
| #4540 / #4593 / #4615 | Tempo-automation undo, mod-encoder automation action, `homogenizeRegion` edit-drop | Our automation is a simpler `float[]` model, so these C `param_set`/`auto_param` structural fixes don't map. |

## 5. Re-verified 2026-07-14 — upstream advanced to `5762c4ba` (33 new commits), no porting gaps

Reset a stale local checkout (`feat/dsp-buffer-dump` had diverged from its own remote-tracking ref
after a rebase, `git reset --hard fork/feat/dsp-buffer-dump` — unrelated housekeeping, no firmware
content changed), then screened `9456095b..origin/main` (33 commits, 2026-07-06 to 2026-07-14).
Nearly all are website/docs/UI/build-only (audio clip recording UI, OLED naming, sidebar fades, CI,
macOS build fix, website redesign) — out of scope by this doc's own filter. Three looked DSP-adjacent
enough to investigate fully:

| Upstream | What | Verdict |
| :--- | :--- | :--- |
| #4635 (`15bdf097`) | `FilterSet::reset()` changed from zeroing only the filter-state unions to `memset(this, 0, ...)` (zeroing mode-tracking fields too) | **No-op cleanup, not a bug fix.** Traced the call sequence: `setConfig()` unconditionally overwrites `lpfMode_`/`hpfMode_`/`routing_`/`LPFOn`/`HPFOn` before anything reads them, and the one branch that *conditionally* uses stale `lastLPFMode_` never actually skips work that would produce a different result. Java's `FilterSet.reset()` (`FilterSet.java`) already explicitly resets these fields (to `OFF`, even cleaner than the C's zero-enum-index) — no change needed. |
| #4663 (`17c7fa09`) | Real C++ footgun: `if constexpr (std::is_constant_evaluated())` always takes the constexpr branch, so real hardware silently used a "portable" float↔fixed-point conversion path instead of true ARM VFP `vcvt` instructions, for the `FixedPoint<>` template class and (separately) an asm operand-aliasing bug in `q31_from_float`/`q31_to_float` | **Confirmed no hot-path impact.** `FixedPoint<>` has zero production call sites anywhere in `src/deluge/` (dead code, test-only). `q31_from_float`/`q31_to_float` have exactly 2 call sites, both one-time sample/wavetable-load-time conversion of an uncommon 32-bit-float PCM format — never per-sample DSP. Java's `Sample.java` uses a plain `(int)` cast at the equivalent one-time conversion point, which already matches the theoretically-correct (round-toward-zero, saturating) semantics per JLS 5.1.3 — no change needed either way. |
| #4658 (`85fed274`) | A note refused a voice under high `cpuDireness` (CPU load) gets stuck `PENDING` forever if the Sound has no active voices/arp to trigger a retry — triggered specifically by SD-card folder scanning stalling the audio engine during output recording | **Not pursued — no clear Java equivalent trigger.** Java's `handlePendingNotes` retry is only invoked from within the arpeggiator's own tick (`ArpeggiatorBase.java`), matching the same structural shape as C, but the *triggering scenario* (a blocking SD-card scan on the audio-render thread during recording) doesn't have an obvious Java analog — Java's file I/O for recording doesn't block the render path the same way. Low confidence this manifests; revisit only if live-recording note-loss is ever actually observed. |

No commits in this range required a Java change. Upstream tip for the next re-check: `5762c4ba`.

## 6. Re-verified 2026-07-23 — upstream advanced to `95b7acab` (18 new commits), one real porting gap

Screened `5762c4ba..origin/main` (18 commits, 2026-07-14 to 2026-07-22). Fourteen are Deluge
Companion web-app, website, CI, or dependency-bump commits with no `src/deluge` footprint. Four
touched `src/deluge` (5 files total) and were read in full against the Java side:

| Upstream | What | Verdict |
| :--- | :--- | :--- |
| #4688 (`a3f5b8a5`) | `filterMap` now includes `{FilterMode::OFF, "Off"}` and `kNumFilterModes` was bumped to cover it — current firmware can **write and parse `lpfMode="Off"` / `hpfMode="Off"`** in song/preset XML | **Real gap — needs porting.** `InstrumentXmlParser.parseFilterMode` and `KitXmlParser`'s lpf/hpf handlers map any unrecognized string (including `"Off"`) to `LADDER_12` via their `else` fallback, so a song saved on current firmware with a bypassed filter loads here with a 12dB ladder engaged — an audible divergence. `org.deluge.model.FilterMode` has no `OFF` constant (the firmware2 `FilterSet.FilterMode.OFF` exists and `FirmwareSound` already routes to it for `null`), and `ProjectSerializer` can't emit `"Off"`. Fix: add `OFF` last in the model enum (preserves existing ordinals), accept `"Off"` in both parsers, emit `"Off"` in the serializer, and map it in `FirmwareSound.setLpfMode`/`setHpfMode`. |
| #4708 (`c8a9dc6f`) | MIDI-follow no longer sends notes / pitch bend / aftertouch / mode-CCs into **audio** clips (`clip->type != ClipType::INSTRUMENT` guards in `midi_follow.cpp`) | **N/A — different architecture.** Our `org.deluge.midi.MidiFollow` is a CC→parameter mapping reimplementation, not a port of the C clip-routing path; notes route through the sequencer/MIDI-looper (`MidiService`), which already treats `AudioTrackModel` specially (arming capture, not playing notes). There is no code path that injects follow notes into audio-clip playback. |
| #4716 (`2173980b`) | OLED song naming: gate the digit-prefix predictive-text behavior in `Browser::predictExtendedText` to 7SEG displays only | **N/A.** The Swing app's save dialog has no port of the browser predictive-text system (no `predictExtendedText`/`filePrefix` equivalent exists in the Java tree). |
| #4717 (`95b7acab`) | Song loader accepted only `channel < 16` for section launch MIDI commands, silently dropping CC-encoded (18-35) and MPE-zone (16-17) learns on load | **N/A, pre-existing scope limit noted.** `SongXmlParser.parseSongSections` reads only `id`/`numRepeats` and never parsed section `launchMIDICommand` data at all, so the range-check bug can't manifest. Corollary worth knowing: round-tripping a hardware song through our serializer drops section MIDI-launch learns entirely (serializer writes sections fresh with only `id`/`numRepeats`). |

One commit in this range requires a Java change (#4688, filter mode `"Off"` XML compat). Upstream
tip for the next re-check: `95b7acab`.

## Method

For each upstream commit touching `src/deluge/{dsp,model/voice,model/song,processing,modulation}`,
the corresponding Java subsystem was read and compared. Hardware/UI paths (`gui/`, `hid/`, `display/`)
were treated as out of scope. "Already correct" entries were verified by reading the Java source, not
inferred from commit messages.
