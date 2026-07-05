# Hardware Calibration Recording — one song, 28 targeted test segments

**Purpose.** The 2026-07 DSP parity fixes changed the delay, reverb, PWM, sync, FM-routing,
modFX, filter-engagement and sample paths — but the existing hardware references predate them
(and several are clipped or stale, see FIDELITY_GAP_ANALYSIS §4.6). This kit produces ONE
hardware recording containing 28 clean, purpose-built segments, each isolating one DSP feature,
so every fixed family gets trustworthy ground truth in a single take (~3 minutes).

Everything is generated, versioned, and verified to render audibly in our engine before you
record (the generator run below reports per-preset RMS — all 28 non-silent).

---

## 1. Generate the files (already done, re-runnable)

```bash
python3 scripts/generate_calibration_presets.py          # writes src/test/resources/calibration/SYNTHS/*.XML
mvn test -Dtest='AllSynthsFidelityTest#generateAllSynthsSong' \
    -Dsynth.dir=src/test/resources/calibration/SYNTHS \
    -Dsong.out=target/CAL_SONG.XML -Dgpg.skip=true -B
```

This uses the exact same proven pipeline as the ALLSYN songs (arranger song, one C4 per preset,
2-bar note + 1-bar silence gap, boots in arranger view). Song facts, all embedded in the XML so
hardware and engine read identical settings:

- **Tempo:** 120 BPM (`timePerTimerTick="229"`), `inputTickMagnitude="2"`.
- **Segment timing:** each preset = 4 s note + 2 s gap = **6 s per segment**, 28 segments ≈ 2 m 48 s.
- **Song reverb:** `model="0"` (Freeverb), roomSize 0.6, damp 0.5, width 0.5 — segment 23 sends into this.
- Notes are stored in the song (no MIDI needed); playback order = preset filename order.

## 2. Copy to the SD card

1. Copy all 28 files from `src/test/resources/calibration/SYNTHS/` into the card's **`SYNTHS/`**
   folder (keep the names — the song binds clips to instruments **by name**; renaming breaks the
   load with `FILE_CORRUPTED`).
2. Copy `target/CAL_SONG.XML` into the card's **`SONGS/`** folder.

## 3. Record on the Deluge (same procedure as the ALLSYN recordings)

1. Load `CAL_SONG` (it boots straight into the Arranger).
2. Start the Deluge's **resample-output recording to SD** (the same on-device procedure used for
   `ALLSYN_1/output_000.wav`), then press play from the arranger start.
3. Let it run to the end (~3 min), stop recording ~2 s after the last segment's tail dies.
4. The recording lands on the card under `SAMPLES/` as `output_000.wav`
   (**44.1 kHz — keep it stereo**: segments 12 and 22 carry stereo information).
5. Copy it to the dev machine as `~/CAL_SONG/output_000.wav`.

Alternative (line-out into a DAW): 44.1 kHz / 16-bit / stereo WAV, gain set so peaks sit around
−6 dB (never clipping) — the level anchors below only make sense unclipped.

## 4. What each segment tests (and what to listen for)

| # | Segment | Isolates | Expected on hardware |
|---|---------|----------|----------------------|
| 01 | SINE | osc level anchor | pure sine — **its level vs 03 SAW validates the sine −6 dB fix** |
| 02 | TRIANGLE | band-limited triangle tables | clean triangle |
| 03 | SAW | osc anchor + table band selection | bright saw |
| 04 | SQUARE 50 | 50% square | odd harmonics only |
| 05 | SQUARE PW | static square pulse width (signed-shift fix) | fixed off-center duty |
| 06 | SAW PW | PW on SAW (the `resetterPhase >>>` fix) | doubled-saw character |
| 07 | PWM LFO | LFO1→PW at an explicit running rate | audible duty sweep |
| 08 | PW ENV | envelope2→PW (PW-envelope family) | one-shot duty sweep over the note |
| 09 | SAW SYNC | hard sync, saw, +18 semis | classic sync timbre |
| 10 | SQUARE SYNC PW | **synced square WITH pulse width** (newly ported branch — zero prior ground truth) | sync + off-center pulse |
| 11 | NOISE | noise source chain | white noise |
| 12 | UNISON 4 | 4-voice detune (stereo image) | wide detuned saw |
| 13 | LPF RESO | 24 dB ladder, low cutoff, high resonance | dark resonant |
| 14 | LPF DRIVE | 24dBDrive in the oversampling-threshold zone (the fixed 65-entry table) | driven, gritty |
| 15 | HPF RESO | HP ladder actually engaged (post-bypass-fix ground truth) | thin, resonant highpass |
| 16 | FM LOW | native FM, ratio 1, mild index | soft FM |
| 17 | FM HIGH | modulator +34 semis, hot index (**FM index calibration**) | bright/metallic |
| 18 | FM CHAIN | mod2→mod1→carrier chain | complex FM |
| 19 | FM CHAIN MUTE | mod2→**muted** mod1 (the routing-gate fix) | **must be a PURE SINE** — if hardware plays a sine here, the gate port is confirmed by ear |
| 20 | FM FEEDBACK | modulator + carrier feedback | raspy FM |
| 21 | DELAY SYNC | pluck + synced delay (file syncLevel 6 → internal 5 = 8th note = **250 ms echoes @120**) | echo train in the gap |
| 22 | DELAY PINGPONG | same, ping-pong | echoes alternating L/R |
| 23 | REVERB SEND | pluck into the song Freeverb (0.6/0.5/0.5) | reverb tail in the gap |
| 24 | CHORUS | chorus, rate knob center, depth set (patcher-final rate fix) | slow shimmer |
| 25 | FLANGER | flanger + feedback | jet sweep |
| 26 | PHASER | 6-stage phaser | notch sweep |
| 27 | ARP GATE | up-arp, synced 16ths, gate 50% (the double-shift fix) | even note train, ~50% duty |
| 28 | PLUCK | sustain 0, ~1 s decay (note-off/decay semantics) | natural decay to silence |

## 5. After recording — hooking it up

1. Place the WAV at `~/a/CAL_SONG/output_000.wav` (or pass `-Dcal.wav=/path/to/it`).
2. Run the calibration scorecard (reuses `FidelityScorecardTest`'s onset-snapped, time-resolved
   comparison; renders each preset through the clean per-preset `renderSynth` path):

   ```bash
   mvn test -Dtest=CalibrationScorecardTest -Dgpg.skip=true
   ```

   **Do not score against `FidelityTestRunner.renderSongToWav` output** — its master chain
   over-drives full-velocity notes into the 0.5 `lshiftAndSaturate` clamp (~25% of samples rail),
   so its spectrum measures clipping, not timbre. Fine as a smoke test, unusable for fidelity.
3. `scripts/cal_analyze.py OURS.wav HARDWARE.wav` does waveform-level checks (onset pairing,
   sine purity, echo timing, arp gate duty) when you need to look below the spectral level.
4. High-value manual checks even before any tooling:
   - Segment 19 is a sine (routing gate) — by ear.
   - Segment 21's first echo at 250 ms after note-off — visible in any editor.
   - Segment 01 vs 03 peak levels (sine-vs-saw balance).
   - Segment 27 gate duty ≈ 50% of a 16th at 120 BPM (62.5 ms on / 62.5 ms off).

## 5b. Results — 2026-07-05 recording (firmware nightly `9456095b`, matching the port's C reference)

Time-resolved median **0.810**, mean 0.720 across the 28 segments — in line with the main
scorecard's ~0.79–0.80, confirming the harness and the recording are sound.

**Hardware-validated (time-resolved ≥ 0.80):** the entire recent fix cluster —
FM CHAIN MUTE **0.918** (win 0.985: the mod1→mod0 mute-gate port, hardware plays the same pure
sine we do), SQUARE 50 0.910, TRIANGLE 0.879, SAW 0.875, ARP GATE 0.874 (gate-duty fix),
LPF RESO 0.871, REVERB SEND 0.871, PLUCK 0.869 (note-off/decay), CHORUS 0.866 (patcher-final
rate), DELAY PINGPONG 0.861, SINE 0.858 (post-undoubling level), PHASER 0.852, SAW PW 0.847
(`resetterPhase >>>` fix), DELAY SYNC 0.810 (sync-mapping fix).

**Confirmed gaps (real divergences, now hardware-attributed):**

| Segment(s) | time | Reading |
|---|---|---|
| 18 FM CHAIN 0.127 · 17 FM HIGH 0.341 · 20 FM FEEDBACK 0.490 | — | MUTE near-perfect but active chains badly off ⇒ FM routing/gating is right; **operator level / index scaling with live modulators is wrong**. Best-scoped fidelity target. |
| 09 SAW SYNC 0.615 · 10 SQ SYNC PW 0.671 | — | hard-sync timbre still diverges |
| 15 HPF RESO 0.449 | — | HPF resonance diverges (check vs the filter-engagement change) |
| 07 PWM LFO 0.670 · 14 LPF DRIVE 0.742 | — | moving-PWM and driven-filter partial |

**Not measurable:** 11 NOISE 0.156 — two independent white-noise realizations are spectrally
uncorrelated; cosine cannot score noise. Minor caveat: `renderSynth` triggers at velocity 110 vs
the song's 127 (timbre-invariant for these presets).

## 6. Known limits of this set (deliberate)

- No SVF / unison-stereo-spread / stutter / sidechain-duck segments: their preset-tag spellings
  or trigger gestures aren't attested in any known-good card preset, and one bad tag makes the
  hardware reject the whole song. Add them in a v2 after confirming the tags on-device.
- Reverb is recorded with model 0 (Freeverb) because the song file pins it; for Mutable/Digital
  ground truth, duplicate `CAL_SONG.XML`, change `model="0"` → `model="1"` (or `"2"`) in the
  `<reverb …>` line, save as `CAL_SONG_MUTABLE.XML`, and record segment 23's region again.
- DX7 segments are excluded — `Dx7ParityTest` already has clean single-note references.
