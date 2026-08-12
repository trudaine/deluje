# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone, pure-Java emulation of the Synthstrom Audible **Deluge** synthesizer/sequencer
workstation: a Swing grid UI on top of a DSP engine that is a port of the real Deluge C++
community firmware. The overriding goal is **sound parity with the C firmware** — DSP changes are
validated against real hardware recordings, not just "sounds fine".

## ABSOLUTE RULE: `org.deluge.firmware2` is a faithful C→Java port

`org.deluge.firmware2` is a **line-for-line translation** of the Deluge C firmware at
`../DelugeFirmware/src/deluge/` (the C reference lives next to this repo). When working in this
package:

- **Translate the C — do not reconstruct, paraphrase, approximate, or hack.** If the C uses
  fixed-point/lookup tables, the Java uses the same. A failing test means a missing/incorrect C
  subsystem — port it; never add a bypass or fall back to an approximation.
- **Every firmware2 edit cites the C `file:line` it ports.** Before writing code, open the exact
  C function, read it, mirror its structure.
- **The master/gain stage in `engine/FirmwareAudioEngine` is non-faithful per-stage but nets sane
  output.** Since 2026-07-25 the whole voice chain up to and through the nonlinear stages (ladder
  drive, per-voice saturate, master compressor input) runs at C-exact levels — the oscillator
  amplitude application was fixed from a mis-derived `>>30` to the C's net `(amp*val)>>32`
  (vqdmulh + halved amplitude vector), with the sample and DX7 paths brought down by the same 4×.
  The entire compensation now lives in ONE final linear stage: `lshiftAndSaturate(…, 6)` at the
  master output where the C does `>>1` (plus master-volume applied both pre-mix and in the
  compressor). Tests that bypass the engine master (golden signatures, kit-drum ratio) carry `/4`
  rebaselines. Any further re-derivation stays scorecard-gated.

See `docs/FIRMWARE2_FAITHFUL_PORT.md` (port protocol + numeric-type mapping) and
`docs/FIRMWARE2_PORT_ROADMAP.md`.

### How to actually find parity bugs (hard-won — read before auditing faithfulness)

For a long time repeated audits concluded "faithful, no gaps" while ~15 real translation bugs sat
in the DSP (signed-vs-unsigned shifts, an off-by-one table, dropped sync branches, an un-zeroed
buffer, inverted delay sync). They were always there and always findable — the audits used a
method that structurally could not find them. The lessons:

- **Audit Java→C, line by line, quoting both sides — not C-delta→Java.** A "what changed upstream
  recently, do we have it?" audit (e.g. `docs/firmware_sync_audit.md`) **cannot** find a bug baked
  into the port from day one; those bugs aren't in the recent-commits set. The only method that
  works is: start from the Java DSP line, open the exact C function it claims to port, and read
  both texts side by side. That is the *only* way `>>` vs `>>>` on a `uint32_t`, a table one entry
  short, or a `goto` that lands after a doubling become visible. It is expensive; do it anyway.
- **Proxies do not prove faithfulness.** A passing scorecard median (amplitude-invariant,
  time-resolved cosine — a preset can sit at 0.80 with a real bug masked by other spectral energy),
  green tests (only prove the tested path), and green commit messages are all evidence of *absence
  of a detected problem*, never evidence of correctness.
- **The "line-for-line port" assertion above is a claim to verify, not a prior to trust.** Treating
  it as "probably faithful unless proven otherwise" is confirmation bias; audit adversarially,
  hunting for divergence.
- **Scope your claims honestly.** "I read these lines against the C and they match at the bit level"
  is a trustworthy, *scoped* statement. "No gaps / it's faithful" is a universal claim no delta
  audit or scorecard number entitles you to. Everything you did not put side by side with the C is
  **unaudited**, not presumed-correct. (Corollary: past "resolved/faithful" verdicts here — e.g.
  the FM "too-bright" write-off — turned out to mask real C-divergences; re-verify, don't inherit.)

See `docs/dsp_parity_review_2026-07-04.md` for the review that used this method and what it found.

## Build, test, run

Requires **JDK 27 early-access** and **Maven 3.9+**. The codebase uses preview features
(`--enable-preview`) and the incubating Vector API (`jdk.incubator.vector`); these flags are
already wired into the compiler, surefire, and `run.sh`, but any manual `java`/`javac`
invocation needs them too.

A Maven Wrapper is committed (`./mvnw` / `mvnw.cmd`, pinned to Maven 3.9.16) — use it for a
reproducible build without a system Maven; everything below works with `./mvnw` in place of `mvn`.
For a fully self-contained build, `./build.sh [goals]` (or `build.bat`) provisions **JDK 27-ea**
per-machine (downloads from Adoptium into `./jdk27` if absent, via `scripts/ensure-jdk27.sh`) and
then runs `./mvnw` — no global JDK/Maven installs needed. `run.sh`/`run.bat` share the same JDK
provisioning. (The wrapper itself only bootstraps Maven, never the JDK — that's what `build.sh` adds.)

```bash
mvn clean package                       # compile + run tests + build shaded jar
mvn test -B                             # tests only
mvn test -Dtest=Dx7ParityTest          # single test class
mvn test -Dtest=Dx7ParityTest#someCase # single test method
mvn spotless:apply                      # auto-format (google-java-format, GOOGLE style)
mvn spotless:check                      # verify formatting

# Run the workstation UI
mvn exec:java -Dexec.mainClass="org.deluge.ui.SwingDelugeApp"
mvn clean package -Pswing-dist          # build self-contained target/deluge-swing.jar
./run.sh                                 # launch the fat jar (downloads JDK 27 if missing)
```

- Main class: `org.deluge.ui.SwingDelugeApp`.
- Surefire runs with `-Dchuck.audio.dummy=true` (no real audio device in CI) and **excludes the
  `slow` JUnit tag** — long hardware-fidelity tests (e.g. `PhysicalHardwareFidelityTest`) only
  run when you target them explicitly.
- Pass `-Ddeluge.card=/path/to/card` to point at a virtual SD card directory.

## Architecture

Audio data flows **model → firmware2 → engine → JavaAudioDriver**, with the Swing UI driving the
model and reading back state. Despite mentions of ChucK in older design docs, the engine is now
**100% pure Java and fully decoupled from ChucK**.

- **`org.deluge.model`** — the in-memory song/project data model (`ProjectModel`, `Clip`,
  `NoteRowModel`, `OscillatorConfig`, `FilterConfig`, `LfoModel`, `ArpModel`, etc.). This is what
  the UI edits and what serializes to/from Deluge XML.
- **`org.deluge.firmware2`** — the DSP core: a faithful Java port of the Deluge C++ firmware
  (`Oscillator`, `Filter`/`LpLadderFilter`/`HpLadderFilter`, `Envelope`, `Lfo`, `Arpeggiator`,
  `Dx7Voice`/`FmCore`, `Delay`, `Freeverb`/`Reverb`, `Compressor`, `Patcher`/`PatchSource`,
  lookup tables). Fixed-point arithmetic and saturation behavior here are deliberately matched to
  hardware — see `HARDWARE_FIDELITY.md` before touching anything in this package.
- **`org.deluge.engine`** — runtime glue. `FirmwareAudioEngine` renders the active sounds;
  `PureFirmwareEngine` coordinates the audio engine + sequencer/clock and pushes song-level param
  changes into the DSP; `JavaAudioDriver` is the output line; `SequencerClock`/`TickEventQueue`
  handle timing.
- **`org.deluge.ui`** — the Swing workstation (grid, OLED emulator, knobs, views). Largest
  package; the 16×8 grid mirrors the physical pad layout with Clip / Song / Arranger views.
- **`org.deluge.midi`, `hid`, `ableton`** — MIDI/SysEx integration, hardware-input handling, and
  Ableton-style routing.
- **`org.deluge.BridgeContract`** — the contract mapping UI gold-knob/parameter changes onto
  engine parameters; `BridgeContractTest` guards it.

## Fidelity scorecard — the objective gate for any DSP change

`FidelityScorecardTest` is the project's fidelity gate. It renders ~190 SD card synth presets
through our engine (one C4) and compares a normalized log-magnitude spectrum against the real
hardware recordings, reporting per-synth + summary cosine similarity (1.0 = identical timbre),
both single-window and **time-resolved** (the headline metric).

```bash
mvn test -Dtest=FidelityScorecardTest -Dgpg.skip=true
```

It self-skips unless `src/main/resources/SYNTHS` and hardware calibration recordings (configured via `-Dscorecard.recordings`)
exist (recordings are ~150 MB each, not in git). Since 2026-07-24 it renders the ALLSYN songs'
**embedded instrument copies** (what the recording actually played — the standalone preset files
drift from them; old mode: `-Dscorecard.presets=true`).

> **⚠ EVERY SCORE BELOW THIS LINE PREDATES 2026-08-12 AND WAS MEASURED THROUGH A BIT-CRUSHER.**
> `syncMasterEffects` mapped the song's sample-rate-reduction and bitcrush params so that "off"
> became the q31 *midpoint*, leaving both effects ~50% ON for **every** render the scorecard ever
> made (commit `0f17dc61`, `docs/FIDELITY_GAP_ANALYSIS.md` §4.2septseptuagies). Treat every
> per-family verdict in this section as provisional until re-measured — including the bottom
> cluster named below, which has since changed membership entirely.
>
> **Post-fix measurements (2026-08-12).** CALIB, same recordings: time-resolved median
> **0.735 → 0.860**, negative cosines **15 → 2**, and the level excess collapsing from ~+20 dB to
> ~+5 dB in every group (the dry control itself went **+19.4 → +4.6 dB**). The CALIB median is not
> like-for-like — removing the noise floor pushed quiet slices under the measurability guard, so the
> scored set shrank 220 → 191 (hpf 33 → 13, noise 9 → 4).

**CURRENT ALLSYN BASELINE (2026-08-12, re-recorded unclipped): time-resolved median 0.919,
mean 0.907, 93% ≥ 0.80, 66% ≥ 0.90, and NOTHING below 0.60** (n=188; clean subset n=185, same
median). Single-window: median 0.912. This reproduces the historical ≈0.92 and settles that
question — it was always reproducible, it just needed a recording that was not clipped.

Getting there required a **recording-procedure fix worth knowing**: the Deluge's resample-to-SD
capture happens BEFORE the master volume knob, so no knob at record time can stop it clipping —
monitoring quietly still wrote 0 dBFS. The lever is `songParams volume` inside the song, which was
at maximum. Note the volume curve saturates: `getFinalParameterValueVolume` clamps `temp` at 2^30,
so anything at or above ~75% renders identically to maximum — `0x40000000` would have changed
nothing. Set to **`0x00000000`** (the midpoint, ≈ −12 dB) both songs came back usable: ALLSYN_2 at
−2.1 dBFS with **0.0000%** at the rail, ALLSYN_1 with zero slices over the CLIPPED threshold.
`src/main/resources/SONGS/ALLSYN_{1,2}.XML` now hold exactly the songs those recordings were made
from, so song and recording match by default.

> **RETRACTED: the "newly-exposed bottom cluster".** Earlier entries here listed 059 Distorted Lead
> Guitar (0.312), 030, 040, 021, 035 and others as regressions the bit-crush fix had uncovered. They
> were artifacts of the CLIPPED recording, and every one of them recovers on the clean take: 059
> **0.312 → 0.764**, 030 **0.513 → 0.959**, 021 **0.583 → 0.915**, 035 **0.596 → 0.890**, 040
> **0.529 → 0.896**. The 059 "right level, wrong harmonics" investigation was chasing a clipped
> reference; its one durable result is that the per-voice saturation path is faithful to the C.
> Near-silent slices fell 30 → 1 and clipped references 13 → 2 on the new take.

**Superseded baseline (embedded mode, 2026-07-24 recordings): time-resolved median ≈ 0.92, 94% of
synths ≥ 0.80, 64% ≥ 0.90.** The
big 2026-07-25 jump was **C-exact clip-param semantics** read from the C loader (a ≥1.2.0
song's clip = fresh initParams ParamManager + ONLY the clip's listed tags — no instrument
back-fill, ZERO patch cables; see `docs/FIDELITY_GAP_ANALYSIS.md` §4.2septies): this closed
most of the former "saturation/PWM/sync/resonant-filter" bottom cluster (016/120/015/059/045…),
which was never the DSP. A second fix the same day (§4.2octies): osc type `"none"` is the C's
unrecognized-type **TRIANGLE fallback** (functions.cpp:812-814) — an osc is off only via its
volume param, never its type; this recovered the whole unnumbered sample-preset family. The
subtractive core (osc + ladder filter + ADSR) is faithful and scores 0.85–0.97. A third fix:
`hpfMode` LP-mode strings ("12dB" — carried by ALL 188 ALLSYN instruments) load as an **inert
high-pass** in the C (no dispatch branch, filter_set.cpp:26-41; §4.2nonies) — this recovered
109 and fixed silent hardware-compat bugs in our filter-mode serialization. Open items as of that
era: the then sub-0.70 scorers (100 Noise Lead .62, 149 Cold 5th Pad .59, 121 Tiny Lights .67),
**FX (reverb/delay/modFX)**, and one scoring artifact (129: hardware slice genuinely
near-silent — §4.2septies follow-up 3). (FM was the former biggest cluster — resolved
2026-07-24 as clip semantics + stale-recording confusion; hardware-verified.)

**Open items now (post bit-crush fix, 2026-08-12), in priority order:**

1. ~~The measurability threshold.~~ **Resolved 2026-08-12, and it was not a threshold problem.**
   Of the 59 excluded CALIB cases, 46 rendered **exactly zero** on our side. 30 of those were the
   entire wavetable group, silent only because `-Ddeluge.card` pointed at `src/main/resources`,
   which has no `SAMPLES/WAVETABLES` — pass `-Ddeluge.card=<gen_calib.py output>` and they score a
   median of **0.902**, one of the best groups in the corpus (CALIB then reads n=221, median 0.862).
   The scorecard now warns loudly when >10% of items render silent on our side, so a whole group
   cannot hide behind an aggregate count again.

   **Correction to the first version of this entry:** it claimed 12 HPF and 4 noise cases "render
   EXACTLY ZERO". They do not. Their `ourMax` is 3e-5 .. 1.5e-3 — small but real; only the 30
   wavetable cases were truly 0.0. (The error: all 46 values were printed, the first 25 read, and
   "exactly zero" extrapolated from a screenful that happened to be all wavetable.) The guard's
   absolute `< 0.002` floor was discarding them, so it now rejects only true silence (`< 1e-6`),
   matching its stated purpose of catching our own render failures.

   That change is **scorecard-neutral**, and the reason is worth knowing: those 16 cases are still
   excluded, just via the both-silent path instead. For `HPF HPLadder f75 q00` the HARDWARE slice is
   `hw_rms=0.00043` against a dry control of 0.0069 — 24 dB down. Both sides sit on the noise floor,
   so there is genuinely nothing to compare. They are not a measurement bias; they are unmeasurable
   with this corpus and metric. A high-corner high-pass on a C4 saw removes nearly everything, and
   testing it needs a source with energy up where the filter passes — a corpus change, not a code
   change.
2. **The current bottom scorers** (clean 2026-08-12 take, nothing below 0.60): 045 Square Sync
   0.663, 121 Tiny Lights 0.718, 134 Melody String 0.725, 149 Cold 5th Pad 0.744, 098 Saturated
   Sync 0.751, 142 Phaser 0.755, 139 Detuned Saw Pad 0.762, 059 Distorted Lead Guitar 0.764. Note
   two SYNC presets at the very bottom (045, 098), which is the one family that keeps reappearing.

   **READ THE SCORECARD'S PER-SLICE ANNOTATIONS BEFORE BUILDING ANY LIST FROM ITS CSV.** This entry
   was wrong twice because I ranked raw `time_resolved` numbers and ignored the labels sitting right
   next to them. Four of the eight presets first listed here are not defects at all: 070
   Glockenspiel, 083 Dark Chorus and 087 Define Leader are **NEAR-SILENT** (−51 dB below the song's
   median slice), and 040 Spacer Leader has a **CLIPPED** reference with 41.45% of the slice at the
   rail. The scorecard prints exactly that for each of them, plus a run-level "Do not draw DSP
   conclusions from them". The `-Dscorecard.csv` dump does not carry those labels — the console does.
   Prefer the **TIME-RESOLVED (clean)** summary, which already excludes the near-silent set.

   The near-silence guard needs no ALLSYN control lane, contrary to an earlier note here: its median
   fallback correctly flagged all 30 near-silent slices. A dry-control lane would be more principled
   but is not blocking anything.

   **The sharpest one is 059**, and its diagnosis is already narrowed. Its defining parameter is
   `<clippingAmount>8</clippingAmount>` — the maximum per-voice saturation. Audited the whole
   saturation path Java→C and it is **faithful**: `getShiftAmountForSaturation`
   (`(clippingAmount >= 2) ? clippingAmount - 2 : 0`), the `5 + clippingAmount` drive, and
   `getTanHAntialiased` including its working-value state all match `sound.h:286-294` /
   `functions.h:295-304` line for line. What is telling is the direction of the change: with the
   bit-crush fix its **level got BETTER** (−3.8 → −1.4 dB against a hardware slice of 0.257) while
   its **cosine got much worse** (0.768 → 0.312). Right level, wrong harmonics, faithful saturator —
   which points at the LEVEL DRIVING the saturator, since tanh harmonic content is level-dependent.
   That makes CLAUDE.md's own claim that "the whole voice chain up to and through the nonlinear
   stages (ladder drive, per-voice saturate, master compressor) runs at C-exact levels" the thing to
   re-verify; a whole master-bus stage turned out to be wrong on 2026-08-12, so that claim should not
   be inherited on trust.
3. **Noise scored worse** after the fix on CALIB (0.643 → 0.422 over 4 measurable cases) — plausibly
   it was being flattered by quantisation noise resembling the hardware's own floor.
4. ~~BOTH ALLSYN recordings are CLIPPED~~ — **fixed 2026-08-12 by re-recording**; see the baseline
   block above. Historical note, since it explains a lot of earlier confusion: the old takes were
   clipped — `~/ALLSYN_1` at 0.66% of samples at the
   rail, `~/ALLSYN_2` at 0.15%, with 13 slices carrying clipped references. Every ALLSYN absolute
   number in this file therefore describes the recording as much as the engine, which also explains
   why nothing here reproduces the historical 0.92 (both sides of the 2026-08-12 A/B landed near
   0.83–0.85). The A/B *delta* is still valid — same recording on both sides — but the levels are
   not. **Re-recording ALLSYN at lower input gain is the single highest-value hardware task**; see
   track 2 of the recording runbook, and fold in the CALIB delay-feedback cap while at the device.

Workflow for a fidelity fix: pick a family above → open the cited C subsystem under
`../DelugeFirmware/src/deluge/` → port faithfully → re-run the scorecard and confirm the targeted
family rose **and the faithful set didn't regress**. `docs/FIDELITY_GAP_ANALYSIS.md` is the
detailed, per-family working reference. **Honesty rule (hard-won): RMS and autocorrelation give
false readings here — always verify with the spectral scorecard and reset the noise seed
(`Functions.resetNoiseSeed()`); never claim a fidelity fix the scorecard doesn't confirm.**

**Corollary — the cosine is blind to level, and absolute levels lie (2026-07-30, §4.2septuagies).**
`spectrum()` subtracts the mean log-magnitude, so the score deliberately ignores overall gain: a
family can sit at 0.85 while rendering 15 dB hot. But a raw our-vs-hardware ratio is not a
measurement either — the CALIB **dry control** itself reads +8.0 dB (the hardware's output chain is
quieter than our float render), a constant every group inherits, so uncalibrated ratios flag all 250
cases and localise nothing. Always express level as **excess over the dry control**; the scorecard
now does (`-Dscorecard.csv` adds `hw_rms,our_rms,level_db`, the run logs a `dry-control baseline`,
and NEAR-SILENT / LEVEL guards fire off it). Two claims made from uncalibrated levels the day before
had to be retracted.

Other fidelity tests in `src/test/resources/fidelity/` (`AllSynthsFidelityTest`, `Dx7ParityTest`,
`DelayParityTest`, `ArpParityTest`, `DigitalAudioFidelityTest`) compare against reference WAVs.
`FIRMWARE_PARITY.md` tracks the subsystem gap vs. the native firmware; `HARDWARE_FIDELITY.md`
documents the hardware recording procedure.

## XML song/preset format (read before editing serialization)

The serializer targets the format the **current `../DelugeFirmware` HEAD** writes (it was modeled
on community firmware c1.2.0 and has since been brought up to head; see `SONG_XML_SPEC.md`,
`docs/deluge.xsd`, and section 0 of `HARDWARE_FIDELITY.md`). Critical, non-obvious rules:

- Instrument clips bind to instruments **by name** (`instrumentPresetName` ↔ `presetName` +
  matching `presetFolder`); a missing linkage makes real hardware reject the file as
  `FILE_CORRUPTED`. There is no fallback path.
- Song settings are **attributes** on `<song>`; tempo is encoded as `timePerTimerTick` +
  `timerTickFraction`, not a `tempo` attribute.
- Synth params live in the **clip's** `<soundParams>` (not the instrument's `defaultParams`,
  which is the separate preset-file format).
- Notes are packed `noteDataWithLift` hex blobs (11 bytes/note).

## Conventions

- Code style is google-java-format (GOOGLE style, unused imports removed) via Spotless.
  `spotless:check` is bound to the `verify` phase, so `mvn verify`/CI fails on unformatted code.
  **Always run `mvn spotless:apply` before committing** to keep commits pre-formatted.
- Extensive prose documentation lives at the repo root (`DELUGE_DESIGN.md`, `FIRMWARE_PARITY.md`,
  `HARDWARE_FIDELITY.md`, `SONG_XML_SPEC.md`) and under `docs/`; the README indexes all of it.
- **Guidebook & User Manual rules**: When editing the user manual ([DELUGE_GUIDEBOOK.md](src/main/resources/docs/DELUGE_GUIDEBOOK.md)):
  * **Strict Code Grounding**: Never guess or invent UI buttons, keyboard shortcuts, config options, or parameter keywords. Verify them directly in the Java UI classes (`SwingGridPanel.java`, `ClipGridPanel.java`, `StepPropertiesEditor.java`, etc.).
  * **Strict User-Facing Tone**: Maintain a clean, user-centric manual. Avoid developer jargon such as "virtual threads", "daemon schedulers", "Swing classes", or "JNI playback thread clock".
  * **Skip Builds for Doc-Only Changes**: Do *not* run a full test compilation (`mvn clean test`) if only markdown (`.md`) or text documentation files were modified. Commit and push directly.
