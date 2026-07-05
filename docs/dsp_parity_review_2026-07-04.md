# DSP Parity Review — Java port vs C firmware (2026-07-04)

**Scope.** Full line-by-line parity review of the DSP path (`org.deluge.firmware2` + the
`org.deluge.engine` glue that feeds it) against `../DelugeFirmware/src/deluge`, run as 11 parallel
subsystem audits: oscillator, voice pipeline, filters, DX7, modulation (env/LFO/patcher), delay+modFX,
reverb, dynamics, fixed-point primitives/tables, sound-level chain, sample playback/timestretch.
Every finding below was verified by quoting both sides; `file:line` cites are as of commit `202e2566`.

**Headline.** The fixed-point primitives, every lookup table (verified numerically), and most DSP
inner loops are genuinely faithful. The parity failures cluster in two places:

1. **A small number of outright translation bugs in `firmware2`** — signed-vs-unsigned shifts,
   a misaligned table, an un-zeroed buffer, missing branches. These map one-to-one onto the
   scorecard's failing families (PWM, Square Sync, FM, resonant/distorted filter, wavetable).
2. **The `org.deluge.engine` wiring layer**, which repeatedly feeds faithful DSP classes wrong
   values (raw knobs instead of patcher finals, inverted sync mapping, invented attack/release
   curves, dead pan/sidechain hooks). The delay/modFX/reverb/sidechain families fail here, not
   in the DSP.

A recurring systemic theme: **Java substitutes saturating ops where the C uses wrapping
arithmetic** (ladder feedback sums, HP gain stage, delay/EQ hot paths, comb filters). The C's
wrap-and-fold *is* part of the hardware's distortion character at high resonance/drive; Java sounds
"cleaner than hardware" exactly on the distorted presets. A second theme: **per-sample amplitude
ramping (`...LastTime` + increment) is dropped in nearly every render path** — C ramps within the
block, Java steps per block.

This review also **resolves the long-open "per-sound delay is inaudible" issue** (§ Delay, D1/D2)
and supersedes the delay-tail theory in `FIDELITY_GAP_ANALYSIS.md`.

---

## Top findings ranked by expected scorecard impact

| # | Finding | Family hit | Where |
|---|---------|-----------|-------|
| 1 | PWM fixed-point bugs: signed `>>` on unsigned phase (2×), halved divisor | PWM / PW-envelope | `Oscillator.java:562,845,552` |
| 2 | Square sync: pulse+sync branch missing; crude-square sync branch missing | Saw/Square Sync | `Oscillator.java:833-848,806-822` |
| 3 | DX7 render buffer never zeroed (C memsets; FM core *adds*) | FM / DX7 | `Voice.java:1204-1207` |
| 4 | FM mod2→mod1 routing: inactive mod1 should mute chain → pure-sine carriers | FM | `Voice.java:1342-1396` |
| 5 | Wavetable band selection inverted → always dullest band; waveIndex offset + scan-width wrong; wavetable sync ignored | Wavetable | `WaveTable.java:53,65,111-128`, `Voice.java:916-928` |
| 6 | `resonanceThresholdsForOversampling` misaligned by 1 and 1 entry short (64 vs 65) — wrong drive-oversampling flips + OOB crash at max cutoff | Resonant/distorted filter | `LookupTables.java:214-223` |
| 7 | Reverb container: Mutable/Digital render **silence** (LPF never set), pan/output-volume multiply dropped (else 8× hot), default model FREEVERB vs hardware MUTABLE, MUTABLE↔DIGITAL cases swapped | Reverb pads | `Reverb.java:552,414-541`, `PureFirmwareEngine.java:144-148` |
| 8 | Delay sync mapping inverted (each sync level should *halve* time; Java doubles → clamps at 2 s) + `syncParamsToFw2()` clobbers exp-converted delay rate & modFX rate/depth with raw Q31 knobs every render | Delay + modFX | `FirmwareSound.java:439-453,420-421` |
| 9 | Note-off semantics reconstructed: `ignoredNoteOff` never set (zero-sustain decay truncated), FAST_RELEASE never used, env2/3 wrongly released, DX7 `keyup()` never called | Plucks, PW-envelope, DX7 release | `Voice.java:389-393` |
| 10 | Arp gate threshold right-shifted 8 twice → gates ~1/256 length | All arp presets | `Sound.java:709-712` + `ArpeggiatorBase.java:598` |
| 11 | `getFinalParameterValueVolume/Linear` clamp at 2^30 (C deliberately unclamped, allows 4×) — up to 7 dB FM-index loss when cables push past unity | FM Bells | `Functions.java:254-273` |
| 12 | Global LFO sync + global FX param patching ignored: synced LFO1 free-runs; cables to delay rate/feedback, modFX rate/depth, arp rate, reverb amount are inert | Wobble/synced-LFO, animated-FX presets | `Sound.java:695-705,806-853` |
| 13 | Per-sample source-amplitude ramping dropped in every path (subtractive, FM carriers, DX7, samples) — block-stepped amplitude vs C's intra-block ramp | Systemic (zipper, attack shape) | `Voice.java:1253-1444` |
| 14 | Sine osc rendered +6 dB (misread C `goto` skips the doubling); crude square −6 dB (`getSquareSmall` in amplitude path); crude↔table wave balance 8:1 vs C's 2:1 | Mix balance everywhere | `Oscillator.java:596-623,813-816,336` |
| 15 | Synth saturation applied twice (faithful per-voice pass + invented track-level pass with hybrid constants) | Saturated presets | `GlobalEffectable.java:98-116` |

---

## Per-subsystem detail

### Oscillator (`Oscillator.java`, `SineOsc.java` vs `dsp/oscillators/*`, `render_wave.h`)

- **CRITICAL** `Oscillator.java:562` — `resetterPhase >> 1` arithmetic vs C's `uint32 >> 1`
  (oscillator.cpp:116): PW on SAW/SINE/TRIANGLE/ANALOG_SAW_2 computes a phase off by
  `−4·((pwAbs>>1)+2^30)` for the entire second half of every cycle.
- **CRITICAL** `Oscillator.java:845` — `-(pulseWidth >> 1)` arithmetic vs C unsigned
  (oscillator.cpp:422): square PWM reads the opposite-polarity table half for ~half the PW range.
- **CRITICAL** `Oscillator.java:552-555` — analog-square PW rescale divides by
  `(pwAbs+2^31)>>>1` where C divides by the unshifted value (oscillator.cpp:105): phase increment
  2× → intra-sync wave an octave up.
- **CRITICAL** `Oscillator.java:833-848` — band-limited square with PW **and** osc sync falls into
  the generic sync path, dropping PW entirely (C oscillator.cpp:417-449 has a dedicated branch).
- **MAJOR** `Oscillator.java:806-822` — crude (<~72 Hz) square has no osc-sync branch at all
  (C oscillator.cpp:381-410): sync vanishes at the low end of sync sweeps.
- **MAJOR** SINE amplitude doubled (`:596/603/615/623`): C's `goto callRenderWave` (oscillator.cpp:147-151)
  lands *after* the doubling at :471-472. Sine is +6 dB vs all other waves.
- **MAJOR** crude square uses half-scale `getSquareSmall` in the applyAmplitude path (`:813-816`)
  where C uses full-scale `getSquare` (oscillator.cpp:344-346): −6 dB.
- **MAJOR** table-wave amplitude at `>>30` (4× C net) while crude paths are bit-exact — documented
  debt, but the crude↔table *relative* level is 8:1 vs C's 2:1: a +12 dB step at the ~72 Hz table
  boundary and wrong osc balance when one osc is crude and the other table-rendered.
- Verified faithful: `renderWaveSync` core (crossover math, half-sine crossfade), table selection
  thresholds, crude saw/triangle incl. sync, `doFMNew`, all wave tables (values + 6-null offsets).
  ⇒ **Non-PW Saw Sync is faithful in the core** — consistent with `testSynthHardSyncParity` passing.

### Voice pipeline (`Voice.java` vs `model/voice/voice.cpp`)

- **CRITICAL** `Voice.java:1342-1396` — C checks `modulator1ToModulator0 && !modulatorsActive[0]`
  *before* rendering mod1 and jumps to `noModulatorsActive` (carriers = plain sines,
  voice.cpp:1433-1437); Java renders mod1 and FMs the carriers with it anyway.
- **CRITICAL/systemic** — `sourceAmplitudesLastTime`/`sourceAmplitudeIncrements` written but never
  used; all paths pass increment 0 with the *target* amplitude (C voice.cpp:1056-1067,1119-1121
  ramps from last block's value). FM carrier level (which *is* the output level) leads C by one
  block and steps.
- **MAJOR** noteOff (`:389-393`): see Top-9. Also fast-release increment default 1024 vs C's
  `SOFT_CULL_INCREMENT` (65536).
- **MAJOR** invented sample amplitude floor `max(vol>>4, 1<<26)` (`:1098-1101`); zero-volume
  samples audible.
- **MAJOR** sample cents fine-tune (`fineTuner.detune`) and INPUT-source transpose dropped
  (`:538-650` vs voice.cpp:434-509); stereo samples mono-summed (`:1113-1119`).
- **MINOR** porta-vs-bend order swapped; phase randomized on every noteOn incl. reused voices
  (C only on fresh acquisition — also desyncs the shared CONG stream); `performInitialPatching`
  runs before NOTE/VELOCITY sourceValues are set (C: after); `justCreated` one-block render
  deferral missing; play-once auto-release + `overrideAmplitudeEnvelopeReleaseRate` missing;
  frequency-too-high sample guard missing; bend ranges hardcoded 2/48.
- Verified faithful: overallOscAmplitude math + fold→filter→amp→saturate→pan order, FM helper
  inner loops incl. feedback `signed_saturate<22>`, `calculateBasePhaseIncrement`, unison detune
  and stereo-spread constants, osc-sync plumbing (s==0 resetter, s==1 synced), porta math,
  ringmod structure, `FilterSet.setConfig` args.

### Filters (`LpLadderFilter`, `HpLadderFilter`, `SVFilter`, `FilterSet` vs `dsp/filter/*`)

- **CRITICAL** `LookupTables.java:214-223` — `resonanceThresholdsForOversampling` has 64 entries
  (C: 65, lpladder.cpp:21-35) with the fourth `16384` (C index 51) missing: every threshold ≥51
  shifted one slot early → `doOversampling` flips at wrong resonance for cutoff slots 52-54
  (entire drive render path changes), and slot 63 reads `table[64]` → AIOOBE at max cutoff.
- **MAJOR** wrap-vs-saturate in the hot paths: drive feedback sum `<<2` (`LpLadderFilter.java:305`
  vs lpladder.cpp:386-390), drive output `<<1` (`:318` vs :408), HP gain stage `<<5`
  (`HpLadderFilter.java:128` vs hpladder.cpp:97), `getFeedbackOutput` `<<2`
  (`BasicFilterComponent.java:59` vs ladder_components.h:43). All diverge only at high
  resonance/drive — exactly the failing family; C's wrap is part of the sound.
- **MINOR** stereo dry/wet fade at half speed; `FilterSet.reset()` reconstruction + the
  `Voice.java:753-762` first-render fade cancel hack; mono voices run through the stereo path,
  consuming 2× CONG draws (breaks noise-stream reproducibility vs C).
- Verified faithful: both ladders' `setConfig` in full, `scaleInput`, 12/24 dB cascades, SVF
  everything, FilterSet routing/order, `tanh.bin` byte-identical, `tanTable`/`resonanceLimitTable`.

### DX7 (`Dx7Voice`, `FmCore`, `EngineMkI`, `Dx7Tables` vs `dsp/dx/*`)

- **CRITICAL** `Voice.java:1204-1207` — `uniBuf` never zeroed before `dxVoice.compute()`; C
  memsets (voice.cpp:2409-2411) and the FM core **adds** carriers into bus 0 → cross-block/voice
  accumulation, runaway distortion on all DX7 presets.
- **MAJOR** `Dx7Voice.keyup()` has zero call sites (C voice.cpp:619-625 calls it in noteOff):
  operator/pitch EGs never release.
- **MAJOR** `Dx7Voice.java:512` — `delayState < (1 << 31)` is always false in Java (int min):
  LFO delay always uses `delayInc2` → onset up to 4× too fast.
- **MAJOR** `Dx7Voice.java:471-473` — LFO key-sync phase set to `-1` (0xFFFFFFFF); C uses
  `(1U<<31)-1` (0x7FFFFFFF): vibrato starts a half-cycle off.
- **MINOR** DxPatch copied per voice (C shares per source: shared LFO phase across chord voices,
  live `update()` on edits); `compute()` activity return ignored; feedback kernel over `simdN` not
  `n` (latent at block 128); per-sample amp ramp dropped at the mix stage.
- Verified faithful: env/pitchenv state machines + tables, dx7note scaling chain, all 32 algorithms,
  fm_op kernels, EngineMkI incl. its own C quirks, math_lut generation bit-identical.

### Modulation (`Envelope`, `Lfo`, `Patcher`, `Param` vs `modulation/*`, `functions.cpp`)

- **CRITICAL** note-off semantics (shared with Voice, Top-9): `ignoredNoteOff` is dead code;
  zero-sustain envelopes must finish their decay on hardware.
- **MAJOR** `Functions.java:254-273` volume/linear clamp (Top-11); C comment explicitly says the
  clip was removed to let FM modulator amounts exceed unity.
- **MAJOR** `Sound.java:695-705` — global LFO phase increment ignores `lfoConfig[].syncLevel`
  (C sound.cpp:2700-2707) and drops cables patched to GLOBAL_LFO_FREQ.
- **MAJOR** `Patcher.java:44-50` — `toPolarity` misses the AFTERTOUCH and Y special cases
  (patch_cable.h:50-63) and defaults every cable BIPOLAR (C: aftertouch defaults UNIPOLAR).
- **MAJOR** `Patcher.java:358-363` — `performInitialPatching` uses the no-cables shortcut for all
  params (C folds real cables) and runs before source values are set: velocity/note→attack cables
  miss the envelope's noteOn decision.
- **MINOR** RANDOM_WALK range constant off by one + 31-bit-masked noise (C uses full unsigned CONG);
  unconditional env/LFO rendering desyncs the CONG stream; `cableToExpParam` saturates vs C wrap.
- Verified faithful: envelope state machine core, all LFO waves + sync dispatch + initial-phase
  tables, the entire cable math (`combineCablesLinear/Exp`, rangeAdjust, hybrid/exp/volume curves),
  param IDs/ranges/neutrals value-for-value, the **whole static PW chain** (knob→hybrid→`<<1`→
  `pulseWidth+2^31`) — the PW family's problem is the oscillator bugs + note-off, not the plumbing.

### Delay + ModFX + per-sound FX (`Delay`, `DelayBuffer`, `ModFx`, `Stutterer`, `SrrBitcrush`, `Eq`)

**Resolves the open "per-sound delay inaudible" issue** — the DSP is faithful; the engine kills it:

- **CRITICAL (D1)** `FirmwareSound.java:439-447` (+`FirmwareFactory.java:820`,
  `FirmwareAudioEngine.java:133`) — sync mapping inverted: C `rate <<= (syncLevel+5)` (higher
  level = shorter delay, delay.cpp:102-117); Java `2^(syncLevel-1)` sixteenths (doubles per level)
  → typical presets clamp at 2.0 s, first echo outside any audition window. Triplet/dotted also
  inverted; dotted missing at song level.
- **CRITICAL (D2)** `FirmwareSound.java:451-453` — `syncParamsToFw2()` (every render) clobbers
  `delayUserRate` with the raw Q31 knob; default knob 0 fails `delayUserRate > 0` → the delay
  never allocates. `FirmwareFactory` sets it correctly at load; first render undoes it.
- **MAJOR** same clobber for modFX (`:420-421`): rate 0 → frozen LFO (static comb) or raw-knob →
  ~8 kHz LFO noise. C uses paramFinalValues (rate neutral 121739 exp, depth linear).
- **MAJOR** modFX not gated on `anySoundComingIn` (`Sound.java:828` passes `true`); the chorus
  −3 dB postFX cut applies during tails.
- **MAJOR** stutterer rate formula invented (`getExp(1, ...)`+floor vs C
  `getFinalParameterValueExp(2^24-neutral)` + sync); would NPE via null paramManager if wired.
- **MINOR** feedback-drop never discards buffers (stale echoes + immortal `delayTailActive`);
  standalone `DelayBuffer.java:295,310` uses `>>>4` where C is signed `>>4`; saturate-vs-wrap in
  delay/EQ hot paths; ping-pong ignores `renderInStereo`.
- Verified faithful: the entire `Delay.process` structure, buffer sizing/resample math, analog
  IR + tanh, ModFx (all types incl. phaser + warble), SrrBitcrush bit-exact, EQ, stutter mechanics.

### Reverb (`Reverb.java`, `Freeverb.java` vs `dsp/reverb/*`)

All three model cores verified essentially perfect; the container/wiring breaks them:

- **CRITICAL** Mutable/Digital never apply `getPanLeft/Right()` on accumulate
  (`Reverb.java:414,428,540` vs mutable.hpp:109-111): wet 8× (+18 dB) hot and the
  volume/sidechain/pan hook severed.
- **CRITICAL** Java container never sets LPF: `lpf` defaults 0 → one-pole coefficient 0 →
  Mutable/Digital output **exact silence** (`Reverb.java:552-581`; C always loads
  `reverbLPF = 50/50` → ~open, song.cpp:181). No `setLPF/setHPF` call site exists in the engine.
- **CRITICAL** default model: hardware c1.2.0 new songs use **MUTABLE** (song.cpp:188); Java
  defaults FREEVERB (`Reverb.java:549`) — FREEVERB is only the legacy-file fallback. All ludocard
  hardware recordings went through Mutable.
- **MAJOR** `PureFirmwareEngine.java:144-148` swaps cases 1/2: selecting Mutable runs Digital and
  vice versa.
- **MAJOR** reverb sidechain ducking missing (`FirmwareAudioEngine.java:248` hardcodes 0; C
  audio_engine.cpp:811-833 + auto-mode param borrowing).
- **MAJOR** send base derivation: track postFX volume folded into the curve (double-counts track
  volume, misses song volume) and the raw factory-time knob is used instead of
  `paramFinalValues[GLOBAL_REVERB_AMOUNT]` → reverb amount unmodulatable
  (`GlobalEffectable.java:130-133`, `FirmwareFactory.java:839`).
- **MINOR** `reverbPan` unwired; fresh-project damp/width defaults (0.5/0.5 vs hardware 0.72/1.0);
  wet mixed after master volume; Freeverb saturate-vs-wrap; grain→reverb backdoor missing.

### Dynamics (`Compressor`, `Sidechain` vs `dsp/compressor/rms_feedback.cpp`, `modulation/sidechain.cpp`)

- **MAJOR** per-sound compressor runs pre-volume/pre-reverb-send (C: post, sound.cpp:2591-2600) —
  wrong detection level, reverb taps compressed signal.
- **MAJOR** `renderVolNeutral` uses `buffer.length` (256) for 128-sample blocks
  (`Compressor.java:207`, `Sound.java:341`): envelopes 2× fast, RMS −3 dB, ramp half-applied.
- **MAJOR** master compressor `baseGain` left at constructor 1.35; C song compressor sets **0.85**
  (song.cpp:191) — ~1.65× hotter into the fixed tanh stage. Not covered by the documented
  master-stage debt list.
- **MAJOR** sidechain fed invented attack/release (`FirmwareFactory.java:738-743`, values ~10-1000×
  off the C rate tables; duck attack ~23 ms vs hardware ~0.6 ms); tempo-sync disabled (fixed
  `1<<20` tick-inverse proxy + syncLevel 0 vs C default `7-magnitude`).
- **MAJOR** `GlobalSidechainBus` is ThreadLocal: hits registered on the Swing/MIDI threads never
  reach the audio thread — manually played kicks don't duck.
- **MINOR** sidechain rendered/registered unconditionally (C gates on patched); metronome placed
  before the master compressor (C: after); default sidechain shape differs when preset omits it.
- Verified faithful: all compressor knob setters, `updateER`, render core incl. inlined
  `getTanHAntialiased`, `calcRMS` structure; the full `Sidechain.render` state machine + both rate
  tables byte-identical.

### Fixed-point primitives + tables (`Functions`, `LookupTables`, `WaveTable`, `Sinc*`)

- **CRITICAL** `WaveTable.java:53` — `maxPhaseIncrement = 0xFFFFFFFF >> (32 - mag)` computes
  `2^mag−1` instead of C's `(0xFFFFFFFF >> mag) * 1.25` (wave_table.cpp:288): every real phase
  increment exceeds every band → render always uses the smallest 8-sample band and `getKernelIndex`
  saturates to the dullest kernel. All wavetable presets lose nearly all harmonic content.
- **MAJOR** wave-index scan: missing `+1073741824` offset (C oscillator.cpp:155); magnitude
  off-by-one (`getMagnitude` vs C `getMagnitudeOld` = floor(log2)+1); hard-coded 31 vs C's
  30-bit scaled-input width; unsigned multiply where C is signed → scan covers ~half the table.
- **MAJOR** wavetable osc-sync parameters accepted and ignored (C runs full renderOscSync
  machinery, wave_table.cpp:1128-1174).
- **MAJOR** `WavetableGenerator` is a reconstruction (double FFT vs C int32 NE10; Nyquist-bin
  preservation, raw-initial-band, HF-content band trimming, non-power-of-2 rejection all absent).
- **MAJOR** volume/linear clamp — see Top-11 (quantified: patched 2^30 with FM neutral →
  C 301989888 vs Java 134217728, 2.25×/7 dB).
- **MINOR/NOTE** `interpolateTableInverse` signed vs C's degenerate unsigned division on
  descending tables; `instantTan` added clamps; `-INT_MIN` special case; kernel lerp int32 vs C
  int16 `vqdmulh` (±1 LSB/tap); `fastPythag`/`shiftVolumeByDB` unported.
- Verified faithful: **every** primitive against ARM semantics (SMMUL/SMMULR/SMMLA/QADD/SSAT) and
  **every** table numerically (exp/decay/sine/cent/attack/release/tan/tanh-bin/triangle-AA/sinc
  kernels/noteFrequency).

### Sound-level chain (`Sound.java`, `GlobalEffectable.java` vs `sound.cpp`, `mod_controllable_audio.cpp`)

- **MAJOR** arp gate double `>>8` (Top-10); bias also doesn't wrap like C uint32.
- **MAJOR** stutter positioned before modFX (C: after delay, sound.cpp:2586-2589).
- **MAJOR** synth saturation duplicated at track level with hybrid kit/Sound constants (Top-15).
- **MAJOR** no per-sample postReverb volume ramp (C mod_controllable_audio.cpp:222-267 +
  first-render grab): sidechain pumping zippers at block boundaries.
- **MAJOR** global FX params bypass patcher entirely (Top-12).
- **MINOR** LPF/HPF engagement conditions differ (C skips a neutral-cutoff filter entirely);
  FX tails cut early when voices end (modFX feedback memory, paramLPF); post-arp note-on only
  handles `[0]` and invents velocity 64; DX7 LFO per-voice instead of per-source.
- Verified faithful: macro FX order (SRR→modFX→EQ→delay), pan law net-equivalent, delay working
  state, postFX/postReverb finalization incl. the `<<5` shifts, EQ, unison gain, paramLPF.

### Sample playback + timestretch (`VoiceSample`, `SampleReader`, `TimeStretcher`, `LivePitchShifter`)

- **CRITICAL** `LivePitchShifter.java:367-380` — hopEnd never rolls newer→older head (C
  live_pitch_shifter.cpp:379); older head's mode is never initialized → every live-shift hop
  crossfades against stale audio. (Live input only, not preset scorecard.)
- **MAJOR** timestretch init hops immediately (`samplesTilHopEnd = 0`, heads inactive) vs C's
  ~200-sample natural-attack playback (time_stretcher.cpp:55-56,130-139): stretched attacks
  displaced. Crossfade-offset search also runs when the older head is inactive (C skips).
- **MAJOR** perc-cache beam-width refinement omitted (fixed midpoint): different stretch texture
  on percussive material.
- **MAJOR** loop wrap re-inits the reader: overshoot + 24-bit `oscPos` fraction discarded, history
  re-primed from pre-loop content (C preserves all three, sample_low_level_reader.cpp:404-433):
  per-cycle pitch drift/warble on short sustain loops (multisamples).
- **MAJOR** resampled one-shots end ~9 source frames early and hard-cut the sinc ring-out.
- **MINOR** +6 dB linear-interp fallback mismatch; hop deferral/CPU staggering absent; no
  end-of-voice when both stretch heads die; native↔resampled transition jump-back missing;
  per-source LINEAR interpolation mode not honored; timestretch loop/sync paths unreachable
  (null guide); `maxOffsetFromHead` operands swapped in live hopEnd.
- Verified faithful: sinc kernels numerically identical, all reader inner loops, hop parameter
  tables, `searchForCrossfadeOffset` line-matched, crossfade envelopes, `SamplePlaybackGuide` math.

---

## Cross-references to existing docs

- `getFinalParameterValueVolume` clamp — already flagged in `FIDELITY_GAP_ANALYSIS.md` §4.1(a);
  now quantified (7 dB) and joined by the `Linear` variant.
- "Per-sound delay inaudible" (§4.−1 open item) — **root cause found** (Delay D1/D2 above); the
  voice-cull tail theory is secondary (the tail cut is real, Sound-chain MINOR, but the delay was
  never audible in the first place because of the sync inversion + knob clobber).
- The master/gain stage debt (CLAUDE.md / §4.5) is respected — nothing above re-reports it; the
  master compressor `baseGain=0.85` and metronome ordering findings are *specific C lines* outside
  that documented list.
- §4.1ter's "the FM engine is faithful" conclusion holds for the *sub-functions it verified*
  (`doFMNew`, cable math, frequencies) — the new FM findings are in paths it did not cover
  (mod1→mod0 gating, amplitude ramping, DX7 buffer/keyup, the volume clamp's magnitude).

## Suggested fix order (scorecard-gated, per CLAUDE.md honesty rule)

1. **Mechanical one-liners with C cites, high family impact:** oscillator PWM shifts
   (`>>>`/divisor), filter threshold table entry, DX7 `Arrays.fill(uniBuf, …)`, DX7
   `delayState` unsigned compare + `lfoPhase` constant, wavetable `maxPhaseIncrement`, reverb
   MUTABLE/DIGITAL swap + `setLPF/HPF` sync + default model, arp gate un-double-shift, sine/crude-square
   amplitude, volume-clamp removal.
2. **Small ports:** square pulse+sync branch, crude-square sync branch, FM mod-routing gate,
   noteOff semantics (`ignoredNoteOff`/FAST_RELEASE/env-gating + `keyup()`), reverb pan multiply,
   delay sync mapping + `syncParamsToFw2` final-value plumbing (delay + modFX), global LFO sync.
3. **Structural:** per-sample amplitude ramping (voice + postReverb), wavetable scan math + sync,
   patcher-driven global FX params, sidechain rate tables + cross-thread hit bus, compressor
   position/length, loop-wrap preservation, timestretch init.

Validation per family: `mvn test -Dtest=FidelityScorecardTest -Dgpg.skip=true -Ddeluge.card=ludocard`
(time-resolved median is the headline; reset the noise seed; confirm the target family rises and
the faithful set doesn't regress), plus the clean single-note `-Pslow-tests` references for
FM/sync/PWM go-no-go.

---

## Outcome of the first fix pass (2026-07-05)

Tier 1 + most of tier 2 were applied and scorecard-gated. **Time-resolved: median 0.790 → 0.796,
mean 0.747 → 0.754, ≥0.80: 85 → 88, <0.60: 27 → 26** (n=183; full suite, golden signatures, and
the `-Pslow-tests` PWM/hard-sync clean references all pass). Biggest per-preset moves: House
0.316 → 0.811, Bio Lab 0.404 → 0.759, Tiny Lights +0.20, Epic Saw Modulation Pad +0.14,
Synthwave Pad +0.12, Ringmod Pad +0.13.

Applied (each edit cites its C line in-code): the three oscillator PWM fixed-point bugs, the
crude-square sync branch + full-scale `getSquare`, the sine amplitude undoubling (goldens
re-baselined), the filter oversampling-threshold table (65 entries), DX7 `lfoPhase`/LFO-delay
compare fixes, the wavetable band-selection/scan-math/caller-offset cluster, the reverb
MUTABLE↔DIGITAL swap + LPF default (Mutable/Digital no longer silent) + pan multiply, the arp
gate double-shift, the FM mod1→mod0 gate, the C note-off semantics (`ignoredNoteOff`,
FAST_RELEASE, env1 gating, envs 2-3 untouched, DX7 `keyup()`), global-LFO tempo sync end-to-end
(XML file→internal conversion included), per-sound delay driven through the fw2 Delay's own C
sync math with a derived-from-C `timePerInternalTickInverse` (64 internal ticks per quarter at
magnitude 2 — ground-truthed by PerSoundDelayTimingTest's hardware 1.0 s echo), and modFX
rate/depth fed patcher finals instead of raw knobs.

Corrections to this review discovered while applying:
- **DX7 `uniBuf` memset (Top-3): FALSE — already present** at `Voice.java:1207`; the review
  agent's claim was stale. Verify before fixing.
- **Volume/linear clamp removal (Top-11): applied, REGRESSED, reverted.** Removing the clamp
  (faithful to C) dropped the time-resolved median 0.790 → 0.766 across ~70 presets. Since the C
  is genuinely unclamped, something upstream feeds hotter-than-C patched values into the volume
  curve (or the documented non-faithful master chain amplifies the difference). Do NOT remove the
  clamp again without first finding that upstream divergence; it currently acts as a compensating
  cap (same class as the master-stage debt, §4.5 of FIDELITY_GAP_ANALYSIS).
- The engine's per-sound delay wiring had partially evolved past the review's findings (unsynced
  rate already used the exp final); the real killers were the inverted sync mapping + the missing
  file→internal syncLevel conversion (`song.cpp:5761-5778`) + the 2× tick-rate error.

### Second pass (tier 3, same day)

Also applied and gated — **final: median 0.797, mean 0.757, ≥0.90: 26, ≥0.80: 88, <0.60: 26**
(vs baseline 0.790 / 0.747 / 22 / 85 / 27; full suite + slow clean references green):

- Master compressor `baseGain` 0.85 (song.cpp:191); `renderVolNeutral` takes the real block size
  (was compressing a 256-row scratch for 128-sample blocks).
- Track-level saturation gated off for `Sound` (C saturates synths per-voice only;
  the double-pass at kit constants +0.03 on saturated presets, e.g. Harpsichord Cyborg).
- Per-sample source-amplitude ramps in the subtractive path (osc/wavetable/DX7 consumers),
  faithful to voice.cpp:1042-1069/1119-1121 incl. `shouldAvoidIncrementing`.
- Square pulse+sync render branch ported (renderOscSync with waveRenderingFunctionPulse,
  oscillator.cpp:417-449) — no ludocard preset exercises it, correct by construction.
- Per-sound compressor moved to the C position (after processReverbSendAndVolume, on the
  volume-applied signal; reverb send now taps the uncompressed signal) via a
  `processPostVolumeDynamics` hook in GlobalEffectable.
- Reverb send `reverbAmountAdjust` = neutral 67108864 (song volume applies at the master stage),
  removing the track-volume double-count: reverb pads +0.05..0.08 (80s Strings 0.521→0.597,
  Stars Of The Bin Pad 0.765→0.846, Reeds-Flute-Oboe → 0.908).
- Sidechain: raw firmware rate ints parsed from XML and passed verbatim (the invented float
  formula was 10-1000x off); file→internal sync conversion + C constructor defaults;
  `GlobalSidechainBus` pending-hit accumulator made cross-thread (was ThreadLocal — EDT/MIDI
  kicks never ducked).
- Sample loop wrap preserves overshoot + fractional phase + interpolation history
  (`SampleReader.wrapBy`, sample_low_level_reader.cpp:404-433) — fixes per-cycle pitch drift on
  short sustain loops.
- The delay tick-rate derivation was initially 2x off (128 vs 64 internal ticks per quarter at
  magnitude 2); `PerSoundDelayTimingTest`'s hardware-measured 1.0 s echo caught it, and the
  final constant is derived from the C BPM formula (song.h:447-456).

### Third pass (2026-07-05)

**Final: median 0.800, mean 0.758, ≥0.90: 27, ≥0.80: 91 (50%), <0.60: 25** (baseline
0.790 / 0.747 / 22 / 85 / 27). Applied:

- modFX gated on `anySoundComingIn` (hasActiveVoices) like C sound.cpp:2588 — tail-only blocks
  skip modFX and its postFXVolume cuts.
- Multisample zone CENTS plumbed end-to-end (XML `cents` → KeyZone → CompiledKeyZone →
  VoiceSource.zoneCents → fineTuner detune, voice.cpp:503-509); INPUT_* sources get their
  source transpose + fineTuner; the frequency-too-high guard (voice.cpp:471-481) deactivates
  the SOURCE (not the voice) on left-shift overflow.
- FM carrier amplitudes hoisted to the C's per-block fold (voice.cpp:1026-1039, shared
  shouldAvoidIncrementing/increment machinery) and ramped per sample through
  renderSineWaveWithFeedback / renderFMWithFeedbackAdd (which gained the ramp it lacked).
- Live pitch shifter: hopEnd now rolls newer→older head by value (live_pitch_shifter.cpp:379 —
  previously the older head was NEVER written, so every hop crossfaded against stale audio) and
  the `maxOffsetFromHead` operand swap is fixed (C:445-446).

Still open: wavetable osc-sync, reverb sidechain ducking (auto-mode param borrowing), stutter
chain position (dormant — no beginStutter caller), sample end-of-note sinc tail / stereo
samples, timestretch init/hop details, the volume-clamp upstream investigation, and the master
gain chain (blocked, §4.5).
