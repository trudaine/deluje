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
Remaining 5 n/a: `169 Double Bass` (its `.WAV` files won't load — reader issue, separate) + 4 short/
percussive (Vibraphone/Tube Slap/Stone Skip/Wood Flute Verb) that DO render in isolation but fall
below the scorecard's 2 s-RMS "silent" threshold — a measurement-window detail, not an engine bug.

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
