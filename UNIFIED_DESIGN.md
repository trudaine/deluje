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

## 2. Song Lifecycle — Create, Edit, Save, Manage

### A. Song Lifecycle Operations

| Action | Description | Trigger |
| :--- | :--- | :--- |
| **New Song** | Creates a blank project (1 default Kit + 1 Synth track). Prompts to confirm if unsaved changes exist. Clears the audio engine pattern and mutes. | `Ctrl+N` / File > New Project |
| **Open Song** | File chooser → parses Song XML → replaces current project in memory. Engine reloads samples. | `Ctrl+O` / File > Open Project |
| **Save Song** | Saves to the current file path. If not yet saved, delegates to Save As. Window title shows the filename. | `Ctrl+S` / File > Save Project |
| **Save As** | File chooser → saves to a new path → updates current file reference. | `Ctrl+Shift+S` / File > Save Project As |

### B. Track Management

A **Track** is a single instrument row. A song may have any number of tracks (Kit or Synth). All track operations notify the audio engine so playback reflects the new structure immediately.

| Action | How to invoke | Details |
| :--- | :--- | :--- |
| **Add Kit Track** | "+ KIT" toolbar button | Prompts for a name → creates a Kit track with one empty 8×16 clip |
| **Add Synth Track** | "+ SYNTH" toolbar button | Prompts for a name → creates a Synth track with one empty 8×16 clip |
| **Rename Track** | Right-click row header → Rename | In-place text prompt |
| **Set Track Color** | Right-click row header → Set Color | Color chooser; persists to XML as `colourHex` |
| **Move Track Up** | Right-click row header → Move Up | Swaps position with the track above; disabled on first row |
| **Move Track Down** | Right-click row header → Move Down | Swaps position with the track below; disabled on last row |
| **Delete Track** | Right-click row header → Delete Track | Confirmation dialog; removes track and all its clips |

### C. Clip Management

A **Clip** is a pattern (sequence of steps) within a Track. One track can hold multiple clips (like the hardware Deluge's clip launcher). The first clip is the default active clip for playback.

| Action | How to invoke | Details |
| :--- | :--- | :--- |
| **Add Clip** | In Song View, left-click an empty pad slot in a track's row | Creates a new empty clip at that position |
| **Rename Clip** | In Song View, right-click a clip pad → Rename Clip | In-place text prompt |
| **Duplicate Clip** | In Song View, right-click a clip pad → Duplicate Clip | Deep copy — all step data preserved; appended to end of track |
| **Delete Clip** | In Song View, right-click a clip pad → Delete Clip | Confirmation dialog; a track must always retain at least one clip |

### D. Library Directory

All file choosers (Open Song, Save Song, Save Kit, Save Synth) default to subdirectories of the **Library Root**, which is derived from the **Samples Directory** preference (Settings > Set Samples Directory…). The library root is the parent folder of SAMPLES, so the expected on-disk layout is:

```
<Library Root>/
  SONGS/    ← song XML files (.xml)
  KITS/     ← standalone kit preset XML files (.xml)
  SYNTHS/   ← standalone synth preset XML files (.xml)
  SAMPLES/  ← audio samples (WAV, AIFF, etc.)
```

On first run the default root is `~/Deluge/`. Set a different root via **Settings > Set Samples Directory…** pointing to the SAMPLES subfolder; the app derives the root from its parent.

### E. Save Kit / Save Synth as independent presets

To save just one track's instrument definition (no clip data) as a reusable preset:

1. Right-click the row header of a Kit or Synth track.
2. Choose **Save as Kit preset…** or **Save as Synth preset…**.
3. File chooser opens directly in `KITS/` or `SYNTHS/`. The track name is pre-filled as the filename.
4. The saved `.xml` file can be loaded back via the sidebar Library tree (double-click) or drag-and-dropped onto a track.

Kit XML format: root element `<kit>`, children `<sound>` with `<name>`, `<sample fileName="…"/>`, optional `<pitch>`, `<muteGroup>`, `<reverse>`.

Synth XML format: root element `<sound>` with `<osc1 type="…"/>`, `<osc2 type="…"/>`, `<lpf freq="…" res="…"/>`.

### F. Copy / Duplicate a Song

To copy an entire song: **File > Save Project As** — saves the current in-memory state under a new filename. The current session continues editing the original file; the new file is a snapshot.

---

## 3. Interface Layout & Workspace

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

## 4. Interaction Design & User Actions

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
| `[M]` Mute | Click | Silence track without destroying pattern |
| `[⚙]` Config | Click | Open track's sound editor dialog |
| Pattern Length badge | Right-click row header → **Set Length** | Set 1–16 steps (polyrhythm) |
| Pattern Length badge | `Ctrl+[` / `Ctrl+]` (track focused) | Decrease / increase length by 1 step |

The **Pattern Length badge** shows the current step count (e.g., `[12]`). When a track's length differs from 16 it is highlighted in amber.

### E. Row Label Display Rules

Row labels in the grid header depend on the view mode and track type:

| View Mode | Track Type | Row Label Behavior |
| :--- | :--- | :--- |
| SONG / ARRANGER | Any | Each grid row shows the project track name (`tracks.get(t).getName()`) |
| CLIP | Kit | Each row shows the individual `KitSound` name (e.g., KICK, SNARE, HI-HAT, CLAP). Falls back to the track name if the sound index exceeds the number of configured sounds. |
| CLIP | Synth | Row 0 shows the track name. Rows 1–7 show semitone offset labels (`-1st`, `-2st`, etc.) to indicate pitch rows. |

The track type is determined via `bridge.getTrackType(engineRow)`, which reads the local `BridgeContract.trackType` array. This array is kept in sync with the ChucK VM global `g_track_type` during every call to `pushModelToBridge()` (see §10).

### E. Advanced Editing (Pop-ups)

*   **Graph Editor (Synth):** Double-click a Clip → node-based OSC & FM Matrix.
*   **Randomize / Generate:** Button inside Synth/Kit dialogs.
*   **Multisampling Editor (Kit):** Drag multiple WAVs onto a virtual keyboard to map zones by pitch.

---

## 5. Track Sound Editor Dialogs

### A. Synth Track Config (`[⚙]` on a Synth row)

Opened via `[⚙]` button in the row header, or keyboard shortcut `E` when a Synth row is focused. Non-modal; changes apply in real time.

#### Tab 1 — ARP / FILTER / FM

##### Arpeggiator section
| Control | Type | Range | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| ARP ON | Checkbox | on / off | Enable the arpeggiator — plays notes in sequence automatically |
| Rate | Slider | 0.25× – 4.00× | Arpeggiator speed multiplier relative to the song tempo |
| Octaves | Combo | 1 – 4 | Number of octaves the arpeggiator spans before repeating |

##### Filter section
| Control | Type | Range | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| Cutoff | Slider | 0 – 100% | Low-pass filter cutoff frequency (0% = fully closed, 100% = fully open) |
| Resonance | Slider | 0 – 100% | Filter resonance / Q — emphasises frequencies around the cutoff point |

##### FM Synthesis section
| Control | Type | Range | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| FM Ratio | Slider | 0.25 – 4.00 | Frequency ratio of the modulator oscillator relative to the carrier |
| FM Amount | Slider | 0 – 100% | Depth of FM modulation — how strongly the modulator affects the carrier |

#### Tab 2 — LFO (4 slots: LFO 0–3)

Each row controls one LFO slot independently.

| Control | Type | Values | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| Shape | Combo | Sine / Saw / Square / Triangle | Waveform shape of the LFO oscillator |
| Rate | Slider | 0.01 – 20.00 Hz | LFO oscillation speed in cycles per second |
| Depth | Slider | 0 – 100% | Modulation intensity — 0% = off, 100% = maximum swing (see range table below) |
| Target | Combo | Filter / Res / Pan / Pitch / Vol / FM | Which parameter this LFO modulates |
| Scope | Combo | All tracks / This track | Whether this LFO affects all tracks or only the current Synth track |

**Depth = 100% modulation ranges:**

| Target | Effect at full depth |
| :--- | :--- |
| Filter cutoff | ±5000 Hz around the base cutoff |
| Filter resonance | ±3 Q units around the base resonance |
| Pan | ±1.0 (full left ↔ right sweep) |
| Pitch | ±1 octave |
| Volume | ±50% of current gain |
| FM Amount | ±50% of current FM depth |

**User flow — add vibrato:**
1. Open Synth Config (`[⚙]`) → LFO tab.
2. LFO 0: Shape = Sine, Rate = 5.0 Hz, Depth = 20%, Target = Pitch, Scope = This track.
3. Press Play — the synth pitch warbles at 5 Hz.

#### Keyboard shortcuts inside the Config dialog
| Action | Key |
| :--- | :--- |
| Close dialog | `Esc` |
| Reset slider to default | `Double-click` on any slider |

---

### B. Kit Track Config (`[⚙]` on a Kit row)

Opened via `[⚙]` button in the row header, or `E` when a Kit row is focused. Non-modal. Each drum sound has its own tab (KICK, SNARE, etc.).

#### Sample section (per sound tab)
| Control | Type | Tooltip / Description |
| :--- | :--- | :--- |
| Sample path | Text (read-only) | Full path to the audio file currently assigned to this sound |
| Browse... | Button | Open file chooser rooted at the library Samples directory; supports WAV, AIFF, FLAC |

Selecting a file updates the model and the engine immediately — the sound plays with the new sample on the next trigger.

#### Pitch section
| Control | Type | Range | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| Pitch (ST) | Slider | −24 to +24 semitones | Transpose this sound up or down without resampling |

#### ADSR Envelope section
| Control | Type | Range | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| Attack | Slider | 0 – 2000 ms | Time to ramp from silence to full volume after the note triggers |
| Decay | Slider | 0 – 5000 ms | Time to fall from peak level down to the sustain level |
| Sustain | Slider | 0 – 100% | Volume level held while the note is held, after the decay phase |
| Release | Slider | 0 – 5000 ms | Time to fade to silence after the note is released |

#### Mute Group section
| Control | Type | Values | Tooltip / Description |
| :--- | :--- | :--- | :--- |
| Mute Group | Combo | None / 1 / 2 / 3 / 4 | Sounds in the same group choke each other — e.g. open/closed hi-hat in Group 1 |

#### Reverse
| Control | Type | Tooltip / Description |
| :--- | :--- | :--- |
| Reverse | Checkbox | Play the sample backwards — useful for reverse cymbal or snare effects |

#### LFO modulation (kit rows respond to global LFOs)
Kit rows do **not** have their own LFO editor — they respond to any global LFO (configured in any Synth Config → LFO tab) whose **Scope** is set to "All tracks". Supported targets for kit sounds: **Pitch** and **Vol**.

---

## 6. Polyrhythm — Independent Track Lengths

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

## 7. Sidechain Ducking

Track row 0 (first Kit row, conventionally the kick) automatically broadcasts `E_SIDECHAIN` on every hit. The synth bus ducks to 15% gain instantly and recovers over 120 ms in 8 linear steps.

**No user configuration needed for basic use.** Future UI will expose duck depth and release time in a Sidechain panel.

---

## 8. Synthesis & Audio Engine

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

## 9. Technical Implementation (Distributed Shred Architecture)

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
| `G_TRACK_TYPE` | `int[TRACKS]` | −1=unused, 0=kit, 1=synth |
| `G_LFO_RATE` | `float[4]` | Hz per LFO |
| `G_LFO_TYPE` | `int[4]` | 0=sine 1=saw 2=square 3=tri |
| `G_LFO_DEPTH` | `float[4]` | 0–1 modulation depth |
| `G_LFO_TARGET` | `int[4]` | 0=filter 1=res 2=pan 3=pitch 4=vol 5=fm |
| `G_LFO_TRACK` | `int[4]` | −1=all tracks, N=specific row |
| `G_LFO_VALUE` | `float[4]` | Current output (written by engine) |

## 10. Java↔ChucK Bridge Dual-Array Pattern

The `BridgeContract` maintains two parallel arrays for shared state:

- **Local Java array** (`BridgeContract.trackType[]`) — read by the Swing UI via `bridge.getTrackType()`.
- **VM global array** (`g_track_type` on the ChucK side) — read by ChucK engine shreds.

**Critical invariant:** both arrays must hold identical values. The Swing UI reads only the local array; the engine reads only the VM global.

### Sync enforcement

Every call to `pushModelToBridge()` in `SwingDelugeApp.java` synchronizes both arrays:

1. Initialises all 64 entries to `-1` (unused) on both sides.
2. For each Kit track: sets `bridge.setTrackType(startRow+v, 0)` locally and `trackTypeArr.setInt(startRow+v, 0L)` on the VM.
3. For each Synth track: sets `bridge.setTrackType(startRow, 1)` locally and `trackTypeArr.setInt(startRow, 1L)` on the VM.

The local array was originally only initialised to 0 in the `BridgeContract` constructor but never updated by `pushModelToBridge()`. This caused `bridge.getTrackType()` to always return 0, which made the grid row-label logic and kit/synth code-path dispatch in `SwingGridPanel` fail for synth tracks. The fix adds explicit `bridge.setTrackType()` calls at every point where the VM global is written.

### When sync is triggered

`pushModelToBridge()` is called on:
- Song load (`Ctrl+O` / File → Open Project)
- New project (`Ctrl+N`)
- Track add / remove / reorder
- Clip view entry (SONG → CLIP switch via `onEditRequest`)
- Any `loadTrigger` broadcast

### Communication & Hot-Swapping
*   **Deferred init:** Kit and Synth shreds block on `G_LOAD_TRIGGER` before allocating UGens, preventing audio underruns at startup.
*   **Hot-Swap:** Reload triggered by broadcasting `G_LOAD_TRIGGER` after changing `g_sample_N`.
*   **Synchronization:** All tick-driven shreds block on the same `tick_event`; LFO shred runs on its own 5 ms timer.

---
*Specification Version 1.3 — April 28, 2026*
