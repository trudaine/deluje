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
  output.** It carries compensations (an invented DC-blocker, a `lshiftAndSaturate(…, 4)` final
  shift where the C does `>>1`, master-volume applied both pre-mix and in the compressor; and the
  oscillator applies amplitude at `>>30` where the C nets `>>32`). These diverge from C per stage,
  but empirically a full-volume single oscillator peaks at ~0.42–0.49 with **0% clipping** — the
  net level is fine, so this is faithfulness debt, NOT a clipping/level bug. Re-deriving the chain
  to match C stage-by-stage is fidelity-neutral for the spectral scorecard (which is
  amplitude-invariant); only attempt it for saturation/inter-track-balance reasons, scorecard-gated.

See `docs/FIRMWARE2_FAITHFUL_PORT.md` (port protocol + numeric-type mapping) and
`docs/FIRMWARE2_PORT_ROADMAP.md`.

## Build, test, run

Requires **JDK 27 early-access** and **Maven 3.9+**. The codebase uses preview features
(`--enable-preview`) and the incubating Vector API (`jdk.incubator.vector`); these flags are
already wired into the compiler, surefire, and `run.sh`, but any manual `java`/`javac`
invocation needs them too.

A Maven Wrapper is committed (`./mvnw` / `mvnw.cmd`, pinned to Maven 3.9.16) — use it for a
reproducible build without a system Maven; everything below works with `./mvnw` in place of `mvn`.

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

`FidelityScorecardTest` is the project's fidelity gate. It renders ~190 ludocard synth presets
through our engine (one C4) and compares a normalized log-magnitude spectrum against the real
hardware recordings, reporting per-synth + summary cosine similarity (1.0 = identical timbre),
both single-window and **time-resolved** (the headline metric).

```bash
mvn test -Dtest=FidelityScorecardTest -Dgpg.skip=true -Ddeluge.card=ludocard
```

It self-skips unless `~/ludocard/SYNTHS` and `~/ALL_SYNTHS_SONG/ALLSYN_{1,2}/output_000.wav`
exist (recordings are ~150 MB each, not in git). **Current baseline:** time-resolved
median ≈ 0.79, ~49% of synths ≥ 0.80. The subtractive core (osc + ladder filter + ADSR) is
faithful and scores 0.85–0.97; the gap is concentrated in **FM synthesis** (FM Bells/Distorted
Bells ≈ 0.1–0.4 — biggest cluster), **oscillator hard-sync** (Saw/Square Sync ≈ 0.3–0.4),
**PWM/PW envelope**, **resonant/distorted filter**, **FX (reverb/delay/modFX)**, and ~16
multisample presets that render silent only because the test path doesn't load their samples.

Workflow for a fidelity fix: pick a family above → open the cited C subsystem under
`../DelugeFirmware/src/deluge/` → port faithfully → re-run the scorecard and confirm the targeted
family rose **and the faithful set didn't regress**. `docs/FIDELITY_GAP_ANALYSIS.md` is the
detailed, per-family working reference. **Honesty rule (hard-won): RMS and autocorrelation give
false readings here — always verify with the spectral scorecard and reset the noise seed
(`Functions.resetNoiseSeed()`); never claim a fidelity fix the scorecard doesn't confirm.**

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
