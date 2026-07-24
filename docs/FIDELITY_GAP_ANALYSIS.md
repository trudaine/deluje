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

**Update 2026-07-13 (current, post Double-Bass + multisample-OOM fixes):** n=188 (all presets now
measurable, "not-measurable: 0"), time-resolved median **0.800**, mean 0.756, ≥0.90: 27, ≥0.80: 94
(50%), <0.60: 25. (The table below is the original §3 baseline from this doc's earlier history —
see `docs/dsp_parity_review_2026-07-04.md` for the pass-by-pass progression from 0.77 to 0.80 that
superseded it. Re-run `FidelityScorecardTest` for the live number; don't trust either static table
indefinitely.)

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
**RESOLVED (2026-07-13) — was already fixed, just unverified/uncredited.** The tail-continuation
gate this paragraph flagged as open (`Sound` culling voices before the delay echo plays) turned out
to already be ported: `Sound.java`'s `delayTailActive = delay.repeatsUntilAbandon != 0` gate (with
`Delay.repeatsUntilAbandon`/`setTimeToAbandon`/`hasWrapped`) is a faithful, byte-for-byte match of
the C's `sound.cpp:2164-2166` skip condition — landed by commit `7ae7b83` ("port the per-sound delay
into the Sound FX chain"), which predates this doc entry. What was actually missing was a
**regression test for the reported scenario**: `PerSoundDelayTimingTest`'s only case used a
full-sustain envelope, so the voice never died during the render and the "voice dies before its
echo" path was never exercised end-to-end. Added
`delayEchoSurvivesVoiceDeathBeforeEchoTime` (`PerSoundDelayTimingTest.java`) against a new fixture
`TestDelayTailSurvival.xml` (= `TestDelayFidelity.xml` with `envelope1 sustain="0x80000000"`, so the
124 ms note fully decays and the voice unassigns within ~0.3 s): asserts the dry voice is silent by
0.5–0.85 s **and** the syncLevel-4 echo still lands at ~1.0 s. Passes — confirms the tail survives
voice death exactly as the C does. NOTE: this does NOT explain the House/Xylophone time-resolved
droppers — those remain measurement artifacts (the HW "plateau" is smooth from frame 0, not a 2 s
echo; Xylophone has no delay at all).


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
| `testPwmSquareParity` | wave corr **−0.66** (need ≥0.90) | TRIAGED 2026-06-28. Pitch is correct (522 vs 523). The real divergence: **HW renders a ~50% square (LFO frozen)** — even/odd-harmonic ratio over time is `0.00` through the sustain (the 1.1/1.7 at the very start/end are attack/release transients) — while **our LFO runs and continuously sweeps the pulse width** (even/odd 0.4–0.6). The preset's `<lfo1>` rate is `0x00000000`; our unsynced-LFO increment path matches C structurally (`getGlobalLFOPhaseIncrement` returns `paramFinalValues[GLOBAL_LFO_FREQ]` directly), but our exp-curve maps rate-knob `0` to a moderate *running* rate, whereas HW is effectively stopped. RESOLVED 2026-06-28 (commit by the user + follow-up): the LFO-rate fix (`isLfoRateKnobSet` + `resyncGlobalLFOs` + the `<frequency>`→`<rate>` fixture fix) took PWM **−0.66 → 0.80**. The residual 0.80-vs-0.90 gap is **faithful-to-C, not an engine bug**: a harmonic comparison shows HW is a clean 50% square (even harmonics ≈ 0) while ours has even harmonics (h2=0.32) + DC. Cause: with the LFO rate ≈ stopped, our unsynced global **triangle** LFO is frozen at its default phase 0, which is the *negative extreme* (`getTriangleSmall(0) = -2^30`), giving an off-center duty. The C does **exactly the same** — `LFO::phase{0}` default, `setGlobalInitialPhase` (which would center triangle/sine) is gated behind *synced* LFOs only — so a fresh C load of this patch also renders an off-center square. The HW reference shows 50% because the hardware's **global** LFO phase persists across loads and happened to be centered at record time (non-deterministic). Re-centering our LFO would *diverge* from C. Treat PWM as effectively closed. |
| `testDx7VintageParity` | wave corr 0.004→**0.035** (need ≥0.05) | PARTIALLY FIXED 2026-06-28. **Root cause #1 (FIXED): `Dx7Voice.PitchEnv` had rates/levels swapped** — `set()`/`advance()` read the initial/target level from the rate bytes (off+0..3) instead of the level bytes (off+4..7); a neutral pitch env (levels=50) read `TAB[99]=+127 ≈ +4 octaves`, so the whole voice was 4 octaves off. Now matches C `pitchenv.cpp` (`level=levels[3]=off+7`, `target=levels[ix]=off+4+ix`, `rate=rates[ix]=off+ix`). Spectral cosine **0.147→0.529**, gross pitch error gone. **CORRECTED 2026-06-28: the old reference was CORRUPT** (a sustaining INIT-voice tone). Re-recorded from hardware (REC00002, aligned to block 452). The new reference's dominant partial is ~1046 Hz, which **matches our render (~1050 Hz)** — so the "octave vs 523 Hz" gap was an artifact of the corrupt file (its 523 Hz was the INIT tone), NOT an operator-balance bug. The user also fixed the per-operator `Dx7Env` (parameter swap + sample-rate scaling) and DX7 engine-mode/random-detune plumbing. A **real spectral gap remains** (~0.10 log-bin / 0.03 256-bin vs the valid reference): the operator/sideband structure still differs, but pitch + decay are now right. Test `@Disabled` pending that engine fix. **Characterized 2026-06-28 (top spectral peaks):** HW = `1045 Hz (=2×523)` + `3140 Hz (=6×523)` — a **harmonic** series (modulator at an integer ratio, ~1, with the 2nd & 6th harmonics dominant). OURS = `345 / 525 / 690 Hz` — **inharmonic** sidebands (carrier 525 ± ~170 Hz ⇒ modulator ratio ≈ 0.32). So our DX7 **modulator renders at the wrong frequency ratio**. Patch decoded (active ops, algo 4): **OP6 ratio 1.0 / level 99** + **OP5 ratio 14.0 / level 82** (all others level 0). So the modulator is **ratio 14** (7322 Hz), but our render's modulator is ~170 Hz (**ratio ~0.32**) — a gross ratio error, not a subtle one. **VERIFIED 2026-06-28 — the operator ratios are CORRECT (hypothesis disproven):** `freqLookup` returns carrier 199063 / modulator 2786885 = **ratio 14.000**, exactly the patch's coarse-14 modulator. The C indexes op0=OP6 consistently for both patch reading (`env_p(op)=&patch[op*21]`) and the algorithm table, and our port matches — so there is NO operator-order mismatch. The 345/690 Hz peaks are **high-order sidebands aliasing/folding** (e.g. 523 + 6×7322 mod 44100 ≈ 355; −6×7322 + 44100 ≈ 691), i.e. a high-index ratio-14 FM whose high sidebands wrap, vs HW's clean low harmonics (1046/3138). So the real gap is the **FM index/spectrum** (operator output level → modulation index, and/or sideband band-limiting), the SAME family as the native-FM index question (§4.1bis) — NOT operator frequency. Needs ground-truth FM-index calibration (your ear / a known-good reference), as with the native FM. (Lesson: verified before changing operator order — which would have broken all DX7 — and the ratio turned out correct.) |
| `testBasicFmRecordingParity` | — | FM-from-recording parity fails |

**Basic FM** (`testBasicFmRecordingParity`, `049 Basic FM.XML` vs `REC00010.WAV`) — the active
modulator is **modulator1 at transpose −12** (a subharmonic, half the carrier frequency;
`modulator2Amount=0x80000000=INT_MIN`, inactive). The `assertSubharmonicFm` check wants the
waveform to repeat at the subharmonic period `2T` (i.e. `AC(2T) > AC(T)`), but ours has
`AC(2T)=0.20 < AC(T)=0.37` — the subharmonic sidebands from modulator1 are too weak (carrier still
dominates). Pre-existing (independent of the `>>30` change — FM uses `doFMNew`, not the table-wave
path — and of the per-sample modulator-amplitude interpolation, which is negligible on a steady
sustain). INSTRUMENTED 2026-06-28: modulator1 is active and at the **correct** frequency (130.7 Hz =
carrier/2, ratio 0.500), so it's NOT a dropped/wrong-frequency modulator. But its index is very high
(`modVol0 = 89M` → β ≈ 16 rad) and the autocorrelations are low overall (`AC(T)=0.37`, `AC(2T)=0.20`)
— the spectrum is very complex/bright, so the carrier-period structure edges out the subharmonic.
This is the same **FM-index magnitude** question as §4.1bis (modVol seems high); whether β≈16 is
faithful needs a direct spectral comparison to `REC00010.WAV`. (Also check whether unison random
detune is smearing the subharmonic — `detunePerVoice = getNoise()`.)

NB hard sync (`testSynthHardSyncParity`) **PASSES** the clean reference — so its low scorecard score
(Saw/Square Sync 0.3–0.4) is another alignment artifact, not an engine bug. **Methodology rule:
trust `-Pslow-tests` clean-reference results over the scorecard for go/no-go on a synthesis family.**

### 4.1quater FM-index calibration harness (`FmIndexAbHarness`, 2026-06-28)

`FmIndexAbHarness` (`@Tag("slow")`, `mvn test -Pslow-tests -Dtest=FmIndexAbHarness`) sweeps the
native-FM modulation index (via the `Voice.testFmIndexScale` test seam) for each native-FM patch
with a clean reference, scores each multiplier against the reference (log-bin spectral cosine), and
writes a WAV per multiplier to `$TMPDIR/deluge-fm-ab/` (incl. `*_HW.wav`) for A/B-by-ear. First run:

| patch | x0.25 | x0.5 | x1.0 | x1.5 | x2.0 | best |
|---|---|---|---|---|---|---|
| 049 Basic FM | 0.766 | 0.856 | **0.857** | 0.861 | 0.858 | ~flat (index-insensitive; spectrally ~0.86 = fine — the failing test is the brittle `AC(2T)` metric) |
| 103 FM Simple | **0.902** | 0.714 | 0.286 | 0.045 | 0.110 | x0.25 (monotone ↓ with index) |
| 117 FM Feedback | **0.793** | 0.502 | 0.061 | 0.063 | 0.169 | x0.25 (monotone ↓ with index) |

Signal: **FM Simple + FM Feedback match hardware far better at LOW index** (≈0.25×) — i.e. our
native-FM index reads too high for them. BUT FM Simple's reference is flagged suspect (§4.1bis: it
reads ≈ a pure carrier), so its x0.25 win may be a bad reference. **FM Feedback is the clean signal**
(not flagged): index x1.0→0.06 vs x0.25→0.79 ⇒ our FM (and/or its modulator feedback, which this
harness does NOT scale) is too hot. Basic FM is index-insensitive (≈0.86 throughout).

**Ear check (user, 2026-06-28): x0.25 is closer than x1.0 but "still off"** — HW reads as real FM,
just mellower than our faithful (x1.0) render. **Yet every FM sub-function is verified byte-identical
to the current C:** `doFMNew`, `getFinalParameterValueVolume`, the modulator-volume neutral
(`33554432 = 2^25`, matches C `functions.cpp:96-98`), note/velocity sources, the patch-cable math,
the feedback branch (`signed_saturate<22>`), and the modulator→carrier feed (no extra shift). So a
faithful-to-current-C render is HOTTER than these references.

**RESOLVED 2026-06-28 — the C5 FM fixtures are INVALID; the engine is faithful.** Re-recorded
`103 FM Simple` + `117 FM Feedback` on the hardware and re-ran the harness; at the matching octave
x0.25 still beat x1.0 (FM Simple 0.91 vs 0.73; FM Feedback 0.87 vs 0.42). That looked like a real
~4× index error — until the root cause: **`103_FM_SIMPLE_C5.XML` / `117_FM_FEEDBACK_C5.XML` use the
non-native `mode="fm" fmRatio="2.0" fmAmount="0.5"` attribute format**, which the real Deluge XML
schema does NOT have (native presets use `<modulator1Amount>` etc.). So the hardware can't read the
FM amount from these fixtures — it falls back to the default modulator amount (`INT_MIN` = **FM
off**, `sound.cpp` initParams), and **records a near-pure carrier** (exactly why `assertFmBrightness`
flagged FM Simple as "reads ≈ a pure carrier"). Meanwhile our parser maps `fmAmount=0.5` →
`(0.5*2-1)*MAX` = knob 0 = neutral modulator volume → β≈1 cycle (FM on). So the harness "index too
high" is this fixture-vs-hardware mismatch, NOT an engine bug — lower index wins because it
approaches the hardware's FM-off carrier.

Clincher: **049 Basic FM** (a NATIVE-format patch with real `<modulator1Amount>`, ref `REC00010`) is
**index-insensitive (~0.86 flat)** — the native FM path is faithful. Combined with the byte-for-byte
sub-function verification above, **the FM engine is faithful; the C5 FM fixtures are unusable for
hardware FM-index calibration.** `FmIndexAbHarness` now only includes native-format cases. To
calibrate FM index against real hardware in future, add NATIVE ludocard FM presets (e.g.
`068 FM Bells 1`) + re-recorded references, or re-author 103/117 in native `<modulator1Amount>` form.

### 4.2 Oscillator hard sync — RESOLVED: clean-reference test passes (was a scorecard artifact)

(Original note below kept for history; `testSynthHardSyncParity` now passes — see §4.1ter.)
Sync patches are badly off.
- `046 Saw Sync` **0.04**, `045 Square Sync` **0.28**, `098 Saturated Sync` **0.33**.
- Likely cause: `processing/render_wave.h` `renderOscSync` / `oscillator.cpp` sync branch. Our
  `Oscillator.renderWaveSync` (the half-sine crossfade at the reset) may not match the C's reset
  handling. The band-limited PWM port (renderPulseWave) was recently fixed; the **sync** path is the
  next oscillator item.

### 4.3 Resonant / distorted filter — REAL BUG FOUND AND FIXED (2026-07-13); ladder/fold themselves are faithful

`015 Resonant Filter Bass` and `059 Distorted Lead Guitar` are **not in the current 188-preset
ALLSYN scorecard set at all** (grepped the full run output — no hit for either name; likely a
stale reference from before the preset-numbering shift, e.g. the Iterance-preset expansion). Their
old 0.24/−0.01 scores can't be re-verified against the live scorecard; don't chase them from this
doc entry alone — re-derive from a fresh scorecard run if revisited.

`120 High Harsh Pad` and `124 Filter Modulation Pad` **are** live and were audited line-by-line,
Java vs C (`../DelugeFirmware/src/deluge/dsp/filter/*`): `LpLadderFilter`/`BasicFilterComponent`
resonance-feedback math, the resonance-threshold/tan lookup tables, and the wavefolder
(`Functions.foldBufferPolyApproximation`) are all **bit-for-bit faithful ports** — no divergence
found (neither preset even uses the wavefolder). The real bug was one level up, in **patch-cable
XML parsing**, not filter math:

**Bug (FIXED): the pre-V3.2 legacy "range"-destination patch-cable encoding was not parsed at
all.** Old-format presets encode "envelope/LFO X controls the DEPTH of cable Y" as two sibling
cables — one flagged `<rangeAdjustable>1</rangeAdjustable>`, another with a bare
`<destination>range</destination>` — which C's `PatchCableSet::readPatchCablesFromFile`
(`patch_cable_set.cpp:807-950`) resolves at end-of-parse by rewriting the "range" cable's
destination to target the flagged cable specifically (the same depth-modulation mechanism the
*current*-format `<depthControlledBy>` nested tag expresses). `InstrumentXmlParser`'s
`parseSinglePatchCable`/`parsePatchCables` only understood the new nested format; a legacy "range"
cable's destination string never matched anything in `FirmwareFactory.mapPatchCables`'s
if/else‑if ladder, so `paramId` stayed `-1` and the cable was **silently discarded** — meaning the
depth-controlled LFO/pitch/filter sweep played at a constant full depth from note-on instead of
being shaped by its controlling envelope. This is not a niche case: **19 of the ~190 bundled
SYNTHS presets** use this encoding (`grep -rl rangeAdjustable src/main/resources/SYNTHS`),
including two in the §6 "must not regress" faithful set (`114 Sootheerio`, `117 Belledy`).

Fixed in `InstrumentXmlParser.parsePatchCableList` (new): collects top-level cables, holds aside
any `destination="range"` cable, and folds it into the `rangeAdjustable`-flagged cable's
`depthControlledBy` list — the same internal representation the new-format parser already
produces, which the engine (`PatchCableSet.addRangeCable`, `Patcher.java` `targetSource`/
`targetParamId`/`rangeValue` machinery) already consumed correctly end-to-end; only the XML
parsing was missing. Added `LegacyRangeCablePatchTest` (asserts the range cable never survives as
its own top-level cable and correctly attaches as `depthControlledBy` on the flagged cable).

**Scorecard impact (confirmed, both single-window and time-resolved, before/after re-run on the
live 188-preset ALLSYN set):**
- `124 Filter Modulation Pad`: win 0.30 → **0.650**, time 0.30 → **0.824** (crosses the ≥0.80 bar).
- `120 High Harsh Pad`: win 0.23 → **0.404**, time 0.23 → **0.488** (more than doubled; still
  moderate — filter/fold math is faithful here, so 120's residual gap is elsewhere, likely
  oscillator content at its extreme `lpfFrequency=0x7FFFFFFF` cutoff).
- No regression: `114 Sootheerio` win 0.915→0.898, time 0.879→0.869; `117 Belledy` win 0.929→0.926,
  time 0.898→0.908 — movements of ±0.01–0.02, within run-to-run noise, not a real regression (the
  doc's old 0.96/0.97 figures for these two were already stale pre-fix, per a same-commit
  pre/post-fix scorecard rerun).
- Overall: time-resolved median held at **0.800**, mean **0.756**, ≥0.80 count **94→95**,
  <0.60 count unchanged at **25** — a clean, isolated improvement.

### 4.4 Reverb / delay / modFX (pads) — REAL BUGS FOUND AND FIXED (2026-07-13); a tempting C-fidelity "fix" was tried and REJECTED by the scorecard

`141 Ringmod Pad`'s low score is **not an FX gap at all** — its XML has `modFXType=none`,
`reverbAmount`/`delayFeedback` both at their off/minimum value. It uses `<mode>ringmod</mode>`
(ring-modulation oscillator synthesis), so its gap belongs in a synthesis-mode bucket, not here.
An audit of the ring-mod combine path (`Voice.java` `renderRingModPath` vs C `voice.cpp:1326-1396`)
found the multiply/gain-compensation math **bit-for-bit faithful** (base `1<<27` constant,
per-osc-type compensation shifts, two-stage `multiply_32x32_rshift32`/`..._rounded`, all matching,
same signal-chain position). Not pinned further; likely upstream (PWM rendering or
resonance-patch-cable interaction feeding the multiply, where small osc/filter errors get
nonlinearly amplified rather than just summed) — leave for a future pass.

Of the genuinely FX-driven presets — `133 80s Strings` (near-max reverb send, `reverbAmount=
0x7FFFFFFF`), `144 Sweep Chords` (delay+reverb), `137 Epic Saw Modulation Pad` (phaser+delay+light
reverb) — an audit of `Reverb.java`'s Freeverb port against C (`dsp/reverb/*`) found the comb/
allpass tuning constants, scale constants, and `roomSize`/`damping` coefficient mapping **all
bit-for-bit faithful** (buffer lengths, `SCALEWET`/`SCALEDRY`/`SCALEROOM`/`SCALEDAMP`, the Q31
saturating arithmetic — no divergence).

**But two REAL, unrelated bugs surfaced during that audit, both fixed:**

1. **`FidelityScorecardTest` never called `engine.syncMasterEffects(project)` at all.** Every
   preset's per-C4 render went through `masterReverb` with its raw Java field defaults
   (`roomSize=0, damping=0, width=0` — `Reverb.Container`'s fields have no initializer) — a
   near-degenerate reverb **regardless of the preset's actual `reverbAmount` send**. Fixed by
   adding the missing call, matching what `FidelityTestRunner`-based tests already did.
2. **`FirmwareAudioEngine.syncMasterEffects` never wired the reverb `model` (Freeverb/Mutable/
   Digital) at all** — only `roomSize`/`damping`/`width` were synced. So even a saved song
   explicitly specifying `<reverb model="1">` (Mutable) would silently render through Freeverb in
   the offline engine. Fixed to mirror the mapping `PureFirmwareEngine` already used for the live
   engine (`case 1 -> MUTABLE, case 2 -> DIGITAL, default -> FREEVERB`).

**Along the way, a genuine C-fidelity claim was tested and REJECTED — a good example of this
project's "verify via scorecard, don't trust the C-citation alone" rule paying off.** A fresh C
song's real defaults (`song.cpp:179-188`) are `roomSize=0.6, damp=0.72, width=1, model=MUTABLE` —
our `ProjectModel`/`BridgeContract` defaulted to `damping=0.5, width=0.5, model=FREEVERB` (0).
Correcting `damping`→0.72 and `width`→1.0 (still keeping Freeverb) is neutral-to-slightly-positive
(133: time 0.601→0.612; overall median 0.800→0.801) and was kept. **But also flipping the default
model to MUTABLE (matching the C literally) was tried and REGRESSED the scorecard**: `133 80s
Strings` time 0.601→0.536, `137 Epic Saw Modulation Pad` time 0.799→0.775, overall median
0.800→0.798. Isolated via a 3-way rerun (original / Freeverb+corrected-damp-width / Mutable) to
confirm the regression was specifically the model switch, not the sync-call or damping/width
fixes. **Reverted the model default back to FREEVERB (0)** — kept as the default despite C's fresh-
song default being MUTABLE, because either the reference recordings' actual song used Freeverb (it
may predate the Mutable-reverb firmware feature) or our `MutableModel`/`DigitalModel` Java port
has its own unaudited divergence from C; either way the scorecard is the objective gate and it says
no. **Do not flip this default again without new evidence** (e.g. an audited, scorecard-confirmed
`MutableModel` fix, or confirmation of what model the reference hardware session actually used).

**Net scorecard effect of what was kept (sync-call fix + corrected damping/width, Freeverb
default):** `133 80s Strings` time 0.601→0.612, `137 Epic Saw Modulation Pad` time 0.799→0.796,
`144 Sweep Chords` time 0.774→0.774, overall time-resolved median 0.800→**0.801**, mean unchanged
at 0.756 — small but real, no regression anywhere checked (`114 Sootheerio`/`117 Belledy`/`141
Ringmod Pad` all unchanged).

**`Delay.java` and the phaser branch of `ModFx.java` also audited (2026-07-13) — both bit-for-bit
faithful, no bugs found.** Delay's read/write/feedback core, the 40Hz feedback-path HPF, analog-vs-
digital saturation paths, and buffer-swap accounting all match `dsp/delay/delay.cpp` exactly (one
inert micro-divergence: Java's ping-pong swap doesn't gate on `AudioEngine::renderInStereo` since
this project's offline renderer always renders stereo — behaviorally a no-op, not a bug). Phaser's
6-stage allpass topology, `a1` coefficient formula, feedback injection, and LFO sourcing all match
`ModFXProcessor.cpp` exactly. **So delay and phaser are ruled out** as the cause of 133/144/137's
residual gap.

**The remaining modFX branches — chorus, chorus-stereo, flanger, warble, dimension — also audited
(2026-07-13), also bit-for-bit faithful.** Setup (`setupChorus`/`setupModFXWFeedback`), the
per-sample delay-line read with 16.16 fixed-point linear interpolation, the stereo/second-tap
condition, all five write-back/feedback paths, the main-loop LFO routing (including warble's
independent second LFO and the `TRIANGLE`/`SINE`/`WARBLER` wave selection per mode), and the
`Lfo.java` `warble()` second-order filter all match `ModFXProcessor.cpp`/`lfo.h` term-for-term. So
**every modFX mode, delay, and reverb are now confirmed faithful** — the entire FX chain audited to
the same line-by-line standard, zero bugs found beyond the two already fixed (§ above: missing
scorecard sync, missing model wiring).

**Still open, unexplained:** the residual itself (133/144/137 still sit at 0.61–0.80 despite every
individual DSP kernel in their signal path — osc, ladder filter, Freeverb, Delay, and now all of
modFX — being confirmed faithful). With no single-subsystem bug left to find in the obvious places,
the remaining gap is likely diffuse: the specific patch-cable-driven modulation depth/rate/send
values these three presets use, a compounding of several individually-faithful stages, or something
outside the FX chain entirely (e.g. the master gain/compressor chain, already flagged §4.5 as
faithfulness debt). **Do not keep blind-auditing whole subsystems here** — the return on that
approach has now gone to zero across seven consecutive audits (ladder, wavefolder, patch-cable
math, ring-mod, Freeverb, Delay, all modFX modes); revisit only with a specific new, falsifiable
hypothesis (e.g. a direct spectral probe of one preset's dry vs. wet signal to localize exactly
which stage introduces the divergence) rather than another full-subsystem line audit.

### 4.5 Master gain chain — BLOCKED on a C-execution / calibrated-hardware reference (2026-06-29)

The master/oscillator gain chain diverges from C per-stage: oscillator amplitude (`>>30` vs C's net
`>>32`), a `×1.25` master pre-multiply (C has none), final output `lshiftAndSaturate(…, 4)` (`<<4`)
vs C's `>>1`, and the `getFinalParameterValueVolume` clamp to `[0, 2^30]` (C is unclamped). DONE: the
invented DC blocker was removed (faithful, verified neutral — §see commit). The rest is **deferred,
and after a disciplined attempt, confirmed not completable here.**

Measured per-stage (full-volume sine):
`voiceSum 0.107 → ×1.25 premult → compressor → 0.026 → <<4 → 0.42 final`.
Applying the C's faithful master (no premult, `>>1` final) to our voice-sum gives ≈ **0.01 (−39 dB)**
— absurd for one note. So the C's voice output must be ~30× louder than ours; our `<<4`/premult are
**compensating a quieter voice stage**, and the net is set partly by the **nonlinear compressor**
(by-inspection scale math was off by 40–160× every attempt).

Why blocked: matching the C needs its **actual per-stage sample values** — which requires *running
the C firmware* (embedded ARM, not desktop-buildable) or a *level-calibrated hardware capture* (the
recordings have unknown gain; `FidelityScorecardTest` is amplitude-invariant). With neither, any
change just re-tunes to our current level (reshuffling, not verifiable faithfulness) at high
regression risk. Corroboration: the `MASTER_VOLUME_NEUTRAL` comment notes a prior `Q31.ONE` attempt
"drove the compressor ~13× too hot and broke hardware shape parity."

Impact note: this is amplitude-invariant for per-synth **spectral** parity (the project's main goal,
already ~0.79), so it does NOT affect "same synth sounds." It only governs saturation onset /
inter-track balance. **Recommendation: leave the chain stable** until a C-execution buffer dump or a
calibrated hardware loopback exists; then it becomes a clean by-construction stage-by-stage port.

### 4.6 Reference-validity audit (`scripts/audit_references.py`, 2026-06-30, no hardware)

`python3 scripts/audit_references.py` scans every `reference_*.wav` (pure WAV analysis, no engine/
build) and reports format, dominant pitch, fundamental presence, and clipping. Robust findings:

- **CLIPPED references (bad amplitude ground truth — measured peak = 1.0000, not a heuristic):**
  `reference_reverb_tail_saw_c5.wav` (**100%** of samples at full scale — useless as a reference),
  `reference_delay_trail_saw_c5.wav` (74%), `reference_eight_voice_unison_saw_c5.wav` (71%). These
  are the FX-tail/dense-unison patches the gap doc already flags as scoring 0.4–0.7 "partly a metric
  confound" (§4.4) — now we know part of that is the *reference itself being clipped*, so their low
  scores overstate the engine error. Re-record at lower gain when hardware is available; until then,
  treat their scores as unreliable.
- **`sw_render.wav`** — a 16-bit *software* render living in the hardware-reference dir; not a
  reference. Stray; safe to remove (left in place pending user confirmation).
- **Weak heuristic (manual follow-up, NOT conclusions):** "FUND-ABSENT" flags filtered/modulated/
  bell patches whose fundamental is legitimately weak (e.g. DX7 is correctly octave-dominant; the
  `_c4` saws are 2nd-harmonic-dominant). Don't auto-trust these.

Limitation: a pure-WAV audit can't distinguish "engine wrong" from "reference wrong" for spectral
shape — only clipping/silence/gross-pitch are unambiguous. The trustworthy reference-vs-render check
remains `PhysicalHardwareFidelityTest` (clean refs) / `FidelityScorecardTest`. This audit's value is
catching *unusable* references cheaply.

### 4.7 Multisample loading — FIXED (2026-06-30): 11 silent multisamples now render

The scorecard's ~16 "silent" multisamples were a **parser** bug, not synthesis: the real Deluge
format puts `fileName`/`rangeTopNote`/`transpose` on each `<sampleRange>` (with positions on its
child `<zone>`), but the parser read `fileName` off the `<zone>` (always empty) so no keyzones
loaded → silent. Fixes:
- `DelugeXmlParser.parseSampleRangeZones`: read `<sampleRange>` (attribute AND child-element vintages
  via `intAttrOrChild`), contiguous `rangeTopNote` → pitch ranges, carry per-zone `transpose`.
- `KeyZone`/`Sound.CompiledKeyZone`: add `transpose`; `Voice` applies it as the authoritative
  multisample tuning (matches C `SampleHolderForVoice::transpose = round(60 - midiNote)`), falling
  back to the WAV-root only when absent.
- `FirmwareFactory.resolveSample`: case-insensitive component resolution (presets store
  `Multisamples` vs on-disk `MULTISAMPLES`; FAT32 is CI but Linux isn't — `playable()` used CI
  `ciExists` while the loader used case-sensitive `File.exists`, so files "present" for the filter
  failed to load).
- `Sound`: guard against a matched zone whose sample failed to load (prevents an NPE the parser fix
  exposed for presets with unreadable WAVs).

Result: scorecard n/a **16 → 5**; n 172 → 183; recovered synths score well (Soft Sax 0.90, Hang Drum
0.94, SolidBass* 0.76–0.84, Secret Choir 0.89 — the high cosines confirm correct pitch/transpose).
Remaining 5 n/a (at the time): `169 Double Bass` (its `.WAV` files won't load — reader issue,
separate) + 4 short/percussive (Vibraphone/Tube Slap/Stone Skip/Wood Flute Verb) that DO render in
isolation but fall below the scorecard's 2 s-RMS "silent" threshold.

**CORRECTION 2026-07-13: the "measurement-window, not an engine bug" verdict on those 4 was WRONG
— see §5.** It was never a windowing issue (all 4 individually clear the threshold by a wide margin
in isolation); it was `AudioFileReader`'s unbounded sample-decode cache exhausting the scorecard's
JVM heap ~183 presets into the sequential run. Fixed; see §5 for the mechanism and evidence. Both
the Double Bass and the 4-multisample entries are now closed.

### 4.8 Note-84 preset scorecard (2026-07-01): core faithful; one real saturation bug

`PresetScorecardTest` renders the 28 hand-authored single-feature presets at note 84 (the Deluge's
"C5" = 1046 Hz — the octave the references were recorded at; confirmed by the dry-sine take peaking
at 1046) and scores each vs its `preset_refs/` reference. Baseline: n=27 mean 0.68 median 0.72,
9 ≥ 0.80.

**Faithful (high-confidence, distinctive spectra):** resonant LPF 0.965, resonant HPF 0.895, LFO
tremolo 0.918, LFO vibrato 0.901, unison 0.860, PWM-static 0.855, FM bell 0.810, dry saw 0.814. The
subtractive core, filter, LFO, unison, and PWM are faithful.

**Low subtractive scores are mostly a METRIC ARTIFACT at this pitch, not bugs.** At note 84
(1046 Hz) the band-limited oscillators are nearly sinusoidal — measured square/saw harmonics are
weak (h3 ≈ 0.07–0.12, not the theoretical 0.33) on BOTH our render and the hardware, and they match
each other. So the log-spectral cosine is dominated by quiet inter-harmonic/noise-floor differences
(the same over-weighting the §4.0 subtractive deep-dive found), giving e.g. dry-square 0.50 despite
matching harmonics. To score subtractive timbre meaningfully the presets should be recorded at a
LOWER note (rich harmonics); note 84 is too high.

**One CONFIRMED real bug — saturation/drive attenuates instead of saturating (`T28`, 0.046).**
Measured a full-vol saw at note 84 through `clippingAmount`: clip 0 → rms 0.19, clip 2–15 → rms 0.09
(HALVED, and flat across the range), clip 20 → rms 0.004 (collapses); the pre-saturation signal is
already ~0.09 fs and `getTanHAntialiased` returns ~0 for a saturated input. The hardware `T28`
(clip 20) is rms **0.245** with strong odd harmonics (h3 0.29 vs our 0.09) — drive should ADD
harmonics and hold/boost level; ours guts it. NOT the final `<< shiftAmount` (C uses a plain shift
where we used `lshiftAndSaturate`, but matching it changed nothing) — the attenuation is upstream in
the tanh-saturation math / its interaction with the pre-scale. Real, but needs a careful faithful
pass over `getTanHAntialiased` + `saturate` (`functions.h:286`, `sound.h:290`) vs the tanH2d table
scaling; do NOT hack the level. (Also verify `clippingAmount=20` is in the hardware's valid range.)

### 4.9 Note-60 (C4) preset re-record CONFIRMS the subtractive core is faithful (2026-07-01)

Re-recorded T01–T15 at note 60 (C4 = 262 Hz — confirmed by the dry-sine take peaking at 262) into
`preset_refs_c4/`, and scored with `-Dpreset.note=60 -Dpreset.refs=preset_refs_c4`. At this
rich-harmonic pitch the subtractive scores jump vs the note-84 near-sinusoidal regime, **confirming
§4.8's "low subtractive scores are a metric artifact" conclusion**:

| preset | note 84 | note 60 |
|---|---|---|
| dry sine | 0.71 | **0.991** |
| dry square | 0.50 | **0.935** |
| dry saw | 0.81 | **0.913** |
| lpf_saw | (silent) | **0.931** |
| pwm_static | 0.855 | 0.876 |
| saw_sync | 0.715 | 0.854 |
| lpf_12db | 0.774 | 0.828 |

n=15 mean 0.73 median 0.80. **The oscillator + filter + PWM + sync core is faithful** — dry-square
0.50→0.935 is the headline (the note-84 number was pure metric noise from the rolled-off harmonics).

One low score is a SUSPECT REFERENCE, not an engine bug: `T06 dry_analogSquare` 0.075 — the hardware
take has a dominant h6 (0.82, an EVEN harmonic) and an inconsistent peak (178/939 Hz), which is not
a physical square. Our analogSquare is weak (quasi-sine, h3 0.11) and may have a minor real issue,
but the reference is unreliable — re-take analogSquare cleanly before investigating. Noise (T07) is
inherently uncorrelatable. The resonant/HPF scores (0.66–0.74) are the honest remaining subtractive
question at C4 (higher than the near-sinusoidal note-84 flattered them) — worth a look, reference-gated.

**Resonant LPF (`T09`) investigated 2026-07-01 — real ladder-filter instability, deferred.** The C4
reference is clean (sustained 0.21 rms, normal decay; resonance peak at h2/524 Hz, smooth rolloff).
Our render diverges in SHAPE (the score is amplitude-invariant): a **low-frequency
self-oscillation/instability** — 16× sub-fundamental at ~150 Hz — absent from the hardware, with the
resonance peak at h4 (1046 Hz) instead of h2. Verified FAITHFUL and NOT the cause: the `setConfig`
resonance math (`resonanceUpperLimit` clamp, cold-ladder branch) and the makeup `filterGain`
(`gainModifier`, `<<3`, `*0.8`) match `lpladder.cpp:150-171` line-for-line; the reference is clean;
T08 (same cutoff, no resonance) scores 0.931 so the cutoff mapping is ~right. So the bug is in the
ladder RENDER loop or a fixed-point interaction that goes unstable at high resonance + high
cutoff/fundamental ratio (it's masked at note 84 where the cutoff sits near the fundamental — T09
scored 0.965 there). Fixing needs a dedicated faithful review of `LpLadderFilter`'s per-sample ladder
processing vs the C; do NOT hack it (many filter cases pass: T08 0.93, resonant 0.96 at note 84).

**Update 2026-07-01 — ladder-filter faithfulness fix (scorecard-neutral; did NOT resolve T09).** A
review of the per-sample ladder found a systematic port error: every plain `<< n` in the C
(`scaleInput` `<<3`/`<<2`, `do24dB` feedbacksSum `<<2` + cascade `<<1`, `do12dB` `<<1`) had been
ported as the CLAMPING `lshiftAndSaturate`, which alters the nonlinear feedback at extreme
resonance. Fixed all to plain shifts matching `lpladder.cpp`. No regression (full suite green bar
the known-flaky UI tests; T08 0.931 / T12 0.828 unchanged) — but T09 is bit-identical (0.695), so
the clamp never triggered for this signal and was NOT the cause of the sub-oscillation. The T09
residual (resonance-peak position / low-frequency content) is still open and lives elsewhere in the
ladder — a subtler tuning/fixed-point issue, not the shifts.

### 4.10 Ladder fully bit-audited faithful; `fc` clamp fixed; T09 root cause is NOT in the ladder (2026-07-06)

Extended the §4.9 ladder review to the pieces prior passes never checked, doing a Java→C
line-by-line read of the **entire** 24 dB path for the T09 (resonant LPF, note 60) regime:
`setConfig`, `scaleInput` (lpladder.h:52), `do24dBLPFOnSample` (lpladder.cpp:345), the
`BasicFilterComponent` integrator/feedback (`doFilter`/`doAPF`/`getFeedbackOutput*`,
ladder_components.h:27-48), AND the base-class `curveFrequency` + `instantTan`. Result: **the whole
ladder DSP path is faithful for T09's parameter regime.**

**One real faithfulness bug found + fixed (commit `2bcd277c`):** `Filter.curveFrequency` computed
`fc` with the clamping `lshiftAndSaturate(…, 4)` where C `filter.h:135` uses a **plain (wrapping)
`<< 4`**. `fc` feeds `moveability`, the core ladder coefficient — same plain-`<<`-vs-saturating-`<<`
class as the §4.9 fixes. **Scorecard-neutral / bit-identical everywhere** (FidelityScorecardTest
time-resolved median 0.800, PresetScorecardTest note-60 T08 0.931 / T09 0.697 / median 0.827
unchanged) because no in-range patch has a cutoff extreme enough to overflow int32 — it only diverges
near Nyquist, so it's a pure faithfulness correction, zero regression. **The identical divergence
exists in `instantTan`** (functions.cpp does `(a+b) << 1` in int32 = wraps; Java promotes to `long`
and clamps to INT_MAX) — left as-is because matching C's wrap yields negative-tan nonsense at the
near-Nyquist extreme; revisit with the golden-buffer harness, not by eye.

**T09 is unmoved (0.697 ≈ prior 0.695).** The valuable negative result: the instability is **NOT in
the ladder math** (now comprehensively verified faithful). It lives upstream — the resonance/cutoff
**param values** fed into `setConfig` (patcher/paramFinal path), or the reference — and pinning it
needs **sample-level C diffing** (a standalone C golden-buffer harness for `LpLadderFilter`), not
another read-audit. Do not re-run the ladder read-audit; it's done.

### 4.11 T28 drive/saturation is FAITHFUL — a downstream symptom of the §4.5 gain debt (2026-07-06)

Re-opened §4.8's "one confirmed real bug" (T28 drive attenuates instead of saturating). Traced the
per-voice saturation end-to-end against C: `Voice` saturation loop (voice.cpp:1553-1565),
`Sound::saturate`/`getShiftAmountForSaturation` (sound.h:286,290 — the Java `Sound` override
correctly uses `(clip>=2)?clip-2` distinct from `GlobalEffectableForClip`'s `>=3 / -3`),
`getTanHAntialiased` + `interpolateTableSigned2d` (functions.h:294,244) with the 129×65 `tanH2d`
table. **All faithful.** The algebra is the tell: net output shift = `(clip-2) − (5+clip+1) = −8`,
**independent of clippingAmount** — which is *exactly* the measured flat level across clip 2–15. So
the attenuation is inherent to the (faithful) soft-saturator, NOT a bug in it.

**Root cause: the tanh is a level-dependent waveshaper fed a signal ~30× too quiet** (§4.5: our
voice stage is ~30× below C's). At 0.09 fs input the pre-scale doesn't drive the tanh into its
compression/boost region, so it applies its ~0.5 linear gain and the `<<(clip-2)` makeup can't
recover it; on C the near-full-scale input compresses-with-harmonics. **T28 is therefore blocked on
the same master/voice-gain calibration as §4.5** — do NOT hack the saturation math (prior local
attempts "changed nothing" for this reason). Unblocks together with §4.5 via a C-execution buffer
dump or level-calibrated hardware capture.

**Method note (2026-07-06):** the GC-allocation commit wave (`e80178a8`…`d3e3d31a`, reusing scratch
buffers inside `FmCore`/`Oscillator` sync/`WaveTable`/`DX7` render loops) was re-scored to rule out
a stale-buffer timbre regression: **CAL bit-identical (median 0.810), full scorecard time-resolved
median 0.800 (n=183)** — confirmed timbre-neutral.

### 4.12 FM is a CONFIRMED real gap on valid ground truth — §4.1bis "artifact" verdict OVERTURNED (2026-07-06)

A purpose-built isolated FM recording (`FM_CAL`: 7 native ludocard FM presets, one C4 each, 4 s
gap so bell tails decay; `FmCalibrationScorecardTest`) gives the FIRST alignment-unambiguous FM
ground truth — recorded on firmware nightly `9456095b` (= our port reference). Onset detection is
clean (gaps 7.98–8.02 s) and the **anchor `050 FM Basic Bass` scores time=0.863**, a known-faithful
low-index patch — so the recording, alignment, and metric are all validated. Against that control:

| preset | time @ x1.0 (faithful) | time @ x0.0625 |
|---|---|---|
| 068 FM Bells 1 | **0.196** | 0.864 |
| 069 FM Bells 2 | 0.596 | 0.872 |
| 084 FM Narrow Band | 0.385 | 0.875 |
| 093 FM Distorted Bells | 0.476 | 0.898 |
| 095 Harsh FM Feedback | 0.638 | 0.767 |
| 050 FM Basic Bass (anchor) | 0.863 | 0.929 |

**This OVERTURNS §4.1bis's "mostly a MEASUREMENT artifact, NOT an engine bug" resolution.** That
verdict rested on fragile per-synth alignment inside the big ALLSYN song and on the invalid C5 FM
fixtures (§4.1quater). With a clean isolated recording + a validated anchor, the FM bells are
genuinely too bright — a REAL engine gap. (Textbook case of CLAUDE.md's "past 'resolved/faithful'
verdicts masked real divergences — re-verify, don't inherit.")

**But the index sweep (`FmIndexSweepTest`, via `Voice.testFmIndexScale`) does NOT isolate the
cause.** The time-resolved cosine improves *monotonically* as the modulator index is lowered, all
the way down to x0.0625 (the lowest tested) — no clean optimum, NOT a tidy 2-bit/×4 shift. A
monotonic "dimmer is always better" is consistent with EITHER (a) a constant index-scaling error,
OR (b) our modulator amplitude not DECAYING over the note like hardware's, so a low *static* index
just approximates the hardware's *decayed average*. A static-scale sweep cannot tell these apart.
**Do NOT apply a multiplier** — that hacks the level without identifying the bug, and risks the
§4.1quater "approaching FM-off carrier" trap. (`107 LPG Percussion` is flat across all scales — a
correct control; its FM isn't driven by this seam.)

**Decisive next step: the golden-buffer dump.** Compare the real firmware's per-sample FM modulator
envelope (and output) for `068 FM Bells 1` against ours — that disambiguates constant-scale vs
envelope-decay and pins the exact divergence. Baseline dump firmware builds from `trudaine/DelugeFirmware`
`fork/main` (= `9456095b`) on this machine (see the calibration/hardware notes); the debug SysEx
buffer-dump command is the tool to add. Until then, FM is "confirmed too bright, cause not yet
isolated" — not "faithful."

### 4.12bis RESOLVED (2026-07-06) — the FM gap is a MISSING MODULATOR-ENVELOPE DECAY (golden-buffer tap)

Built the golden-buffer tap into the firmware (`trudaine/DelugeFirmware` branch `feat/dsp-buffer-dump`:
a Debug-SysEx command that captures the master output; onset-synced via auto-arm on `Voice::noteOn`),
drove it headlessly over USB (`HardwareDspTapTest` + `DspTapCodec`), and captured real `068 FM Bells 1`
output from hardware. Comparing **same-note** windows (our render at the captured notes via
`FmEnvelopeProbeTest`), with brightness = fraction of energy above 2 kHz:

| | HARDWARE | OURS |
|---|---|---|
| Attack @E6 | 0.838 | **0.837** |
| Tail @C5 | 0.370 | **0.997** |
| brightness retained attack→tail | **0.44** | **1.19** |

**Our FM ATTACK is faithful (0.837 ≈ 0.838)** — the index at onset is correct, NOT a constant-scale
error.

**CORRECTION 2026-07-06 — the "missing modulator-envelope decay" conclusion below was WRONG; the
modulator envelope IS faithful.** Added a second tap source (per-block modulator amplitude,
`paramFinalValues[LOCAL_MODULATOR_0_VOLUME]`, SysEx subcmd 6) and captured the real hardware
modulator-amplitude trajectory for `068`. It decays to a **0.32 sustain ratio** (32.0M peak →
10.2M) — and OUR engine's modulator decays to **~0.30** (38.1M → ~10M, `FmEnvelopeProbeTest -Dfm.trace`).
**They match: the modulator amplitude envelope is faithful.** So is the attack. The earlier
"0.84→0.997 vs 0.84→0.37" tail divergence was a **confounded measurement** — the hardware attack
(E6) and "tail" (C5) were *different notes* AND different envelope phases (our 0.8–1.2 s sustain vs
an uncontrolled polled hardware window), so it does NOT establish a bug.

**Honest status:** the two *controlled* tap measurements (onset-synced same-note attack; per-block
modulator envelope) both show our FM is faithful — re-opening whether the FM_CAL scorecard gap
(068 = 0.196 at x1.0) is a real steady-state divergence or a decay/alignment measurement effect.
Settling it needs a **controlled same-note, known-timing capture** (MIDI-triggered note + tap at set
offsets), which uncontrolled audition-pad striking can't provide. Do NOT lower the FM index, and do
NOT "fix" the modulator envelope — both are tap-confirmed faithful. (Lesson: the tap corrected a
premature conclusion that a read-audit-style inference had reached — exactly its purpose.)

### 4.12ter DEFINITIVE (2026-07-06, MIDI-controlled tap): FM sidebands too bright — the FM CORE, not envelopes

With MIDI-Follow enabled on the Deluge, the tap is now fully controlled: MIDI-trigger a known note
(C4) at known timing and capture the master output at set offsets AND the per-block modulator
amplitude. This is the trustworthy measurement (same note, same phase, repeatable); it supersedes
the note-confounded ones above. **Result for `068 FM Bells 1` at C4** (brightness = energy >2 kHz):

| offset | HW bright | OUR bright | HW centroid | OUR centroid |
|---|---|---|---|---|
| 0 ms (attack) | 0.802 | 0.861 | 4444 Hz | **7341 Hz** |
| 500 ms | 0.592 | 0.822 | | |
| 1000 ms | 0.336 | 0.739 | | |
| 2000 ms | 0.199 | 0.644 | 1291 Hz | 2673 Hz |

**Our FM is genuinely too bright at C4 — at the attack AND worsening through the decay.** And the
MIDI-controlled per-block modulator-amplitude capture shows: **at the attack the modulator amplitude
MATCHES** (~46M peak both; our 45.4M vs HW 47.2M), yet our centroid is **1.65× higher** (7341 vs
4444). So the modulator *amplitude* is faithful, but the *sidebands it produces are too bright* →
the divergence is in the **FM core** (modulator frequency / phase-increment, or the modulator→
carrier index/depth scaling in `doFMNew`), NOT the amplitude envelopes. The §4.1bis read-audit claim
that `doFMNew` is "byte-identical" is contradicted by this ground truth — re-audit it against the C
looking specifically for a modulator-frequency or index-scaling divergence (the modulator1 transpose
is +34 semitones; check `calculateBasePhaseIncrement`/`phaseIncrementModulator` and the FM depth).

**Measurement-quality progression (why the verdict flipped — do not re-litigate the early ones):**
§4.1bis "artifact" (fragile ALLSYN alignment) → §4.12 "confirmed real" (validated anchor, correct)
→ §4.12bis "missing modulator decay" (WRONG — confounded E6-attack vs C5-tail) → §4.12bis correction
"faithful" (WRONG — the E6 attack match was note-lucky) → **§4.12ter (this): too bright in the FM
core, controlled same-note C4, DEFINITIVE.** Lesson: only the MIDI-controlled, same-note, same-offset
capture is trustworthy for FM; uncontrolled audition strikes (varying note/phase) produce confounded
verdicts. Do NOT lower the FM index globally (attack modAmp is right); find the sideband-brightness
bug in the FM core, scorecard-gated.

### 4.12quater PITCH-MATCHED (2026-07-06) — §4.12ter's "1.65×" was an OCTAVE confound; FM is largely faithful

§4.12ter compared our `renderSynth(note 60)` to a hardware capture of **MIDI** note 60 — but
**MIDI-Follow plays MIDI note N an octave below the Deluge's sequencer note N** (verified: our
note-60 carrier tail = 1357 Hz, hardware MIDI-60 carrier tail = 678 Hz, exactly 2×). The ALLSYN /
FM_CAL references and our `renderSynth` both use the *sequencer* note 60, which match; the tap
captures used MIDI note 60, an octave low. Re-running with **MIDI note 72** (= our note-60 pitch):

| offset | HW >2 kHz | OUR >2 kHz | HW centroid | OUR centroid |
|---|---|---|---|---|
| 0 ms (attack) | 0.855 | 0.861 | 6471 Hz | 7341 Hz |
| 1000 ms | 0.628 | 0.739 | | |
| 2000 ms | 0.506 | 0.644 | 2110 Hz | 2673 Hz |

**Pitch-matched, the FM attack matches (0.861 vs 0.855) and the whole FM signal path is verified
faithful to C** (`doFMNew`, `renderFMWithFeedback`, `calculateModulatorBasePhaseIncrement`, the
index/depth scaling — all line-for-line). A **modest residual** remains: our brightness decays ~20–30%
too slowly (2 s: 0.644 vs 0.506) — real but small, not the gross bug the confounded measurements
implied. **Do NOT chase it with an index/envelope hack** (both are tap-confirmed faithful); if pursued,
it's a subtle carrier-vs-modulator decay-balance effect, scorecard-gated.

**THE meta-lesson (this whole §4.12 arc): every uncontrolled or mismatched comparison produced a
false "bug"** — fragile ALLSYN alignment, different notes (E6 vs C5), different envelope phases
(sustain vs release), and finally an OCTAVE offset (MIDI vs sequencer). Only the **fully
pitch-matched, same-offset, MIDI-controlled** capture is trustworthy, and it shows FM is largely
faithful. When using the tap, always verify pitch first (compare carrier tails).

**Side finding — RETRACTED by §4.14 (2026-07-06):** this section speculated that a real Deluge
plays incoming MIDI note N an octave below its sequencer note N, and flagged `MidiInputRouter` as
worth checking. §4.14's clean (non-resonant, non-FM) T28 capture below shows there is **no octave
offset** — the apparent one here was a measurement confound (T09's sub-harmonic / FM's sidebands
corrupting the pitch read). §4.14 explicitly says **do not change `MidiInputRouter`**; nothing to
fix here.

### 4.13 T09 ladder + the MIDI octave offset — tap-verified (2026-07-06)

Using the MIDI-controlled tap on the `T09 resonant LPF` preset (a clean saw+ladder, so pitch is
unambiguous):

- **Octave offset CONFIRMED (clean saw, not FM):** hardware MIDI note 60 fundamental = 140 Hz, our
  `renderSynth(60)` = 264 Hz — the Deluge plays **MIDI-in note N ~an octave below its sequencer note
  N** (our engine + the scorecard match the sequencer). Our `MidiInputRouter` triggers the raw MIDI
  note (`triggerNote(midiNote)`), so our **live-MIDI input is ~an octave too high** vs a real Deluge.
  A real (live-MIDI) parity gap — separate from DSP/scorecard. NB the exact offset (−12 vs a base
  convention) needs one more clean capture to pin before fixing; peak-picking gave 264/140 ≈ 1.89.

- **T09 ladder is largely faithful, pitch-matched.** Comparing our note-48 (129 Hz) to hardware
  MIDI-60 (140 Hz) — matched pitch — both are fundamental-dominant with **no sub-oscillation** (sub<
  fund = 0.000 both) and the resonant peak aligned (522 vs 528 Hz). The doc's "resonance peak at h4
  not h2" is just the **fixed ~525 Hz cutoff** landing on h4 at low notes / h2 at note 60 — not a bug.
  Residual: our note-60 render has a **~5%-amplitude period-doubling sub-harmonic** (129 Hz = fund/2)
  absent at note-48 and (at matched low pitch) absent on hardware — a small, note-dependent ladder
  instability, not the gross "150 Hz sub-oscillation" the reference-based scorecard implied. Couldn't
  confirm against hardware at note-60 pitch (MIDI-72 capture was filter-attenuated to near-silence).

**Same pattern as FM:** measured pitch-matched with the tap, T09 is largely faithful; the big
reference-scorecard gap was inflated by pitch/note/alignment confounds. The real residuals (FM decay
~20-30% bright; T09 5% sub-harmonic at high notes) are small. The one clearly-actionable item is the
**MIDI-in octave offset** in `MidiInputRouter`.

### 4.14 T28 saturation faithful; octave offset RETRACTED (2026-07-06, MIDI-controlled tap)

**T28 drive/saturation is faithful** (retracts §4.8's "saturation attenuates / doesn't add
harmonics", which was a note-84/level artifact). Pitch-matched via the MIDI-controlled tap
(hardware MIDI-60 vs our note-60, both ~258 Hz), the odd-harmonic content matches: **h3 = 0.36 (HW)
vs 0.31 (ours), h5 = 0.18 vs 0.20, h2 ≈ 0.03 both.** Our per-voice saturation adds the drive
harmonics as hardware does.

**The "MIDI octave offset" (§4.13) is RETRACTED — it was a measurement confound.** T28 (osc
transpose 0, the cleanest reference) plays hardware MIDI-60 at **258 Hz = C4**, i.e. the *correct*
MIDI pitch and equal to our `renderSynth(60)` — **no octave offset**. The apparent offset in §4.13
came from T09's resonance sub-harmonic and FM's dense sidebands corrupting the pitch read (a saw
through a resonant ladder is 2nd-harmonic-dominant; FM has no clean fundamental). **Do NOT change
`MidiInputRouter`.** (Lesson yet again: verify pitch with a clean, non-resonant, non-FM tone before
concluding anything — and autocorrelation mis-locks on these, per CLAUDE.md.)

### 4.15 Summary of the 2026-07-06 hardware-tap session

Built a full hardware-in-the-loop golden-buffer tap (firmware `trudaine/DelugeFirmware`
`feat/dsp-buffer-dump` + Java `DspTapCodec`/`HardwareDspTapTest`), MIDI-Follow-controlled. Verdict
across the three biggest "gaps": **FM (§4.12quater), T09 ladder (§4.13), and T28 saturation (§4.14)
are all LARGELY FAITHFUL when measured pitch-matched.** The large reference-scorecard gaps were
inflated by measurement confounds (alignment, note, phase, octave, resonance sub-harmonics, FM
sidebands, level). Real residuals are small (FM decay ~20-30% bright; T09 ~5% sub-harmonic at high
notes). The only real *bug* found was in tooling — the `DelugeSysExManager` session-encoding
(file transfer broken vs current firmware, now fixed). **Bottom line: the DSP engine is
substantially more faithful than the amplitude-/alignment-sensitive scorecard implied; the tap is
the trustworthy instrument, and pitch-matching is mandatory.**

### 4.16 Supercharged parallel audit round (2026-07-13/14) — 9 subsystems clean, 1 new bug fixed, 2 documented for later

After §4.3/4.4's line-by-line audits ran dry (7 subsystems clean in a row), ran a wider parallel
fan-out covering: PWM/pulse-width oscillator rendering, the compressor + sidechain, a systematic
sweep for MORE instances of the "parsed from XML but never read by the engine" bug shape (the exact
shape of the reverb-model bug in §4.4), and an empirical dry-vs-wet probe of 133/144/137 (see below).

**PWM/pulse-width oscillator — bit-for-bit faithful, no bug.** Both `SQUARE`'s ring-mod band-
limiting and `ANALOG_SQUARE`'s phase-warp PW path (`Oscillator.java` vs `oscillator.cpp`/
`basic_waves.cpp`) match exactly, including the deliberately-asymmetric phase/phaseIncrement
divisor quirk (Java's own comment correctly flags it as intentional, matching C). Per-block (not
per-sample) pulse-width update granularity also matches — this is architectural on real hardware,
not a Java shortcut. Rules out PWM as `141 Ringmod Pad`'s cause, and closes CLAUDE.md's last named
gap family from this list: FM, oscillator hard-sync, resonant/distorted filter, FX, and now PWM/PW
envelope have all been investigated.

**Compressor + sidechain — bit-for-bit faithful, no bug.** `Compressor.java`/`rms_feedback.cpp+h`
(envelope follower, attack/release/threshold/ratio/blend formulas, gain curve, RMS/DC-blocking) and
`Sidechain.java`/`sidechain.cpp+h` (hit detection, attack/release state machine, `GlobalSidechainBus`)
both match exactly. Two audio-inert nits noted (float-vs-int32 clamp order on a debug meter; signed
vs. unsigned-masked hit-strength combine that's behaviorally identical since values are always
non-negative) — neither affects sound.

**Orphaned-parameter sweep — found 3 real bugs (same shape as the §4.4 reverb-model miss): a getter
is populated from XML but nothing in the engine ever reads it.**

1. **FIXED (commit `860303d4`): synth-track sample oscillator settings never wired.**
   `FirmwareFactory.loadOscResources`'s single-sample (non-multizone) path loaded the file but never
   copied `loopMode`/`reversed`/`timeStretch` from `SynthTrackModel` into `sound.sampleSettings` — a
   synth-track oscillator with a directly-assigned sample always played un-looped, forward, no
   time-stretch, regardless of the preset. (The multisample/kit-zone path was already correct —
   `KeyZone.looping` threads through fine.) Verified via a direct unit test
   (`SynthSampleOscSettingsTest`), NOT the scorecard: checked all 4 ludocard presets referencing
   `loopMode`/`reversed`/`timeStretch` and none actually exercise this path (3 are multisample
   zone-based; 1 — `153 FM Modulation Pad` — has `type=sine` oscillators with vestigial unused
   zone/loop XML fields). Scorecard confirmed unchanged (median 0.801, mean 0.756) — an honest,
   real fix with no current scorecard-visible effect; matters for any preset actually using this
   feature (future-authored or user-created presets).

2. **STILL NOT FIXED — and the research to scope it (2026-07-14) found the problem is bigger and
   different than originally framed.** These are the Deluge's `<songParams>` XML "performance macro"
   knobs (`ProjectModel.getSongParam*` getters, lines ~1262-1584 → bridge globals `G_SP_*` via
   `EngineSyncCoordinator.java:483-518` → **never read back** by `PureFirmwareEngine.syncFromBridge`
   for 12 of ~21 of them). The original framing above ("wire these 12 the way the other 9 already
   are") turned out to be wrong on two counts, verified against the real C (`Song`/
   `PerformanceView`/`AudioEngine`, all citations grep-confirmed):

   - **The 9 "already-wired" ones are themselves unfaithful, not a pattern to copy.** Real hardware's
     Performance View writes song-level macro values into `Song`'s own single `paramManager`
     (`song.h:83`), consumed **once**, post-summation, in a master-bus stage
     (`Song::renderAudio:2498-2513`, `AudioEngine::renderSongFX`, `audio_engine.cpp:871-903`) —
     **layered additively alongside** each track's own independent value for the same parameter
     (traced for `reverbAmount` and `modFXRate`: each track sends its own reverb/modFX
     independently in `GlobalEffectableForClip::renderOutput`/`processFXForGlobalEffectable`,
     `global_effectable_for_clip.cpp:71-85,137`, and the song value is a *second*, separate
     contribution, not a replacement). Java's existing 9-param wiring instead **broadcasts/
     overwrites** every `FirmwareSound`'s own `paramNeutralValues[Param.LOCAL_X]` — clobbering the
     per-track knob, exactly the "known simplification" the code's own comment already flags. This
     is architecturally wrong for volume/LPF/HPF too, not just missing for the other 12.
   - **4 of the 12 have no real Performance View mechanism to wire to at all.**
     `UNPATCHED_COMPRESSOR_THRESHOLD`, `UNPATCHED_PAN`, `UNPATCHED_VOLUME`, and
     `UNPATCHED_SIDECHAIN_SHAPE` all have **zero occurrences in `performance_view.cpp`** (grep-
     confirmed) — `pan`/`volume`/`sidechainShape`/`compressorThreshold` are not Performance View
     macros in this firmware version at all. (`compressorThreshold` on the master compressor is set
     from an entirely separate one-time `masterCompressorThresh` XML tag, `song.cpp:1731-1732`, not
     a per-buffer patched macro.) Wiring these as if they were performance macros would be inventing
     behavior with no C equivalent, not fixing a gap.

   **Recommendation, not yet acted on:** wiring `reverbAmount`/`modFXRate` (and by the same
   evidence, the render-side FX macros) correctly needs a **new master-bus post-processing stage**
   in the Java engine (analogous to `AudioEngine::renderSongFX`) applied once to the final mixed
   stereo buffer — Java's per-`FirmwareSound` `paramNeutralValues` array has no slot for "song
   add-on layered on top of the track's own value," so this is a real architectural addition, not a
   wiring fix, and touches already-shipped (if unfaithful) behavior with real regression risk.
   Deliberately not attempted without a scoped decision on how far to take the refactor — flagged
   back to the user rather than guessed at.

3. **FIXED (2026-07-14).** `ProjectModel.getReverbPan()` was parsed from `<rev pan="...">`
   (`SongXmlParser.java:1504`), pushed to bridge global `G_REVERB_PAN` (`EngineSyncCoordinator.java:441`),
   but never read back — `FirmwareAudioEngine.masterReverb.setPanLevels(...)` always called with the
   symmetric sidechain-ducking volume for both channels, never a stereo split. Ported C's
   `shouldDoPanning` (`functions.cpp:1487-1498`, new `Functions.shouldDoPanning`) and wired it into
   the reverb render exactly matching `audio_engine.cpp:840-847` (the C's `renderInStereo &&` guard
   is always true here, so it's dropped). **Bonus bug found and fixed along the way:**
   `SongXmlParser`'s `readSongRawAttr` applies `Math.abs()` to every value it reads, correct for
   width/hpf (unsigned) but silently flipping a bipolar reverb pan's sign (hard-left → hard-right).
   Added a sign-preserving `readSongSignedRawAttr` for pan specifically. Guarded by
   `FunctionsPanningTest` + `ReverbPanSignTest`. Scorecard confirmed unchanged (median 0.801, mean
   0.756) — reverbPan is song-level and the scorecard's bare `ProjectModel()` never sets it, so this
   is a real correctness fix with no scorecard-visible effect, same shape as the sample-oscillator
   fix above.

**Empirical dry-vs-wet probe of 133/144/137 (throwaway diagnostic, not committed) — rules out the
mix-ratio hypothesis.** Rendered each preset with reverb/delay force-zeroed vs. normal: removing FX
entirely moved the hardware-similarity score by at most 0.07, and for `144 Sweep Chords` the wet and
dry signals were nearly spectrally identical (cosine 0.999) yet both scored the same 0.774 — the FX
mix isn't touching the outcome at all for that preset. For `133 80s Strings` (max reverb send),
removing FX made the score *worse*, the opposite of an "FX too loud" bug.

**Hypothesis (b) confirmed as a REAL BUG and FIXED (2026-07-14) — but doesn't move the scorecard.**
Traced `144`'s near-silent reverb send fully: `FirmwareSound` inherited `GlobalEffectable`'s
Kit/AudioOutput reverb-send formula (`getFinalParameterValueVolume` applied to a shortcut-scaled
`reverbSendKnob`) instead of `Sound`'s own C formula (`sound.cpp:2428-2431`, a plain multiply
against the already-Patcher-resolved `paramFinalValues[GLOBAL_REVERB_AMOUNT]`) — applying the
volume curve a second time, and reading a stale, factory-build-time-only snapshot that never
tracked patch-cable modulation. Fixed via a `computeReverbSendAmount` hook on `GlobalEffectable`
(default = unchanged Kit/AudioOutput formula), overridden in `FirmwareSound` to read the live value
through `Patcher.computeFinalValueForParam`. **Verified via direct pipeline probing and unit tests**
(not scorecard, since it doesn't move — see below): confirmed `144`'s actual `paramKnobs`/
`patchedParamValues[GLOBAL_REVERB_AMOUNT]` correctly carry the preset's raw knob value
(-1342177280, ~14% of unity per the already-audited-faithful volume curve); `computeReverbSendAmount`
now returns a real, non-collapsed value where the old formula returned something close to zero;
`ReverbSendRoutingTest`'s release-tail energy went from ~1.28e8 to ~12.2e9 (~95×) — a dramatic, real
change in actual rendered audio. **Scorecard: unchanged** (median 0.801, mean 0.756; 133/144/137 all
identical to 3 decimals) — the onset-aligned spectral-cosine metric this project gates on isn't
sensitive to a reverb tail's energy change, only its onset spectral shape, consistent with §7's own
caveat that FX-tail differences are poorly captured by this metric. A real, demonstrated fix,
verified by direct measurement rather than the scorecard — same shape as the sample-oscillator and
reverb-pan fixes above. Two pre-existing tests (`ReverbSendParityTest`, `ReverbSendRoutingTest`) had
encoded the bug as "expected" (they set the stale `reverbSendKnob`/a value `syncParamsToFw2()`
silently discards) and needed correcting alongside the fix.

**FIXED (2026-07-14) — sidechain auto-ducking on the reverb send was dead code.**
`FirmwareAudioEngine.updateReverbParams()` (the "find the sound with the most reverb send, borrow
its sidechain shape for auto-ducking" logic, C `audio_engine.cpp:1251-1317`) did `if (ge instanceof
org.deluge.firmware2.Sound snd)` to find candidate sounds — but the engine's `sounds` list holds
`FirmwareSound` instances, a *sibling* subclass of `GlobalEffectable` (not a subclass of
`firmware2.Sound`), so this check could never match in production. `best` was always `null`,
`reverbSidechainVolumeInEffect` stayed 0, and auto-ducking never engaged for any real sound. Fixed
the `instanceof` and field access to use `FirmwareSound` directly (routing through `fw2Sound` for
`patchCableSet`/`patchedParamValues`). Guarded by `ReverbAutoDuckingTest` — getting the test right
took an extra round: patch cables must go through `paramManager.getPatchCableSet()` (the model
layer), not a direct `fw2Sound.patchCableSet` write, since `renderBlock`'s internal
`syncParamsToFw2()` call rebuilds that array fresh every block and silently discards a direct
write. Scorecard: no measurable change (every individual preset's score identical to 3 decimals) —
none of the bundled ludocard presets happen to use the specific `SIDECHAIN`→
`GLOBAL_VOLUME_POST_REVERB_SEND` patch-cable combo this activates, not evidence the fix is wrong.
Net effect on level was already benign even while broken (the code correctly falls back to a
neutral, non-degenerate `reverbOutputVolume` when no ducking sound is found — verified by
inspection, not just assumed), so this was never the cause of 133/144/137's residual; it's a
distinct, separate feature gap (the actual
sidechain-pumping *behavior* of a reverb-ducking sound never happened) — now fixed above.

**Hypothesis (a) — CONFIRMED REAL by direct probe (2026-07-14), root cause still unpinned.** Wrote a
throwaway diagnostic (not committed) that zeroes each preset's `lfo1`/`lfo2`-sourced patch-cable
amounts (`PatchSource.LFO_GLOBAL_1/2`, `LFO_LOCAL_1/2`) and re-scores against the same hardware
slice `FidelityScorecardTest` uses. Result — disabling LFO modulation moves the time-resolved score:

| preset | LFO1 config | cables zeroed | normal | LFO-zeroed | Δ |
|---|---|---|---|---|---|
| `133 80s Strings` | unsynced (`syncLevel=0`), `lfo1→pitch` only | 1 | 0.612 | 0.742 | **+0.130** |
| `137 Epic Saw Modulation Pad` | unsynced, `lfo1→pitch`, `lfo1→lpfFreq`, `lfo2→lpfFreq` | 3 | 0.796 | 0.823 | +0.027 |
| `144 Sweep Chords` | **tempo-synced** (`syncLevel=3`), `lfo1→lpfFreq`, `lfo1→oscAPhaseWidth` | 2 | 0.774 | 0.778 | +0.004 |

This rules out the "no effect" possibility and pins the pattern precisely: the divergence is
concentrated in **unsynced (free-running) LFO1 modulation**, not the tempo-synced path — 144 (synced)
barely moves, while 133 (a *single*, small ~5.5%-depth `lfo1→pitch` vibrato and nothing else) moves
the most. This also rules out LFO2/local-LFO as the culprit for 133 (it has none) and for 137's bulk
of the effect (still moves when only its unsynced-LFO1 cables are considered).

**Investigated and ruled out as the cause** (all confirmed correct/faithful before writing the
probe, so the remaining bug is elsewhere): the `lfo1`/`lfo2` XML-tag→`PatchSource` source mapping
(`FirmwareFactory.SOURCE_MAP`/`resolvePatchSource`, `lfo1`→`LFO_GLOBAL_1`, `lfo2`→`LFO_LOCAL_1`,
matching C's `LFO1_ID`/`LFO2_ID`↔`PatchSource::LFO_GLOBAL_1`/`LFO_LOCAL_1`, `voice.cpp:156-159`);
the global-vs-local render scope (`Sound.java:771` computes `LFO_GLOBAL_1` once per Sound, matching
C `sound.cpp:2382`; `Voice.java:276` computes `LFO_LOCAL_1` per-voice, matching `voice.cpp:157`);
and `Functions.getParamRange(LOCAL_PITCH_ADJUST)` (536870912, matching C `functions.cpp:153-158`
bit-for-bit). **Not yet checked:** the actual unsynced LFO1 rate curve's numeric output for values
other than the one specific hex already spot-checked against hardware
(`FirmwareFactory.java:802-805`'s comment cites `0x1999997E`→3.79 Hz; 133's rate is a different hex,
`0x1EB851CF`, unverified), and whether LFO1's rendered waveform shape/phase drifts over the
scorecard's 2s analysis window in a way a single-instant tap wouldn't show. **Needs a
hardware-controlled tap capture** (per §4.12quater/§4.13's proven methodology) isolating a single
free-running LFO1→pitch cable at a known rate to pin the exact defect — Java-side reading alone
found everything upstream faithful, so further progress needs a real measurement, not another
read-audit.

## 5. Real bugs: synths our engine renders SILENT

These produce no sound in-engine but DO sound on hardware. Highest priority — they're 0 fidelity:
- **`107 FM LPG Percussion` — RESOLVED, stale entry.** Re-checked 2026-07-13: no longer silent
  (win=0.491, time=0.743). Fixed as a side effect of one of the later passes (§4.6–§4.15); not
  independently tracked. Leave the confirmation here so nobody re-investigates it as "silent."
- **`169 Double Bass` — FIXED (2026-07-13).** Root cause: `AudioFileReader.readWavSample`'s
  `smpl`-chunk parser (`src/main/java/org/deluge/storage/audio/AudioFileReader.java`) skipped the
  real `NumSampleLoops` field, misread the adjacent `SamplerData` field as the loop count, and only
  accounted for 16 of a loop entry's 24 bytes — desyncing the byte stream so the subsequent `data`
  chunk was never recognized (`Sample.data` stayed `null`, no exception, silent render). Double
  Bass's WAVs have a `smpl` chunk (per-note loop points) that every other ludocard multisample
  lacks, which is why only this preset hit the bug. Fixed to the correct RIFF `smpl` layout
  (36-byte header + 24-byte-per-loop entries). Scorecard: win=0.940 time=0.952 (one of the best
  scores in the set); no regressions (median held 0.800, n 183→184, ≥0.80 91→92). One pre-existing
  test (`AudioFileReaderTest`) had hand-authored a `smpl` chunk matching the OLD buggy byte layout,
  not the real spec — corrected the test's synthetic WAV to a spec-compliant layout (assertions
  unchanged).
- **`Stone Skip`/`Tube Slap`/`Vibraphone`/`Wood Flute Verb` — FIXED (2026-07-13), root cause was
  NOT what §4.7 assumed.** These are the last 4 presets rendered in the ~188-preset sequential
  scorecard run. Verified each renders loudly and clears the "silent" RMS threshold with a wide
  margin **when rendered in isolation** (peaks 0.05–0.37, `ourMax` 0.012–0.14 vs the 0.002 gate) —
  so "a measurement-window detail" (§4.7's original verdict) was false. Reproduced the real
  failure by replaying the scorecard's exact sequential render order via reflection
  (`renderSynth` called ~183+ times in one JVM): confirmed `OutOfMemoryError`, silently uncaught
  because `FirmwareFactory.loadOscResources`'s sample-load `catch` blocks only catch `IOException`
  (`OutOfMemoryError` is an `Error`, not an `Exception`). Cause: `AudioFileReader.CACHE` is an
  unbounded `ConcurrentHashMap<String, Sample>` that never evicts — after ~183 presets' worth of
  decoded multisample float arrays (many multisamples carry 10–36 zones), it exhausts the
  scorecard's 2 GB surefire heap, and the last few multisample-heavy presets fail to load their
  zones with zero error output. Fixed: added `AudioFileReader.clearCache()` and call it once per
  preset in `FidelityScorecardTest`'s render loop (scoped to the test — the cache is intentional
  and correct for normal app use, just wrong for a one-JVM 188-preset batch). Scorecard: all 4 now
  score (Stone Skip 0.822, Tube Slap 0.843, Vibraphone 0.694, Wood Flute Verb 0.706 — time-
  resolved); no regression elsewhere (median held 0.800). (Re-verified 2026-07-13: scorecard
  reports "not-measurable: 0" now, down from the original ~16 after the §4.7 zone-parsing fix + the
  fix above.)

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

### 4.1ter FM bells — 2026-07-24: the §4.1bis "misaligned slice" write-off is REFUTED; divergence is real

`FmBellsAlignmentProbeTest` (new, permanent) extracts the exact hardware slice the scorecard
scores — replicating the scorecard's list construction byte-for-byte, which matters: a first
probe with a naive file list was six slots off and looked at the wrong notes — and prints
per-250 ms RMS + spectral-centroid curves for both sides (slices + renders also written to
`target/fm_probe/*.wav`). Result for the correctly-mapped slices:

| preset | HW slice | HW centroid over 4 s | our centroid over 3 s |
|---|---|---|---|
| 068 FM Bells 1 (k=68, 409.2 s) | clean onset, smooth RMS decay | **flat 513 Hz, zero drift** | 9222 → 3649 Hz |
| 069 FM Bells 2 (k=69) | clean onset | flat ~365 Hz | 5544 → 1911 Hz |
| 050 FM Basic Bass (k=50, control) | clean onset | flat ~263 Hz (≈C4) | 3960 → 473 Hz |

The 068 slice is aligned (clean onset, exactly one 6.00 s grid slot, and the *control* preset
in the same grid scores 0.884 — a shifted grid would break it too). The hardware genuinely
renders FM Bells 1 as a near-static ~513 Hz tone; our render is a 9 kHz decaying buzz. So:
**engine divergence, real, not measurement.** The §4.1bis instruction "do NOT lower the FM
index" was built on a false premise (its trace verified sub-functions individually but never
checked the end-to-end number against an aligned hardware slice).

Sharpened target: hardware's effective modulation index for this patch must be β < ~0.5 rad
(static carrier-dominant centroid, *no spectral decay despite an env1→modulator1Volume cable* —
i.e. the modulator contributes ~nothing from the start), while ours is β ≈ 9 rad
(§4.1bis: modVol ≈ 45.4M) — an effective modulator-amplitude discrepancy of roughly 20×
(≈2^4.3). Same note (60) and velocity (127 vs our 110) on both sides, so the suspect is the
**modulator-amplitude derivation chain**, audited end-to-end as one dataflow (not per
sub-function): preset value → patcher cable combination (note −0.221FS / velocity / env1 →
modulator1Volume) → `getFinalParameterValueVolume` domain → the shift/scale applied where the
final param value becomes the `doFMNew` amplitude operand, Java vs `voice.cpp`/`patcher.cpp`
side by side with 068's exact numbers as the test vector. A single wrong shift (`<<5`-class) or
a linear-vs-exponential domain slip would produce exactly this signature.

Also reconfirmed en passant: the "fine" family's steady-state is ~1.8× brighter than HW
(473 vs 263 Hz on 050) with a much brighter attack — the "mild systematic over-brightness" of
§4.1bis is visible in the same probe and may share the root cause.

### 4.1quater 2026-07-24 — the parameter chain is C-exact; the scorecard's comparison target is not

Continued from §4.1ter with a runtime probe (scratchpad `ParseProbe`) that renders 068 and reads
the live fw2 state mid-render, plus C-exact hand computation of the same chain:

1. **The Java modulator chain is numerically C-exact.** At render time for 068:
   knob −738,197,504 (0xD4000000 ✓ signed), modulatorTranspose [34, −42] ✓, all cables present
   with correct amounts, and the voice's `paramFinalValues[MODULATOR_0_VOLUME]` = 40.3M — inside
   the C-exact envelope band (21.9M at env=0 → 54.7M at env=max) computed from patcher.cpp /
   functions.cpp with the same inputs. §4.1bis's per-function verification is confirmed at the
   dataflow level too. (Note: an early probe read fw2 fields BEFORE the knob→fw2 sync runs and
   wrongly implicated FirmwareFactory — the sync happens on render, always probe mid-render.)
2. **Real parser bug fixed: sound-level nested `<transpose>` was dropped** (old-format factory
   presets; attribute-only parse). 068's `<transpose>-12` now flows to `fw2Sound.masterTranspose`
   (regression test `SoundLevelTransposeTest`; the lookup is direct-child-only — a descendant
   search would grab osc1's transpose).
3. **Scorecard after the fix: targeted FM family up strongly** (068 .203→.375, 093 .448→.687,
   084 .395→.548, 151 .569→.715, 164 Study Arp .606→.867, 072 Kyoto .774→.917) **but the overall
   set regressed** (time-resolved median .801→.775; Glockenspiel .965→.542, Organ .900→.616…) —
   the fix does NOT pass the fidelity gate on its own. Why:
4. **The recordings did not play the preset files.** The ALLSYN songs embed their own full
   instrument copies, and those demonstrably differ from the SYNTHS/ preset files. For 068:
   embedded has sound-level transpose **0** (preset: −12) and modulator2Amount **0xD4000000,
   ACTIVE** (preset: 0x80000000 = INT_MIN, which the C gate at voice.cpp:530 silences). The
   clip's `<soundParams>` adds only volume/pan/filters (LPF wide open). So the scorecard has been
   comparing our render of one patch against a recording of a different patch, per preset — and
   any parse fix that moves us toward the preset file moves us away from the recording wherever
   the embedded copy drifted. **The scorecard must render the songs' embedded instruments**
   (parse ALLSYN_1/2.XML tracks in order) to be a valid metric; that rework is the next step,
   and per-preset deltas from the transpose fix should be re-judged only against that.
5. **Open contradiction needing hardware ground truth:** even for the exact embedded 068 patch,
   current-C math predicts a bright bell (β≈8 from modVol≈40M in the 24-bit phase domain of
   `SineOsc::doFMNew`), while the recording is a spectrally static ~513 Hz tone (β<0.5). Either
   the device ran a different firmware than assumed when the recordings were made, or there is a
   C-side scaling between `paramFinalValues` and the FM phase domain that reading hasn't caught.
   The DSP-tap harness (docs/hardware_dsp_tap_calibration.md) exists precisely for this: capture
   the device's real modulator amplitude for 068 and compare. Needs the physical Deluge.

### 4.1quinquies 2026-07-24 — HARDWARE GROUND TRUTH: the engine is right; the recording is stale

With the physical Deluge connected (debug firmware, PONG verified), the per-block
modulator-amplitude tap (`HardwareDspTapTest -Dtap.mod=true -Dtap.note=true`, preset 068 loaded
on the device, C4 @ vel 100) captured the device's actual
`paramFinalValues[LOCAL_MODULATOR_0_VOLUME]` across an 11.9 s note
(4096 blocks, saved as `docs/evidence_fm068_hw_modulator_tap_2026-07-24.txt`):

| t | hardware modVol |
|---|---|
| 0 | 7.78M (attack ramp) |
| 50 ms | **47.2M peak** |
| 1 s | 40.6M |
| 4 s | 27.2M |
| 11 s | 15.6M |

Our engine's runtime value for the same patch: **40.3M** (§4.1quater), C-paper band 21.9–54.7M.
**The live hardware, today's C source, and our engine all agree** — 068 plays a bright β≈8 bell
on the real device, exactly as the old ear-check reported ("METALLIC on the hardware").

Conclusion: the ALLSYN recordings' static-soft ~513 Hz slice for the FM bells does not represent
what current firmware/preset state produces — the recordings are stale or were made under a
different firmware/state for (at least) these presets. Together with §4.1quater's finding that
the recordings play song-embedded instrument copies that drift from the preset files, the path
to a trustworthy scorecard is:

1. **Re-record ALLSYN_1/2 on the current firmware** (procedure: HARDWARE_FIDELITY.md), and
2. **Make the scorecard render the songs' embedded instruments** so the comparison is
   apples-to-apples even if presets drift again.

Until then, per-preset FM scores against the old recordings are not evidence of engine
divergence, and the §4.1quater transpose-fix regressions (Glockenspiel etc.) cannot be judged.

### 4.1sexies 2026-07-24 — scorecard now renders the songs' EMBEDDED instruments (new default)

`FidelityScorecardTest` now parses ALLSYN_1/2.XML and renders each track's embedded instrument
with the clip's own note/velocity — the patches the hardware recording actually played. The old
preset-file mode remains behind `-Dscorecard.presets=true` for comparison only.

**New baseline (embedded mode): time-resolved median 0.777, ≥0.80: 44%, n=187.** Composition of
the change vs the old (invalid) preset-mode 0.801 baseline:

- Preset-file drift victims now score near-perfect: SolidBassShort .227→.972, Xax Stacato
  .277→.972, SolidBassMidLong .555→.969, SawFifthFilter .675→.950, Wood Flute Verb .706→.947.
  The §4.1quater transpose-fix "regressions" (Glockenspiel, Organ, Cello) also vanish — they
  were pure drift artifacts.
- The stale-recording FM slots (068/069/084/093…) now score LOWER because our render is
  hardware-correct-bright (§4.1quinquies) while the recording is stale-soft. These slots are
  unjudgeable until ALLSYN is re-recorded on current firmware.
- NEW actionable cluster: pads/tempo-synced patches regressed (078 House .806→.324, 120 High
  Harsh Pad, 158 Tempo-Synced LFO, 124 Filter Modulation Pad, several *Pad presets ~−0.15..−0.4)
  — likely song-context/parse gaps: the embedded render discards song BPM/sync context, and the
  song-parse path may drop LFO sync or similar fields the preset path keeps. This cluster is the
  next line-of-attack (it is real signal about OUR parse/render, unlike the FM slots).

### 4.1septies 2026-07-24 — osc2-type parse bug fixed; pads/sync cluster adjudication blocked on re-record

Model-level reflection diff (preset-parse vs song-embedded-parse of the same patch) found the
osc2 TYPE binding was child-element-only, so attribute-style `<osc2 type="...">` — the
song-embedded and newer preset format — parsed as NONE, silencing oscillator 2 of every
two-oscillator patch loaded from a song (fix: attrOrChild like osc1; regression test
`Osc2AttributeTypeTest`). Real bug, but NOT the pads-cluster driver: scores barely moved
(080 House +0.05) because e.g. 078's embedded copy has oscB volume at minimum anyway.

The cluster's actual signature is high single-window + low time-resolved (078 House win .80 /
time .32): correct timbre, wrong time-evolution — the recordings carry delay tails / LFO
movement that the embedded instrument state says should be off (078 embedded: delay feedback
INT_MIN). Same class as the FM bells: the recording reflects an older device state. **Per-slot
forensics against the current ALLSYN recordings is no longer productive — re-recording on
current firmware is the gate for all further per-preset fidelity claims.** Until then the
scorecard's value is as a regression tripwire (it did catch the osc2 and transpose parse bugs),
not an absolute fidelity measure.

### 4.1octies 2026-07-24 — ROOT CAUSE FOUND AND FIXED: clip param semantics; median 0.83, 60% ≥0.80

The user re-recorded ALLSYN_1/2 on current firmware (part 1 needed a trim: a stop-button pop at
the file's end defeated trailing-silence trimming and stretched the onset grid — check `onset
gaps ≈ 6s` in the header before trusting a run). The fresh recording reproduced the old one
almost exactly — the recordings were never stale; §4.1quinquies's conclusion was wrong about
that. The static ~513 Hz "bell" is what the hardware truly plays IN THE SONG — while the DSP
tap (preset mode) plays bright. The difference is the clip-param semantics:

**In the firmware, a clip with `<soundParams>` takes its patched params from initParams defaults
overlaid with only what the clip lists (sound.cpp:146-210) — the instrument's `<defaultParams>`
are not consulted.** The ALLSYN clips' soundParams lack all modulator params, and
`LOCAL_MODULATOR_*_VOLUME` defaults to INT_MIN = modulator OFF → the hardware plays those FM
presets as carrier-only sines in the song. Our song parser inherited the embedded instrument's
defaults instead, rendering bright bells against a soft recording.

Fix (`InstrumentXmlParser.resetClipParamsToFirmwareDefaults`, called from SongXmlParser before
the clip soundParams overlay), **empirically calibrated against the fresh recording**:
- Reset the FM modulator param group (modulator1/2 amounts + feedbacks, carrier feedbacks) to
  INT_MIN — hardware-proven (068/069/084/093 snapped from 0.2-0.5 to 0.84-0.93).
- Do NOT reset osc volumes / HPF / sends / portamento / patch cables: a full initParams reset
  regressed basses/leads sharply (down to Panpipes −0.01) and the recording matches the
  inherited values — the C old-song path evidently back-fills those groups from the instrument.
  (The osc/HPF float resets were additionally no-ops in our pipeline: the factory prefers raw
  Q31 knobs that the float setters don't touch.)

**New baseline: time-resolved median 0.829, ≥0.80: 60%, ≥0.90: 27%, n=187** (from 0.78/45%
before this arc; the pre-arc preset-mode "0.801" was measuring drifted patches). Only one
regression < −0.05 across the calibration (096 FM Guitar Power Chord −0.09 — carries FM params
in neither clip nor recording expectations cleanly; open). Remaining low scorers to attack
next: 120 High Harsh Pad (.42), 078 House time-structure (.34 — likely the documented
envelope-defaults omission), plus the known hard-sync/PWM/FX families.

### 4.1nonies 2026-07-24 — clip volume-envelope defaults: the blank-synth shape; zero-regression win

Following 4.1octies, the 078-House time-structure gap was the predicted envelope omission. The
chain: in the clip path the C's envelope params are NOT inherited from the instrument. First
attempt (AutoParam construction defaults, param 0 = user-25 mid for all of ADSR) split the set —
half the presets' recordings matched it (House .34→.93) while sustain-heavy basses regressed
(Dubstep −0.29): mid-sustain was wrong. The profile that unifies both populations is the C
blank-synth shape (sound.cpp:297-306): **volume envelope (ENV_0) = instant attack, user-20
decay, FULL sustain, user-25 release; envelopes 2-4 inherited.** Applied via the raw Q31 env
knob channel in `resetClipParamsToFirmwareDefaults`.

Result vs the 4.1octies baseline: **zero presets regress >0.05; five improve >0.05** (078 House
.34→.92, 081 Xylophone Big Bass .42→.96, 103 Sci-fi Chaos +.25, 149 Cold 5th Pad +.11, 109
Talking Arp +.06). New baseline: **time-resolved median 0.830, ≥0.80: 60%, ≥0.90: 27%,
<0.60: 19** — every summary metric at its historical best. Note the method: each candidate
default profile was accepted or rejected purely on the fresh-recording scorecard delta
(hardware-calibrated empiricism), with the C source narrowing the candidate space.

### 4.2bis 2026-07-24 — 109 Talking Arp diagnosed: resonance-at-open-cutoff gain error (filter family)

Bottom-scorer triage. 109 (time .01) is not an arp problem (its embedded arp is off) nor an LFO
routing problem: probes show our synced square LFO1 → lpfResonance chain parses and renders
(resonance final value toggles 335M↔0 at tempo). The decisive evidence is the RMS envelope:
hardware decays perfectly smoothly while our render pumps >10× RMS with each LFO cycle — with
the clip's LPF cutoff WIDE OPEN (0x7FFFFFFF). On the hardware, resonance modulation at a fully
open cutoff is near-inaudible; our ladder filter's resonance changes gain massively at max
cutoff. This is the known resonant/distorted-filter family expressed through modulation, and
likely also underlies 120 High Harsh Pad (win −0.14: categorically different spectrum) and the
other filter-heavy bottom scorers. Next line of attack: line-by-line LpLadderFilter resonance /
gain-compensation audit vs dsp/filter in the C (the family was flagged in the 2026-07-04 review
but the resonance-compensation path was not exhaustively audited).

### 4.2ter 2026-07-24 — LP-ladder audit complete: filter is bit-faithful; 109's gap is load
semantics, not DSP

The line-by-line Java→C audit promised in 4.2bis ran the full chain and **found the filter
clean**. Verified side-by-side at the bit level: `LpLadderFilter.setConfig` (incl. the
cold-ladder resonance squaring, the `tannedFrequency <= 304587486` halving/boost branch and
the resonance `gainModifier`) vs lpladder.cpp:53-171; `scaleInput` vs lpladder.h:52-70; all
three per-sample render functions vs lpladder.cpp:327-411; `BasicFilterComponent` vs
ladder_components.h; the `Filter` CRTP base (curveFrequency, dry/wet blend) vs filter.h;
`Patcher.performPatching`/`combineCablesLinear`/`combineCablesExp`/`applyRangeAdjustment`
vs patcher.cpp/patch_cable.h; `getParamRange`/`getParamNeutralValue`/`getExp` vs
functions.cpp; and the synced-LFO increment (`getSyncedLFOPhaseIncrement`, SyncLevel enum,
tick inverse 2^31/(1.5×timePerTimerTick)) vs sound.cpp:2711-2734 + song.cpp:2586. Hand-fed
109's runtime inputs (lpfResonance 335544304 → procRes 988281208) reproduce identically.

One true divergence found and fixed: `cableToExpParam*` used `add_saturate` where the C
(patcher.cpp:164-173) uses plain wrapping `+`. Scorecard-neutral on the corpus (0 presets
change >0.005) — kept for faithfulness.

Two corrections to 4.2bis: (1) the clip's LPF cutoff is NOT wide open — soundParams says
`lpfFrequency="0x1A000000"` (user ≈30, final ≈4.4M, cutoff a few hundred Hz, tanned ≤304M,
so BOTH engines take the resonance-boost branch); (2) the pump in our render follows the
lfo2→lpfFrequency sweep more than the resonance toggle (removing the resonance cable leaves
most of the pumping).

The initParams-envelope theory was tested and **refuted**: making the C initParams clip-env
defaults actually apply (ENV_1 = 20/20/25/20, ENV_2/3 = construction zeros, ENV_0 sustain
full) scored net-negative (mean −0.008; 103 Sci-fi Chaos −0.40, 160 Synthwave Bass Arp −0.34,
011 Dubstep −0.29) and made 109 itself worse (0.009 → −0.022). Discovery along the way: the
env *sustains* written by `resetClipParamsToFirmwareDefaults` were always overridden by the
raw param-knob map (applied later in FirmwareFactory) — the empirically-validated "blank-synth
ENV_0" reset is effectively rates-only, sustains inherited. The C old-song reader evidently
back-fills envelopes from the instrument like the osc/HPF groups. 109's residual divergence
(~3× hot, pumping vs hardware's flat sustained decay) therefore sits in the old-song
load/back-fill semantics or a per-voice level path — not in the ladder DSP, which is now
audited faithful.

### 4.2quater 2026-07-24 — hard-sync audit clean; bottom-cluster reframed as saturation/env-semantics

The oscillator hard-sync family was audited line-by-line Java→C and **found faithful**:
`renderOsc` (oscillator.cpp:28-509 — retrigger offsets per type, the callRenderWave label
placement that skips amplitude-doubling for sine/triangle, pulse pre-halving, crude/band-limited
thresholds), `renderOscSync` (render_wave.h:25-90 — window chopping, crossover half-sine blend,
resetter arithmetic in uint32), the scalar segment renderers vs waveRenderingFunctionGeneral/
Pulse (one sub-LSB16 interp difference: C computes `2*(frac>>1)`, we use `frac` — inaudible),
`applyAmplitudeVectorToBuffer`, and the Voice orchestration (voice.cpp:1107-1250 — resetter
oscPos capture before render, phase-increment collection on osc A, pitch-too-high zeroing,
`renderingOscillatorSyncCurrently`). Current sync scores (045 ≈ 0.59, 046 ≈ 0.76, 127_CAL ≈
0.83) are far above the stale 0.3-0.4 claim; 045/046's remaining gap is not the sync engine
(045's embedded instrument doesn't even enable oscillatorSync — the C file has no such tag
there).

Bottom-cluster triage (time-resolved): 109 (.01), 120 (.03), 016 (.23), 059 (.34) are ALL
clipping/saturation presets (clippingAmount 1-8) and/or envelope2-cable presets. Two threads:

1. **Nonlinear-stage level debt.** Per-voice saturate() (sound.h:290, getTanHAntialiased at
   5+clippingAmount) is ported faithfully, but the engine's documented amplitude-chain debt
   (osc amplitude at >>30 vs C's net >>32, compensated linearly at the master stage) means the
   tanh sees a NON-C level — and tanh is level-dependent, so distortion amount diverges
   exactly on these presets (059 renders dull/clean where hardware is bright/driven). This is
   the CLAUDE.md "only attempt stage-faithful re-derivation for saturation reasons" case, now
   with concrete motivating presets. Scorecard-gated re-derivation of the voice→saturate→master
   chain is the identified next big fidelity project.

2. **Envelope semantics contradiction (needs hardware tap).** 016 (env2→lpfFrequency, clip
   cutoff user-13 "dark") renders 10× too bright — env2-driven filter opening; the initParams
   env experiment fixed it (+0.31). But 011 Dubstep, which regressed −0.29 in that same
   experiment, ALSO drives everything from envelope2 — one global rule cannot satisfy both,
   so the C's old-song envelope back-fill semantics remain unresolved. Next time the Deluge
   is connected: HardwareDspTapTest with the ALLSYN clip for 016/011 tapping LPF_FREQ and env
   levels per block will settle which shape each clip actually runs.

### 4.2quinquies 2026-07-25 — C-exact oscillator amplitude: the nonlinear stages now see C levels

The 4.2quater thread-1 project landed. The wave-oscillator amplitude application in
`Oscillator.java` was a translation error: the port commented `vqdmulhq_s32(amplitude, val) ==
(amplitude*val) >> 30`, but vqdmulh is `sat((2ab)>>32)` AND the C halves its amplitude vector
first (`createAmplitudeVector`, basic_waves.cpp:34/43 + the "investigate where the doubling
comes from" TODO) — net `(amp*val)>>32`. Our waves ran 4× hot into every nonlinear stage
(ladder drive tanh, per-voice saturate, master compressor), with the master stage compensating
linearly — exactly the wrong place. Fixed to the C-exact `sat(((amp>>1)*val)>>31)` with the C's
wrapping accumulate; the crude/band-limited seam at ~72 Hz (crude paths were already C-exact,
so 4× discontinuous) is gone as a side effect, and osc-vs-noise balance is now C-correct. The
sample subsystem and DX7 path (both calibrated against the old hot waves — hybrid presets like
Vibraphone/SolidBassLong regressed when only the waves moved) were brought down 4× to match;
native FM was verified already C-exact (`mult_acc >>32`, voice.cpp:1748) and untouched. The
master output shift went `lshiftAndSaturate(…,4)` → `(…,6)` so net output level is unchanged.

Scorecard: net-neutral overall (median 0.830 → 0.831, mean delta +0.0005), with the motivating
saturation preset up sharply (016 Dark Saturated Bass 0.234 → 0.398) and several mid presets
+0.03..+0.08 (038 Vapor Arp +0.078, 115 Sounds After Take-off +0.068, 140 Slow Aural Swells
+0.066). Residual regressors worth revisiting: 149 Cold 5th Pad (−0.067), 015 Resonant Filter
Bass (−0.049), 098 Saturated Sync (−0.042) — resonant/drive-filter presets that apparently
preferred the extra drive; whether the C drive path hides another compensating divergence is
the open question. Rebaselined `/4`: FirmwareGoldenSignatureTest (sine/saw/tremolo/env/DX7
fixtures — the harness calls renderOutput directly, bypassing the master compensation) and
DigitalAudioFidelityTest's kit ratio (0.078125 → /4). AutodispWorkstationDiagnostic's 3
failures pre-date this change (broken synthetic paramNeutralValues harness — verified by
stash-rerun at the prior commit).

### 4.2sexies 2026-07-25 — drive/saturation primitive stack audited: bit-faithful; residual dips
are not translation bugs

Follow-up to 4.2quinquies's residual regressors (149 Cold 5th Pad, 015 Resonant Filter Bass,
098 Saturated Sync): the suspicion that the C drive path hides another compensating divergence
was checked and ELIMINATED. Verified side-by-side at the bit level: `getTanHUnknown` +
`getTanH` (functions.h:273-293), `getTanHAntialiased` (functions.h:295-304),
`lshiftAndSaturateUnknown` + `signed_saturate_operand_unknown` (functions.h:53-109 — the
13..31-explicit/default-12 switch matches Java's clamp), `interpolateTableSigned`
(waves.h:15-27) and `interpolateTableSigned2d` (functions.h:245-271), and the tables:
`tanHSmall` (257) and `tanH2d` (8385) byte-identical to tanh.bin, and the LP-ladder
`resonanceThresholdsForOversampling`/`resonanceLimitTable` (65 each) identical. Combined with
the 4.2ter ladder audit and the 4.2quinquies C-exact input levels, the entire signal path
osc → ladder(+drive tanh) → per-voice saturate is faithful end-to-end. The small dips on
149/015/098 therefore reflect divergence elsewhere (clip-param/envelope semantics — see the
unresolved 016-vs-011 contradiction in 4.2quater — or recording-side), not the drive DSP.
