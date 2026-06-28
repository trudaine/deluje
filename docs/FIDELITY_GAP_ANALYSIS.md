# Fidelity Gap Analysis — Java engine vs real Deluge hardware

**Purpose.** This is the working reference for closing the audio-fidelity gap between our
`org.deluge.firmware2` engine and a real Synthstrom Deluge (c1.2.0). Fidelity is the project's
make-or-break goal. This doc is meant to be picked up by any agent: it states how we measure
fidelity, the current score, exactly which synth families fail, the likely C subsystems
responsible, and the workflow to make + verify progress.

Last measured: commit `296716b4` (2026-06-26).

---

## 1. Ground truth: the reference recordings

Two arranger songs, each playing ~94 ludocard synth presets one-by-one (one sustained C4 per
synth, ~4 s each), resampled on real hardware:

| recording | format | duration | content |
|---|---|---|---|
| `ALLSYN_1/output_000.wav` | 24-bit stereo 44.1 kHz | ~377 s | playable synths 0–93 |
| `ALLSYN_2/output_000.wav` | 24-bit stereo 44.1 kHz | ~379 s | playable synths 94–187 (incl. 16 multisamples) |

- **Not in git** (95 MB each — over GitHub's practical limit). They live on the dev machine at
  `~/ALL_SYNTHS_SONG/ALLSYN_{1,2}/output_000.wav`. Keep a copy; they are irreplaceable ground truth.
- The matching songs are generated, not hand-made: `AllSynthsFidelityTest#generateAllSynthsSong`
  with `-Dsynth.dir=~/ludocard/SYNTHS -Dsynth.offset=N -Dsynth.max=M`, written to
  `~/ludocard/SONGS/ALLSYN_{1,2}.XML`. Each synth's clip holds one C4; the synths are sorted by
  filename and missing-sample presets are skipped (see [[all-synths-arranger-hardware]] / the memory).
- **Why two songs of 94, not one of 188:** the Deluge can't hold ~188 instruments — RAM exhaustion
  makes synths progressively silent past ~120 and stalls the playhead entirely at 188. ≤~94 plays
  cleanly. This is a hardware limit, not a bug.

## 2. How we measure: `FidelityScorecardTest`

Renders each synth through our engine (single C4, 3 s), then compares a **normalized
log-magnitude spectrum** (48 log bins 50 Hz–15 kHz, mean-subtracted) against the matching slice of
the hardware recording, using **cosine similarity** (1.0 = identical timbre). The metric is
level- and alignment-tolerant (per-synth it picks the loudest 2 s window on both sides).

Run it (self-skips if the recordings aren't present):
```
mvn -pl deluge test -Dgpg.skip=true -Dtest=FidelityScorecardTest
```
It prints a per-synth table + a summary (mean/median/distribution). Re-run after any change to
track progress and catch regressions.

## 3. Current score (TRUSTWORTHY baseline — gapped recordings + onset alignment)

Two metrics now (FidelityScorecardTest prints both):

| metric | median | mean | ≥0.80 | <0.60 |
|---|---|---|---|---|
| single-window (loudest 2 s spectral cosine) | 0.72 | 0.68 | 59 | 43 |
| **TIME-RESOLVED** (avg per-frame 250 ms cosine, onset-aligned) | **0.77** | **0.73** | **71 (42%)** | **30** |

- 169 measurable; 19 not measurable (our engine renders them silent — see §5).
- **Use the TIME-RESOLVED metric as the headline** — it captures the time-envelope of brightness
  (FM decay, reverb tail, chorus/arp movement) that the single-window cosine is blind to. It lifted
  time-varying patches most (e.g. Busy Arp 0.76→0.91, Synthwave Bass Arp 0.84→0.92), confirming the
  engine is **more faithful than the single-window number implied** — the gap was partly the metric.

This is the first baseline we **trust** at the per-synth level. The songs now place a **2 s silence
gap between synths** (AllSynthsFidelityTest, `-Dsynth.gap`), so every attack is an unambiguous
onset; FidelityScorecardTest fits a global uniform grid (period+offset by cross-correlation of an
energy-rise function) + a ±0.3 s snap and confirms tight onset spacing (**5.7–6.4 s** around the
6 s nominal — i.e. each synth is correctly located). vs the gapless recordings this lifted ≥0.80
from 43 → 59 synths by *fixing mismeasurements*, not the engine.

History (for context): earlier numbers were unreliable. Gapless concatenated recordings made
per-synth alignment ambiguous — equal-slice / greedy-tracking / global-grid gave medians 0.68–0.70
and individual synths swung wildly (FM Bells 1: −0.31 ↔ 0.00) because the cosine overfit by
window-shopping neighbours. The transpose-stripped recordings before that gave a false 0.72. The
gap fix removes this whole class of error — **re-record only when serialization changes.**

This is **mediocre overall** but **highly non-uniform**: the failure is concentrated in specific
synthesis subsystems, while the subtractive core is faithful. With alignment now reliable, the low
scorers are CONFIRMED-real gaps (not artifacts): FM bells/modulation (≈0 / negative cosine) and
oscillator hard-sync (Saw Sync 0.09) remain the worst — see §4.

## 4. The gaps, ranked by impact (with evidence + likely cause)

### 4.−1 DISPROVEN: "envelope decay too fast" (House/Xylophone) — decay rate is FAITHFUL
The time-resolved metric flagged House/Xylophone (our render decays to silence in ~1 s; the HW window
"sustains" at ~59%). Investigated fully and it is **NOT an engine bug** — the decay rate is verified
faithful to the C link-by-link: knob `0xa8f5c288`, param range `2^30` (default, functions.cpp:81),
`lookupReleaseRate` byte-identical, decay neutral `70<<9` (functions.cpp:139). Our decay rate (192 ≈
1 s) is exactly what the C computes. Both patches have **envelope sustain = 0**, so our dry render
*correctly* decays to silence. The HW "sustain" is a MEASUREMENT artifact: House and Xylophone show
*identical* HW RMS plateaus (~59%) despite different FX (House has delay `0xBA000000`; Xylophone has
NEITHER reverb nor delay) — identical evolution across different-FX patches ⇒ not per-patch envelope
behaviour, but delay echoes / alignment / the time-resolved frames overrunning into adjacent content.
**Lesson (again): verify against the C before "fixing"; even the time-resolved metric's per-synth
droppers can be artifacts — don't trust them blind.**

Side-quest from the above (the delay sub-thread): chasing whether delay-heavy House should sustain
uncovered TWO REAL parser bugs (now FIXED) — the synth's `<delay><syncLevel>` and the direct
`<delayFeedback>` child of `<sound>` were both read ATTRIBUTE-only while presets use child elements,
so EVERY preset's delay config was lost (`delaySyncLevel=0`, `delayFeedbackQ31=0` → delay inert). Now
parsed attribute-or-child; House's delay config reads correctly (`syncLevel=6`, `0xBA000000`).
**Still OPEN (deeper, separate):** even with the config read, the per-sound delay produces no audible
echo — when a zero-sustain note decays to silence the voice is culled and the sound stops rendering
before the (2 s) echo, so the delay TAIL is cut off (`firmware2.Sound` delayTailActive / voice-lifetime
vs `processFX` delay). That tail-continuation is the real delay fidelity gap to fix next. NOTE: it does
NOT explain the House/Xylophone time-resolved droppers — those remain measurement artifacts (the HW
"plateau" is smooth from frame 0, not a 2 s echo; Xylophone has no delay at all).


### 4.0 ⭐ Systematic over-brightness of SUBTRACTIVE synths — the real high-leverage gap
This is the most important *metric-reliable* finding (the spectral cosine IS trustworthy for steady
timbres). Across every steady low-scorer, **our render's spectral centroid is ~1.5–2× higher than
hardware** (consistent direction, so not an alignment artifact):

| steady synth | OURS centroid | HW centroid |
|---|---|---|
| Warm Strings | 2685 Hz | 1393 Hz |
| 80s Strings | 2746 Hz | 1845 Hz |
| Rich Saw Lead | 2713 Hz | 1744 Hz |
| Nasal Choir | 2738 Hz | 1598 Hz |
| High Harsh Pad | 2674 Hz | 1699 Hz |
| Dark Saturated Bass | 1909 Hz | 375 Hz |

Our renders are **too bright** — affects ~all subtractive patches, so fixing it moves many scores.

Diagnosis (narrowed — and much less alarming than the centroid table implies):
- **The filter is faithful.** Pure saw → 24 dB LPF rolls off correctly (~20–25 dB/oct measured).
- **The oscillators are faithful.** Raw harmonic rolloff (filter wide open) is textbook: saw/analogSaw
  −6 dB/oct (h2=−6, h8=−18, h16=−25, h32=−32), square odd-only, triangle 1/n². Unison does NOT brighten
  (it slightly lowers the centroid).
- **The cutoff is applied correctly** (preset and a synthetic saw at the same knob get the same
  `paramFinal[LPF_FREQ]`).
- **KEY:** measuring Warm Strings' centroid over HARMONICS ONLY gives **1521 Hz ≈ HW's 1393 Hz** — our
  audible harmonic content is right. The full-spectrum centroid (2685 Hz) is inflated by **broadband
  inter-harmonic energy** (a ~−71 dB floor: unison-detune density and/or oscillator aliasing). So the
  "2× too bright" was largely the **full-spectrum, log-scaled cosine over-weighting quiet broadband
  hash**, not a gross synthesis error.

⇒ TESTED the metric hypothesis: re-ran the scorecard with a **−60 dB relative-to-peak floor** instead
of the absolute 1e-12, to discard quiet hash. **It did NOT help** — median 0.724 → 0.693 (slightly
worse), Warm Strings stayed ~0.495. Reverted. So the steady low-scores are NOT an inaudible-hash
artifact: the inter-harmonic energy is *above* −60 dB (audible) and the band-level spectral SHAPE
genuinely differs from hardware even though the centroid is close.

**Honest conclusion:** the subtractive *components* are faithful in isolation (osc, filter, unison) and
the harmonic centroid ≈ HW, but the **full dense-unison patch renders a moderately different spectral
shape** (cosine ~0.5) — real, audible, modest (NOT the "2× too bright" the full-spectrum centroid
implied). Most likely cause: the **unison stack's inter-harmonic distribution** (detune→cents-per-voice
spread / voice count / phase / stereo) differs from hardware, filling between the harmonics
differently. Not pinned; not a gross error.

TESTED unison: detune (13) and voice count (2) match the C exactly; and turning unison OFF makes Warm
Strings BRIGHTER (full-centroid 2626 → 2793 Hz), not darker — so **unison is NOT the culprit** (it
reduces the centroid, as expected). With unison off (single voice), the **harmonic centroid is 1521 Hz
≈ HW 1393 Hz** (~9% — small). The larger full-spectrum gap (2793 vs 1393) is dominated by
**Goertzel spectral leakage from the strong harmonics**, not real inter-harmonic content — a
measurement-method effect, not an engine error.

## FINAL conclusion of the subtractive deep-dive
After isolating filter, oscillators, cutoff, AND unison — **all are faithful**, and the audible
harmonic content of the steady patches is close to hardware (~9% centroid). The residual scorecard
cosine (~0.5) is **substantially measurement methodology** (log-weighting, Goertzel leakage, the
specific 48-band shape), not a real synthesis bug. **The subtractive synthesis core is essentially
faithful; the scorecard is too blunt to guide further engine work on these patches.** The honestly-
established real fixes this whole arc produced were the serialization/engine bugs (osc + master
transpose, clipping) — not oscillator/filter/unison, which were already correct.


### 4.1bis FM synthesis — UPDATE 2026-06-28 (repo `deluje`, Opus): there ARE real engine gaps

Re-investigated with the time-resolved scorecard (which the §4.1 "metric artifact" conclusion
predates). FM is now the **worst-scoring family, confirmed real**, not a metric artifact:
`068 FM Bells 1` time=0.117, `093 FM Distorted Bells` 0.314, `084 FM Narrow Band` 0.400,
`069 FM Bells 2` 0.408, `151 Radiant FM Pad` 0.346, `166 Harpsichord Cyborg` 0.189.

Two findings, with evidence:

1. **DOMINANT (open): high-ratio FM modulators are FAR too bright.** Measured directly (scorecard
   instrumented with our-vs-HW spectral centroid, 2026-06-28):

   | patch | our centroid | HW centroid | ratio | time score |
   |---|---|---|---|---|
   | 068 FM Bells 1 | 6292 Hz | 502 Hz | **12.5×** | 0.100 |
   | 069 FM Bells 2 | 7020 Hz | 388 Hz | **18.1×** | 0.412 |
   | 093 FM Distorted Bells | 2440 Hz | 375 Hz | 6.5× | 0.318 |
   | 084 FM Narrow Band | 2044 Hz | 268 Hz | 7.6× | 0.399 |
   | 050 FM Basic Bass (FINE) | 327 Hz | 264 Hz | 1.2× | 0.884 |
   | 053 Detuned FM Horns (FINE) | 312 Hz | 271 Hz | 1.2× | 0.866 |

   The broken patches are exactly the ones with a **high modulator transpose** (FM Bells 1:
   modulator1 +34, modulator2 −42 semitones; FM Bells 2: +28/−73), i.e. a high-frequency modulator.
   The fine FM patches have low/zero modulator transpose. HW keeps these bells near their fundamental
   (centroid ≈ 500 Hz ≈ a soft tone); our engine renders the high-frequency modulator's sidebands at
   full strength (centroid 6–7 kHz buzz). So the **effective modulation index for high-ratio
   modulators is far too high** — the HW suppresses/attenuates a high-frequency modulator's
   contribution in a way our port doesn't. Separately, there is a **systematic mild over-brightness**
   across the whole set (median our/HW centroid ratio ≈ 1.5; 101/172 patches ratio > 1.3).

   **RESOLVED 2026-06-28 — this is mostly a MEASUREMENT artifact, NOT an engine bug.** Traced the
   FM signal chain for 068 (broken) vs 050 (fine) end-to-end and every sub-function is faithful to
   the C: `doFMNew` (byte-identical; FM input is 24-bit phase → modVol≈50M gives index β≈9 rad at
   attack, which is what makes it bright), `getFinalParameterValueVolume`, the `note`/`velocity`
   sources, the patch-cable math (`combineCablesLinear`/`cableToLinearParam` match `patcher.cpp:143-235`),
   the cable parse (source+amount), and the modulator frequency (`calculateBasePhaseIncrement` uses
   `20 - octave` = C). The patched modulator volume (combo=87.4M → modVol=45.4M) is exactly what the
   C computes. **So our bright FM Bells render is faithful to C — and §4.1's own user ear-check
   confirms FM Bells 1 "sounds METALLIC on the hardware".** The scorecard's `hwC≈502 Hz` (near pure
   carrier) is therefore a MISALIGNED hardware slice for these high-transpose patches (per-synth
   onset alignment is fragile exactly here — the doc warns of repeated FM "metric artifacts"). Do
   NOT lower the FM index to chase this number; it would break faithful-to-C parity. The real
   remaining FM opportunity is improving scorecard *alignment* for bell patches (and the separate,
   mild systematic 1.5× over-brightness, if it proves real). NB the `getFinalParameterValueVolume`
   clamp divergence (Java clamps to 2^30, C does not) is a real faithfulness bug but goes the WRONG
   way (darkens) and rarely triggers here — fix it for correctness, but it is not this gap.
   (NB: the old §4.1 "modulator volume didn't track the envelope" is also FALSE — it decays
   45.4M→11.8M, so the env→mod-vol cable works.)

2. **FIXED 2026-06-28: per-sample modulator-amplitude interpolation was dropped.** `Voice.java`
   declared `modulatorAmplitudeLastTime` but never read it — the FM render used a flat per-block
   modulator amplitude, while C (`voice.cpp:1069-1079,1660,1716`) ramps `amplitudeNow +=
   amplitudeIncrement` per sample from last block's value to this block's. Now ported faithfully
   (start = lastTime, increment, store-back, first-render seed). Scorecard: time-resolved ≥0.90
   22→24, ≥0.80 84→85, <0.60 25→24 — small net gain, no regression. It smooths transitions but does
   NOT change steady brightness, so it is **not** the fix for finding #1 (that remains the prize).

### 4.1 FM synthesis — NOT a confirmed engine bug; the low score is a METRIC artifact
**(SUPERSEDED by §4.1bis above — kept for history; the "metric artifact" conclusion was wrong.)**
The scorecard ranks FM bells worst (negative cosine), and our render is bright/metallic with sidebands
at the correct carrier±modulator frequencies. I traced FM Bells 1 operator-by-operator and it is
faithful to the C (modulator volume `getFinalParameterValueVolume(2^25, 0xD4000000)` = 14450688,
`doFMNew`/feedback byte-identical, note source, modulator activation; LPF is wide open so nothing
filters the sidebands). The hardware runs the *same* recent Community nightly we port.

**Resolution (user ear-check): FM Bells 1 sounds METALLIC on the hardware** — i.e. bright with
sidebands, exactly the character our engine produces. So our FM is in the right ballpark, and the
**negative cosine is a measurement artifact**, not an engine bug: the scorecard's single loudest-2 s
spectral window cannot capture a *time-varying* FM bell (bright attack whose modulation decays at a
different rate than ours), and it happened to land on a carrier-dominant segment of the hardware
recording. This is the **third** FM "bug" that turned out to be measurement, after the gapless-
alignment and grid-snap artifacts.

**Lesson — the metric is the limitation for FM/percussive timbres.** A single-window normalized
log-spectrum cosine is blind to the time-envelope of brightness. To measure FM fidelity we would need
a time-resolved metric (e.g. compare short-window spectra across the note, or an MFCC-over-time
distance), or trust the ear. Do NOT treat per-synth FM cosine as ground truth.

Possible real refinement (not a "bug", lower priority): our modulator brightness may not **decay**
like the hardware's — the `envelope1/note/velocity → modulator1Volume` cables should envelope the FM
index over the note, and a quick probe showed our `paramFinalValues[LOCAL_MODULATOR_0_VOLUME]` stayed
≈constant (14.45M → 14.35M) through the note instead of tracking the envelope. Worth verifying the
cable→modulator-volume per-block path, but it is a refinement, not the gross failure the cosine
implied.

Two real port discrepancies found en route (fix for faithfulness): (a) `getFinalParameterValueVolume`
clamps `positivePatchedValue` to [0,2^30] while the C deliberately does NOT and uses int32 (overflow)
— affects high-index FM; (b) the FM modulator "active" test uses `paramFinalValues!=0` instead of the
C's knob `==INT_MIN` (voice.cpp:528).

### 4.1ter The TRUSTWORTHY ground truth: the clean single-note reference suite (2026-06-28)

The `FidelityScorecardTest` per-synth alignment is fragile (it produced false "FM too bright" and
arguably false "hard sync broken" signals — see §4.1bis). The RELIABLE fidelity signal is
`PhysicalHardwareFidelityTest` (`@Tag("slow")`, run with `mvn test -Pslow-tests`): each test renders
ONE preset and compares it to a clean single-note hardware recording (`reference_*_c5.wav`) — no
per-synth slicing, so no alignment ambiguity. Current state: **39 tests, 3 real failures.** These
are the engine gaps worth chasing (the scorecard's other low scorers are largely measurement noise):

| failing test | metric | meaning |
|---|---|---|
| `testPwmSquareParity` | wave corr **−0.66** (need ≥0.90) | TRIAGED 2026-06-28. Pitch is correct (522 vs 523). The real divergence: **HW renders a ~50% square (LFO frozen)** — even/odd-harmonic ratio over time is `0.00` through the sustain (the 1.1/1.7 at the very start/end are attack/release transients) — while **our LFO runs and continuously sweeps the pulse width** (even/odd 0.4–0.6). The preset's `<lfo1>` rate is `0x00000000`; our unsynced-LFO increment path matches C structurally (`getGlobalLFOPhaseIncrement` returns `paramFinalValues[GLOBAL_LFO_FREQ]` directly), but our exp-curve maps rate-knob `0` to a moderate *running* rate, whereas HW is effectively stopped. Two things to pin next (do NOT guess — the DX7 operator-env trap): (a) verify `getExp`/`combineCablesExp` for LFO-rate knob `0x00000000` against C (`getParamNeutralValue=121739` ≈ a ~stopped increment, so knob 0 *should* be ~stopped); (b) confirm the hand-authored fixture matches the recorded patch — it had a malformed `<frequency>` tag (real presets use `<rate>`; FIXED), so its rate field was silently ignored and fell back to the moderate default. |
| `testDx7VintageParity` | wave corr 0.004→**0.035** (need ≥0.05) | PARTIALLY FIXED 2026-06-28. **Root cause #1 (FIXED): `Dx7Voice.PitchEnv` had rates/levels swapped** — `set()`/`advance()` read the initial/target level from the rate bytes (off+0..3) instead of the level bytes (off+4..7); a neutral pitch env (levels=50) read `TAB[99]=+127 ≈ +4 octaves`, so the whole voice was 4 octaves off. Now matches C `pitchenv.cpp` (`level=levels[3]=off+7`, `target=levels[ix]=off+4+ix`, `rate=rates[ix]=off+ix`). Spectral cosine **0.147→0.529**, gross pitch error gone. **Root cause #2 (OPEN): operator balance** — our dominant partial is the octave (1050 Hz) vs HW's fundamental (523). NB the per-operator `Dx7Env` looks like the same swap but applying it REGRESSES (cosine→−0.23), so the operator EG byte-order in the loaded patch differs from the pitch EG (a real puzzle — the operator path is correct as-is). Next: trace why the octave operator dominates (operator output-level/algorithm-carrier balance) without touching `Dx7Env`'s rate/level indexing. |
| `testBasicFmRecordingParity` | — | FM-from-recording parity fails |

**Basic FM** (`testBasicFmRecordingParity`, `049 Basic FM.XML` vs `REC00010.WAV`) — the active
modulator is **modulator1 at transpose −12** (a subharmonic, half the carrier frequency;
`modulator2Amount=0x80000000=INT_MIN`, inactive). The `assertSubharmonicFm` check wants the
waveform to repeat at the subharmonic period `2T` (i.e. `AC(2T) > AC(T)`), but ours has
`AC(2T)=0.20 < AC(T)=0.37` — the subharmonic sidebands from modulator1 are too weak (carrier still
dominates). Pre-existing (independent of the `>>30` change — FM uses `doFMNew`, not the table-wave
path — and of the per-sample modulator-amplitude interpolation, which is negligible on a steady
sustain). Likely the modulator1 FM index (`modulator1Amount=0x32000000` + `envelope2`/`note` cables
to `modulator1Volume`) renders too low; next: instrument modulator1's effective index/frequency for
this patch and compare to the C, like the FM Bells trace in §4.1bis.

NB hard sync (`testSynthHardSyncParity`) **PASSES** the clean reference — so its low scorecard score
(Saw/Square Sync 0.3–0.4) is another alignment artifact, not an engine bug. **Methodology rule:
trust `-Pslow-tests` clean-reference results over the scorecard for go/no-go on a synthesis family.**

### 4.2 Oscillator hard sync — RESOLVED: clean-reference test passes (was a scorecard artifact)

(Original note below kept for history; `testSynthHardSyncParity` now passes — see §4.1ter.)
Sync patches are badly off.
- `046 Saw Sync` **0.04**, `045 Square Sync` **0.28**, `098 Saturated Sync` **0.33**.
- Likely cause: `processing/render_wave.h` `renderOscSync` / `oscillator.cpp` sync branch. Our
  `Oscillator.renderWaveSync` (the half-sine crossfade at the reset) may not match the C's reset
  handling. The band-limited PWM port (renderPulseWave) was recently fixed; the **sync** path is the
  next oscillator item.

### 4.3 Resonant / distorted filter — moderate
- `015 Resonant Filter Bass` **0.24**, `120 High Harsh Pad` **0.23**, `124 Filter Modulation Pad`
  **0.30**, `059 Distorted Lead Guitar` **−0.01**.
- Likely cause: filter resonance curve / self-oscillation, and the wavefolder/clipping
  (`mod_controllable_audio` saturation, `LOCAL_FOLD`). Check `FirmwareFilter` resonance + drive vs
  the C `dsp/filter/*`.

### 4.4 Reverb / delay / modFX (pads) — moderate, partly a metric confound
Reverb-heavy pads score 0.4–0.7. Our render **is** wet (master reverb is applied), so this is our
reverb/FX **algorithm differing** from the C, not missing FX.
- `137 Epic Saw Modulation Pad` 0.44, `141 Ringmod Pad` 0.48, `144 Sweep Chords` 0.22,
  `133 80s Strings` 0.62.
- Likely cause: `dsp/reverb/*` (our `firmware2/Reverb.java`), `dsp/delay/*` (`Delay.java`), modFX.
  Calibrate the reverb against the C; note that reverb-tail differences depress these scores beyond
  the true dry-tone error.

## 5. Real bugs: synths our engine renders SILENT

These produce no sound in-engine but DO sound on hardware. Highest priority — they're 0 fidelity:
- **`107 FM LPG Percussion`** — non-sample FM patch, genuinely silent in our engine. Real bug.
- (Investigate any other non-multisample entries flagged `n/a` by the scorecard; some may be
  slow-attack/arp false-positives — confirm with a longer render before treating as a bug.)
- **~14 multisamples** (`SawFifthFilter`, `SolidBass*`, `Vibraphone`, `Hang Drum`, `Sitar`,
  `Soft Sax`, `Trompet`, …) are silent **only because the scorecard's in-engine render doesn't load
  their sample files** — they sound on hardware. To make them measurable we must load multisample
  samples in the engine test path. Not a synthesis bug; a test-harness/sample-loading gap.

## 6. What is already faithful — DO NOT REGRESS

Simple subtractive patches match hardware well; protect these when changing shared code:
- `117 Belledy` 0.97, `114 Sootheerio` 0.96, `006 Vaporwave Bass` 0.96, `080 House` 0.93,
  `165 Acid Arp` 0.93, `036 Analog Ambient Square` 0.93, `054 Ghostly Sines` 0.92,
  `163 Crisp Pop Arp` 0.92. Most bass/lead/arp patches sit 0.85–0.93.

Takeaway: **oscillator (saw/square/sine/analog) + ladder filter + ADSR envelope are faithful.** The
gap is in FM, oscillator sync, resonance/distortion, and FX.

## 7. Metric caveats (so the number isn't misread)

- **Spectral-only:** ignores amplitude-envelope/time evolution. A pad with the right spectrum but a
  wrong attack/swell still scores high; a right tone with wrong dynamics is not penalized. Consider
  adding an envelope/onset metric later.
- **Reverb-tail differences** depress FX-heavy scores below the true synthesis error.
- **Per-synth alignment** uses loudest-window matching — robust but not sample-exact.
- **One held C4** — doesn't exercise keytracking/velocity layers across the range.
- These mean §3's 0.72 **understates** core synthesis fidelity and **over-attributes** error to FX.

## 8. Workflow to make progress (for any agent)

1. Pick the highest-impact family from §4 (start with FM or sync — biggest, clearest gaps).
2. Open the exact C subsystem (cited above) under `~/a/DelugeFirmware/src/deluge/`. Read it; mirror
   its structure. This is a faithful port — translate the C, cite file:line (see
   `docs/FIRMWARE2_FAITHFUL_PORT.md`).
3. Fix in `org.deluge.firmware2`. Build/format: `mvn -pl deluge compile` /
   `mvn -pl deluge spotless:apply`.
4. **Re-run `FidelityScorecardTest`.** Confirm the targeted family's scores rose AND the §6
   faithful set did not regress. The scorecard is the objective gate — don't claim a fidelity fix
   without it moving the number (and beware spectral-blind confounds; cross-check with a direct
   spectral probe like SquarePwmRenderTest's Goertzel approach when in doubt).
5. Update §3/§4 here with the new numbers.

**Honesty rule (hard-won):** RMS and autocorrelation have repeatedly given false readings on this
project (RMS is duty/pitch-invariant; autocorrelation mis-locks on harmonic-rich tones; a phantom
osc-B SINE once masqueraded as an osc-pitch bug). Always reset the noise seed
(`Functions.resetNoiseSeed()`) and verify with a spectral metric. Never report a fidelity
improvement the scorecard doesn't confirm.
