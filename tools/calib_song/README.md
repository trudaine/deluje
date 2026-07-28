# CALIB — a second calibration corpus for the fidelity scorecard

## Why

`FidelityScorecardTest` renders the ALLSYN songs. A census of that corpus
(`docs/FIDELITY_GAP_ANALYSIS.md` §4.2duosexagies) showed it exercises far less of the engine than
its 188 presets suggest:

| subsystem | presets exercising it in ALLSYN |
| --- | --- |
| reverb | 43 / 188 |
| HPF | 27 / 188 — but all 188 carry `hpfMode="12dB"`, which is **inert** on hardware |
| delay | **0 / 188** |
| modFX | **0 / 188** |
| noise | **0 / 188** |
| wavetable oscillator | **0 / 188** |
| register | C4 only |

Those subsystems are not passing — they are **unmeasured**. Two golden-verified DSP fixes shipped in
July 2026 (the wavetable windowed-sinc kernel row, and the crude-saw phase double-advance) landed in
exactly these blind spots, which is why neither moved the scorecard median by so much as 0.001.

CALIB covers the gaps, in the shape the scorecard already knows how to slice.

## Generate

```bash
cd tools/calib_song
python3 gen_calib.py --out /tmp/calib_out
```

Produces:

```
/tmp/calib_out/SONGS/CALIB1.XML  CALIB2.XML  CALIB3.XML   # 94 + 94 + 55 sounds
/tmp/calib_out/SYNTHS/*.XML                               # 243 standalone presets
/tmp/calib_out/SAMPLES/WAVETABLES/WTSAWSQ.WAV  WTHARM.WAV  WTFORM.WAV
/tmp/calib_out/calib_manifest.csv                         # one row per case + every varied knob
```

Copy `SONGS/`, `SYNTHS/` and `SAMPLES/WAVETABLES/` onto the SD card, preserving those paths (the
wavetable presets reference `SAMPLES/WAVETABLES/*.WAV`).

## The matrix — 243 cases, ~12 min of recording

Every case is a fixed base voice with **one knob moved**, so a hardware-vs-us difference points at a
single subsystem. Interaction cases are separate and labelled in the manifest.

| group | n | what it covers |
| --- | --- | --- |
| `modfx` | 71 | 7 types (chorus, StereoChorus, flanger, phaser, TapeWarble, dimension, grainFX) × 3 depths × 3 rates, plus offset/feedback sweeps for flanger and phaser |
| `hpf` | 36 | 4 real HPF modes (HPLadder, SVF, SVF_BAND, SVF_NOTCH) × 3 frequencies × 3 resonances — every existing preset's HPF is inert |
| `delay` | 36 | rate × feedback × pingPong × analog |
| `wavetable` | 30 | 3 generated tables (16/8/64 cycles) × 5 positions × 2 registers |
| `register` | 22 | 6 osc types at C1/C2/C3 — below ~C3 the oscillators leave the band-limited tables for the crude `tableNumber<6` paths, never once compared to hardware |
| `drive` | 17 | clippingAmount 1–7 × 2 levels, plus waveFold |
| `morph` | 15 | 3 LPF modes × 5 morph positions |
| `noise` | 9 | noise volume × LPF cutoff |
| `reverb` | 5 | send amount |
| `control` | 2 | dry saw at C4 and C2, to separate session-level drift from the effect under test |

**Reverb caveat:** `roomSize` / `dampening` / `width` are *song-level* settings, so they cannot vary
per case inside one song. Only the per-clip send is swept here. To characterise the reverb model
itself, edit the `<reverb>` line in `template_blocks.py:TAIL` and record additional songs.

**Non-deterministic cases:** `grainFX` and the `noise` group are stochastic. The scorecard compares
normalised log-magnitude spectra, which is still meaningful for noise, but the manifest flags them
(`nondeterministic=True`) so they can be scored separately or excluded.

## Record

Same procedure as the existing recordings (`HARDWARE_FIDELITY.md`): load each song, record the
arrangement from the top, save as `output_000.wav` under a directory named for the song.

```
$SCORECARD_RECORDINGS/CALIB1/output_000.wav
$SCORECARD_RECORDINGS/CALIB2/output_000.wav
$SCORECARD_RECORDINGS/CALIB3/output_000.wav
```

Geometry matches ALLSYN exactly — clip length 768 ticks, one sound every 1152 ticks, i.e. a 2 s note
and 1 s of silence at 120 BPM — so the scorecard's onset detection works unchanged.

## Pre-flight: what our engine already does with these cases

Before any hardware exists, every case was rendered through our engine and fingerprinted
(32 log-spaced spectral bands + 8 amplitude bins). All 243 render **non-silent**. Cases that produce
a *bit-identical* fingerprint to another case in their group mean the knob does nothing in our
engine — which is worth knowing before spending time in front of the hardware:

| group | distinct / n | finding |
| --- | --- | --- |
| `wavetable`, `register`, `delay`, `morph`, `noise`, `reverb` | all distinct | every knob is wired |
| `modfx` | **16 / 71** | `chorus`, `StereoChorus`, `dimension`, `TapeWarble`, `grainFX` do not respond to depth or rate at all; only `flanger` and `phaser` vary. `modFXOffset` changes nothing for either (`o25` ≡ `o75` at equal feedback). |
| `hpf` | **18 / 36** | `SVF`, `SVF_BAND` and `SVF_NOTCH` render **identically**. In the C only `SVF_BAND` and `SVF_NOTCH` are dispatched by `renderHPFLong` (filter_set.cpp:26-33) — plain `SVF` in the HPF slot should be *inert* — and band vs notch differ by `band_mode` (svf.cpp:48). Both distinctions appear to be missing. `HPLadder` responds correctly. |
| `drive` | **11 / 17** | `waveFold` at 25/50/75 % is bit-identical — the fold param appears unimplemented. The `clippingAmount` ≡ level equivalences (`c2 v100` ≡ `c4 v050`) are plausibly correct drive-law behaviour, not necessarily a bug. |

These are gaps between our engine and *expectation*; only the HPF `SVF`/`SVF_BAND`/`SVF_NOTCH` one is
already confirmed against the C source. The rest need the hardware recording to settle — which is
the entire point of the corpus.

## Joining scores back to the matrix

```bash
mvn test -Dtest=FidelityScorecardTest -Dgpg.skip=true \
  -Ddeluge.card=/path/to/card -Dscorecard.recordings=/path/to/recordings \
  -Dscorecard.csv=/tmp/calib_scores.csv
```

Join `calib_scores.csv` on `name` against `calib_manifest.csv` to get score-per-knob-setting, the
same way §4.2duosexagies profiled ALLSYN.

## Regenerating the skeleton

`template_blocks.py` holds song attributes, `<sections>`, the reverb/delay/sidechain/songParams tail
and the LFO/arpeggiator/defaultParams/modKnobs blocks, copied **verbatim** from a hardware-written
ALLSYN song so the Deluge accepts the generated files. If the firmware's song format moves:

```bash
python3 tools/calib_song/extract_template.py /path/to/card/SONGS/ALLSYN_1.XML
```
