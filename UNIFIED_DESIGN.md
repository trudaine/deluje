# ChucK-Java Deluge Unified Design Specification

This document defines the authoritative architecture, object model, and interaction design for the desktop-optimized Synthstrom Deluge emulator.

---

## 1. The Deluge Object Model

The software adheres to a nested hierarchy mirroring the Deluge Guidebook 4.0, managed via a persistent **Sidebar Project Manager**.

| Level | Software Entity | XML Association | Description |
| :--- | :--- | :--- | :--- |
| **Song** | **Project** | `SONGS/*.xml` | The master file. Contains global tempo, swing, mixer settings, and references to all Tracks. |
| **Track** | **Instrument Instance** | Nested in Song `.xml` | A single instrument (Synth or Kit). Occupies one or more rows in the Matrix. |
| **Clip** | **Sequence / Pattern** | Nested in Song `.xml` | A 2D grid of steps (notes + parameter locks) belonging to a Track. |
| **Synth** | **Synthesis Engine** | `SYNTHS/*.xml` | Polyphonic melodic engine (Oscillators, Filters, Envelopes). |
| **Kit** | **Percussion Engine** | `KITS/*.xml` | Multi-sound drum engine. Each row maps to a distinct `KitSound` (Sample). |

---

## 2. Interface Layout & Workspace

The UI is designed for high-speed desktop use, utilizing persistent panels to avoid the hardware's "context-switching" limitations.

### A. Sidebar Project Manager (Left)
*   **Project Tree:** Hierarchy of Tracks and their associated Clips. Click to focus; drag to reorder.
*   **SD Card Emulator (Library):** Tree view of `SAMPLES`, `SYNTHS`, `KITS`, and `SONGS`.
    *   **Quick Listen:** Play button next to file names for instant auditioning.
    *   **Drag-and-Drop:** Drag XML presets onto Tracks or WAV samples onto Kit rows.

### B. The Matrix Grid (Center)
*   **Dynamic Grid:** Toggle between 8x16 and 16x16 steps.
*   **Visual State:** Cells glow with velocity-sensitive brightness. Playhead moves with sub-millisecond precision.
*   **Modes:**
    *   **Clip View:** Detailed sequencing of a single track.
    *   **Song View:** Launcher mode for triggering clips and sections (A-Z).
    *   **Arranger View:** Linear timeline for horizontal song structure.

### C. Persistent Panels (Bottom)
*   **Velocity Lane:** Horizontal bar chart showing velocities for all 16 steps of the selected track.
*   **Piano Keyboard:** 88-key interactive piano (from `PianoKeyboard.java`). Scale tones are highlighted based on global settings.

---

## 3. Interaction Design & User Actions

### A. Global Transport & Controls

| Action | Shortcut |
| :--- | :--- |
| Play / Stop | `Spacebar` |
| Save Project | `Ctrl+S` |
| New Project | `Ctrl+N` |
| Export Audio | `File > Export Audio` |
| Tap Tempo | `T` |
| View: Clip | `F1` |
| View: Song | `F2` |
| View: Arranger | `F3` |
| Mute Track 1–8 | `Alt+1` … `Alt+8` |
| Gold Knob 1 | `Q` (hold + drag or scroll) |
| Gold Knob 2 | `W` (hold + drag or scroll) |

### B. Mouse Interaction (Grid)

*   **Toggle Step:** Left-click cell.
*   **Long Note Entry (Synth):** Press and hold a cell, then drag horizontally. A tied-note bar extends across the dragged cells. Dragging to the edge auto-scrolls.
*   **Context Menu:** Right-click cell → **Quantize, Transpose, Legato, Delete**.
*   **Fine Timing:** `Shift` + Drag to disable Snap-to-Grid.
*   **Multi-Select:** Click-drag a marquee box over notes.
*   **Duplicate:** `Alt+Drag` a note or clip.
*   **Copy / Paste:** `Ctrl+C` / `Ctrl+V`.

### C. Navigation & View Control

*   **Horizontal Scroll:** `Shift+Wheel` or drag the bottom scrollbar.
*   **Zoom (Time):** `Ctrl+Wheel`.

### D. Row Header Controls (per track)

Each row in the grid has a compact header strip:

| Control | How to access | What it does |
| :--- | :--- | :--- |
| `[○]` Audition pad | Click | Preview sample / play root note |
| `[M]` Mute | Click | Silence track without deleting pattern |
| `[⚙]` Config | Click | Open track's sound editor dialog |
| Pattern Length badge | Right-click row header → **Set Length** | Set 1–16 steps (polyrhythm) |
| Pattern Length badge | `Ctrl+[` / `Ctrl+]` (track focused) | Decrease / increase length by 1 step |

The **Pattern Length badge** shows the current step count (e.g., `[12]`). When a track's length differs from 16 it is highlighted in amber.

### E. Advanced Editing (Pop-ups)

*   **Graph Editor (Synth):** Double-click a Clip → node-based OSC & FM Matrix.
*   **Randomize / Generate:** Button inside Synth/Kit dialogs.
*   **Multisampling Editor (Kit):** Drag multiple WAVs onto a virtual keyboard to map zones by pitch.

---

## 4. Track Sound Editor Dialogs

### A. Synth Track Config (`[⚙]` on a Synth row)

Opened via `[⚙]` or keyboard shortcut `E` when a Synth row is focused.

#### Arpeggiator section
| Control | Range | Shortcut |
| :--- | :--- | :--- |
| ARP ON toggle | on / off | — |
| Rate | 0.25× – 4× | — |
| Octaves | 1 – 4 | — |

#### Filter section
| Control | Range |
| :--- | :--- |
| Mode | LP / BP / HP |
| Cutoff | 0 – 1 (normalized → 100 Hz – 20 kHz) |
| Resonance (Q) | 0 – 1 (→ 1 – 10) |

#### FM Synthesis section
| Control | Range |
| :--- | :--- |
| FM Ratio | 0.25 – 4.0 |
| FM Amount | 0 – 1 |

#### LFO section (4 slots: LFO 0–3)

Each LFO row contains:

| Control | Values | Notes |
| :--- | :--- | :--- |
| **Shape** | Sine / Saw / Square / Triangle | Combo box |
| **Rate** | 0.01 – 20 Hz | Slider |
| **Depth** | 0 – 1 | Slider; 0 = LFO off (no CPU cost) |
| **Target** | Filter / Res / Pan / Pitch / Vol / FM | Combo box |
| **Scope** | All tracks / This track | Toggle |

**User flow:**
1. Open Synth Config (`[⚙]`).
2. In the LFO section, pick a slot (LFO 0).
3. Set **Shape** = Sine, **Rate** = 0.5 Hz, **Depth** = 0.3, **Target** = Filter.
4. Leave **Scope** = All tracks (or restrict to this row).
5. Press Play — filter cutoff undulates at 0.5 Hz with ±1500 Hz sweep.

**Depth → modulation range mapping:**

| Target | Depth = 1.0 means |
| :--- | :--- |
| Filter cutoff | ±5000 Hz around base value |
| Filter resonance | ±3 Q units around base |
| Pan | ±1.0 (full L↔R) |
| Pitch | ±1 octave |
| Volume | ±50% of current gain |
| FM Amount | ±50% of current FM depth |

#### Keyboard shortcuts inside the Config dialog
| Action | Key |
| :--- | :--- |
| Close dialog | `Esc` |
| Cycle LFO target | `Tab` inside LFO Target combo |
| Reset slider to default | `Double-click` on any slider |

---

### B. Kit Track Config (`[⚙]` on a Kit row)

Opened via `[⚙]` or `E` when a Kit row is focused.

#### Sample section
| Control | Action |
| :--- | :--- |
| Path field | Read-only; shows current sample path |
| Browse button | Opens Sample Browser panel |

#### Pitch & Modulation section
| Control | Range |
| :--- | :--- |
| Pitch (semitones) | −24 to +24 |

#### ADSR section
| Control | Range |
| :--- | :--- |
| Attack | 0.1 ms – 2 s |
| Decay | 1 ms – 5 s |
| Sustain | 0 – 1 |
| Release | 1 ms – 5 s |

#### Mute Group
| Control | Values |
| :--- | :--- |
| Group | None / 1 / 2 / 3 / 4 |

Sounds in the same mute group choke each other (e.g., open and closed hi-hats in Group 1).

#### Reverse toggle
Plays the sample backwards when enabled.

#### LFO modulation (kit rows respond to global LFOs)
Kit rows do **not** have their own LFO editor — they respond to any global LFO whose **Scope** is set to "All tracks" or explicitly to this row's index. Supported targets for kit: **Pitch** and **Vol**.

---

## 5. Polyrhythm — Independent Track Lengths

Each track has a **Pattern Length** (1–16 steps, default 16). The master clock emits a monotonic tick counter; each track independently computes its position as `tick % trackLength`.

**Example — 5-against-7 groove:**
1. Set Track 0 (Kick) length to **5** via right-click row header → Set Length → 5.
2. Set Track 1 (Hi-hat) length to **7**.
3. Press Play. The patterns drift against each other and align every LCM(5,7) = 35 steps.

**Keyboard shortcuts:**
| Action | Key (track row focused) |
| :--- | :--- |
| Decrease length by 1 | `Ctrl+[` |
| Increase length by 1 | `Ctrl+]` |
| Reset to 16 | `Ctrl+Shift+L` |

**Visual feedback:** The active step cell within each row reflects that row's own position — rows of different lengths will show playheads advancing at different rates.

---

## 6. Sidechain Ducking

Track row 0 (first Kit row, conventionally the kick) automatically broadcasts `E_SIDECHAIN` on every hit. The synth bus ducks to 15% gain instantly and recovers over 120 ms in 8 linear steps.

**No user configuration needed for basic use.** Future UI will expose duck depth and release time in a Sidechain panel.

---

## 7. Synthesis & Audio Engine

### Signal Chain
`Source (Osc/Sample) → ADSR → Pan → Mute Group → FX Send (Delay/Reverb) → Master Bus`

For Synth tracks: `MorphingWavetable (FM mod) → SVFilter → ADSR → Pan → Chorus → Delay/Reverb → Synth Bus → HPF → Compressor → Limiter → DAC`

### Modulation System

| Module | Count | Targets |
| :--- | :--- | :--- |
| LFOs | 4 global | Filter, Res, Pan, Pitch, Vol, FM |
| Envelopes (kit) | 1 per kit row | Volume (ADSR shape) |
| Envelopes (synth) | 1 per synth row | Volume (ADSR shape) |
| Sidechain | 1 global | Synth bus gain |

### LFO Engine Details

*   `lfo_shred` runs at **200 Hz** (5 ms steps), independent of the clock tick.
*   Depth = 0 skips computation entirely — no idle CPU cost.
*   4 LFO slots share the same shred; each has its own phase accumulator.
*   Output written to `G_LFO_VALUE[]`; consumed by kit and synth shreds each tick.

### Polyrhythm Engine Details

*   Clock shred emits a **raw monotonic counter** (not `% 16`).
*   Each track reads `G_TRACK_LENGTH[r]` and computes `step = counter % length` independently.
*   Lengths 1–16. All tracks reset to step 0 on Stop.

---

## 8. Technical Implementation (Distributed Shred Architecture)

The engine is modeled as independent, sample-accurate processes (shreds) using the **Fluent Java DSL**.

### Shred Map

| Shred | Role |
| :--- | :--- |
| `transport_shred` | Root; registers global buses; sporks all sub-shreds |
| `clock_shred` | Emits monotonic tick counter at BPM/swing-derived intervals |
| `kit_shred` | Handles all Kit rows; defers until `G_LOAD_TRIGGER` |
| `synth_shred` | Handles all Synth rows; defers until `G_LOAD_TRIGGER` |
| `lfo_shred` | Updates 4 LFO outputs at 200 Hz |
| `sidechain_shred` | Listens for `E_SIDECHAIN`; ducks synth bus |
| `fx_bus_shred` | Manages Delay, Reverb, Chorus buses |
| `master_shred` | HPF → Compressor → Limiter on synth bus |
| `kit_reload_shred` | Re-loads SndBufs on each `G_LOAD_TRIGGER` broadcast |
| `kit_preview_shred` | Auditions a single kit sound on `E_PREVIEW` |

### Bridge Contract Globals (key ones)

| Global | Type | Description |
| :--- | :--- | :--- |
| `G_TRACK_LENGTH` | `int[TRACKS]` | Steps per track (1–16) |
| `G_LFO_RATE` | `float[4]` | Hz per LFO |
| `G_LFO_TYPE` | `int[4]` | 0=sine 1=saw 2=square 3=tri |
| `G_LFO_DEPTH` | `float[4]` | 0–1 modulation depth |
| `G_LFO_TARGET` | `int[4]` | 0=filter 1=res 2=pan 3=pitch 4=vol 5=fm |
| `G_LFO_TRACK` | `int[4]` | −1=all tracks, N=specific row |
| `G_LFO_VALUE` | `float[4]` | Current output (written by engine) |

### Communication & Hot-Swapping
*   **Deferred init:** Kit and Synth shreds block on `G_LOAD_TRIGGER` before allocating UGens, preventing audio underruns at startup.
*   **Hot-Swap:** Reload triggered by broadcasting `G_LOAD_TRIGGER` after changing `g_sample_N`.
*   **Synchronization:** All tick-driven shreds block on the same `tick_event`; LFO shred runs on its own 5 ms timer.

---
*Specification Version 1.2 — April 26, 2026*
