# Deluge Workstation — Java Swing Edition
## Quick Reference Guide v1.0

> Equivalent of the Synthstrom Deluge Community Quick Reference (v3.1), adapted for the
> desktop Java Swing application. Hardware buttons → keyboard shortcuts and mouse gestures.
> Sections marked **⚠ NOT YET IMPLEMENTED** exist in the engine or model but have no UI yet.

---

## Symbol Key

| Symbol | Meaning |
| :--- | :--- |
| `Click` | Left mouse button |
| `Right-click` | Right mouse button |
| `Hold` | Press and keep held |
| `Drag` | Hold + move mouse |
| `Ctrl+X` | Hold Ctrl, press X |
| `Shift+X` | Hold Shift, press X |
| `Alt+X` | Hold Alt, press X |
| `Wheel` | Mouse scroll wheel |
| **[▶]** | Play button in toolbar |
| **[■]** | Stop button in toolbar |
| **[○]** | Audition pad (row header) |
| **[M]** | Mute button (row header) |
| **[⚙]** | Config / Sound Editor button (row header) |
| `Q` `W` | Gold Knob 1 / Gold Knob 2 (hold + wheel or drag) |
| ⚠ | Feature not yet implemented in Swing UI |

---

## Window Layout

```
┌─────────────────────────────────────────────────────────────────────┐
│ Menu: File | Settings | View                          [MIDI status] │
├──────────────────┬──────────────────────────────────────────────────┤
│                  │  Toolbar: [▶] [■] BPM──── SWING──── [VOL]       │
│  Sidebar         ├──────────────────────────────────────────────────┤
│  (Project        │  View tabs: [CLIP] [SONG] [ARRANGEMENT]          │
│   Tree /         ├──────────────────────────────────────────────────┤
│   Library)       │                                                  │
│                  │         GRID (rows × 18 columns)                 │
│  SONGS/          │  [○][M][⚙] track name │ step cells 1-16 │ctrl│  │
│  KITS/           │  [○][M][⚙] track name │ step cells 1-16 │ctrl│  │
│  SYNTHS/         │         …                                        │
│  SAMPLES/        ├──────────────────────────────────────────────────┤
│                  │  Velocity / visualiser strip                     │
└──────────────────┴──────────────────────────────────────────────────┘
```

---

## Song / Project Lifecycle `PL`

### PL-A: Song operations

| # | Action | How | Notes |
| :--- | :--- | :--- | :--- |
| PL01 | **New Song** | `Ctrl+N` or File > New Project | Confirms before discarding unsaved changes; creates 1 Kit + 1 Synth track |
| PL02 | **Open Song** | `Ctrl+O` or File > Open Project | File chooser → parses Song XML; engine reloads samples |
| PL03 | **Save Song** | `Ctrl+S` or File > Save Project | Saves to current file; shows Save As dialog if not yet saved |
| PL04 | **Save Song As** | `Ctrl+Shift+S` or File > Save Project As | New filename; window title updates |
| PL05 | **Duplicate Song** | File > Save Project As, then `Ctrl+N` to start fresh | Saves current state as a new file; original is unaffected |
| PL06 | **Open Synth editor standalone** | Sidebar → double-click a file in SYNTHS/ | Loads as a single-track CLIP-mode view |
| PL07 | **Open Kit editor standalone** | Sidebar → double-click a file in KITS/ | Loads as a single-track CLIP-mode view |

### PL-B: Track management

| # | Action | How | Notes |
| :--- | :--- | :--- | :--- |
| PL10 | **Add Kit track** | Toolbar "+ KIT" button | Prompts for name; adds a Kit row with one empty clip |
| PL11 | **Add Synth track** | Toolbar "+ SYNTH" button | Prompts for name; adds a Synth row with one empty clip |
| PL12 | **Rename track** | Right-click row header → Rename | In-place prompt |
| PL13 | **Set track color** | Right-click row header → Set Color | Color chooser; saved to XML |
| PL14 | **Move track up** | Right-click row header → Move Up | Disabled when track is already first |
| PL15 | **Move track down** | Right-click row header → Move Down | Disabled when track is already last |
| PL16 | **Delete track** | Right-click row header → Delete Track | Confirmation dialog; removes all clips |

### PL-C: Clip management (in Song View)

| # | Action | How | Notes |
| :--- | :--- | :--- | :--- |
| PL20 | **Add clip to track** | In SONG view, left-click an empty pad slot in the track's row | Creates a new empty clip at that position |
| PL21 | **Rename clip** | In SONG view, right-click a clip pad → Rename Clip | In-place prompt |
| PL22 | **Duplicate clip** | In SONG view, right-click a clip pad → Duplicate Clip | Deep copy; appended after existing clips |
| PL23 | **Delete clip** | In SONG view, right-click a clip pad → Delete Clip | Confirmation; a track must retain at least one clip |
| PL24 | **Copy clip between songs** | Duplicate source song (PL05), then open target song and use LOAD XML to import | Full inter-song clipboard ⚠ not yet implemented |

---

## GLOBAL `GL`

| # | Action | How |
| :--- | :--- | :--- |
| GL01 | Zoom grid (time) | `Ctrl+Wheel` |
| GL02 | Scroll grid left/right | `Shift+Wheel` or drag scrollbar |
| GL03 | Scroll grid up/down | `Wheel` on grid |
| GL04 | Undo | `Ctrl+Z` |
| GL05 | Redo | `Ctrl+Shift+Z` or `Ctrl+Y` |
| GL06 | Load Song (XML) | `Ctrl+O` or File > Open Project, or Sidebar double-click |
| GL07 | Save Song | `Ctrl+S` or File > Save Project |
| GL07b | Save Song As | `Ctrl+Shift+S` or File > Save Project As |
| GL08 | New Song | `Ctrl+N` or File > New Project |
| GL09 | Save + Collect All Samples | ⚠ not yet implemented |
| GL10 | Change Tempo | BPM slider (toolbar), or `Ctrl+Up/Down` |
| GL11 | Tap Tempo | `T` (focus on main window) |
| GL12 | Metronome On/Off | ⚠ not yet implemented |
| GL13 | Swing | SWING slider (toolbar), default 50% = no swing |
| GL14 | Settings / Preferences | **Settings > Preferences…** |
| GL15 | Set Samples Directory | **Settings > Set Samples Directory…** |
| GL16 | Reverb Model | **Settings > Preferences… → Reverb Model** (JCRev / FreeVerb / MVerb / ProceduralReverb) |
| GL17 | MIDI Input Device | **Settings > Preferences… → MIDI Input** |
| GL18 | Adjust UI Font Size | Controlled by `PreferencesManager` (`window.width/height`) |
| GL19 | Firmware Update | N/A — update the JAR file |
| GL20 | Show/Hide Visualisers | **Settings > Preferences… → Show Visualizers** |

> **Song slots**: songs load directly from XML files on disk. No slot-number concept.
> **QWERTY search**: use the sidebar tree filter field to locate files quickly.

---

## SEQUENCING `SQ`

### Step Grid

| # | Action | How |
| :--- | :--- | :--- |
| SQ01 | Toggle step on/off | `Click` cell |
| SQ02 | Make a long note (tie) | `Hold` first cell + `Click` end cell on same row |
| SQ03 | Adjust note velocity | `Right-click` cell → **Step Editor** → velocity slider |
| SQ04 | Set note probability | `Right-click` cell → **Step Editor** → probability slider |
| SQ05 | Set per-step filter | `Right-click` cell → **Step Editor** → filter offset |
| SQ06 | Set per-step pan | `Right-click` cell → **Step Editor** → pan offset |
| SQ07 | Set sample start/end | `Right-click` cell → **Step Editor** → start / end |
| SQ08 | Duplicate clip content | ⚠ not yet implemented (`Ctrl+D` planned) |
| SQ09 | Cross-screen editing | ⚠ not yet implemented |
| SQ10 | Add instrument / row | ⚠ rows loaded from XML only; drag-and-drop planned |
| SQ11 | Record live notes | `R` (while focused on grid; triggers engine record mode) |
| SQ12 | Resample | ⚠ not yet implemented |
| SQ13 | Shift all clip notes | ⚠ not yet implemented |
| SQ14 | Clear clip | ⚠ not yet implemented (`Ctrl+Delete` planned) |
| SQ15 | Change clip / row colour | ⚠ not yet implemented |
| SQ16 | Note nudge left/right | ⚠ not yet implemented |
| SQ17 | Reduce clip length | `Ctrl+[` (decreases `G_TRACK_LENGTH` by 1) |
| SQ18 | Increase clip length | `Ctrl+]` (increases `G_TRACK_LENGTH` by 1) |
| SQ19 | Reset clip length to 16 | `Ctrl+Shift+L` |
| SQ20 | Poly Rhythms | Set each row's length independently via `Ctrl+[` / `Ctrl+]` when that row is focused. Rows cycle at their own length against the master clock. |
| SQ21 | Move kit clip row | ⚠ not yet implemented |
| SQ22 | Note repeat | ⚠ not yet implemented |
| SQ23 | Copy notes | `Ctrl+C` (⚠ planned) |
| SQ24 | Paste notes | `Ctrl+V` (⚠ planned) |
| SQ25 | Set note iteration group | ⚠ not yet implemented |

> **Poly Rhythm example (5 against 7):**
> 1. Click Track 0 row header to focus it → press `Ctrl+[` until badge shows **[5]**.
> 2. Click Track 1 row header → press `Ctrl+]` until badge shows **[7]**.
> 3. Press `Space` to play — tracks realign every 35 steps (LCM of 5 and 7).

---

## SONG VIEW `SV`

Switch to Song view: `F2` or click **[SONG]** tab.

| # | Action | How |
| :--- | :--- | :--- |
| SV01 | Launch / stop clip | `Click` clip block in song grid |
| SV02 | Launch clip immediately | `Click` (clips always launch immediately in software) |
| SV03 | Enter clip (edit) | `Double-click` clip block → switches to CLIP view |
| SV04 | Create new clip | `Right-click` empty row → **New Clip** |
| SV05 | Move row up/down | ⚠ not yet implemented (drag-and-drop planned) |
| SV06 | Clone clip | ⚠ not yet implemented |
| SV07 | Delete clip | ⚠ not yet implemented (`Delete` key planned) |
| SV08 | Solo track | `Click` **[S]** button on row header (dims all other rows) |
| SV09 | Un-solo | `Click` **[S]** again |
| SV10 | Mute / unmute track | `Click` **[M]** button on row header |
| SV11 | Mute tracks 1–8 | `Alt+1` … `Alt+8` |
| SV12 | Arm clip for recording | ⚠ not yet implemented |
| SV13 | Clip section colour | ⚠ not yet implemented |
| SV14 | Section repeat count | ⚠ not yet implemented |
| SV15 | Share / clone clip status | ⚠ not yet implemented |
| SV16 | Check clip / instrument name | Hover over row header — tooltip shows name |

> **Song vs. Clip view**: Song view shows all clips as coloured blocks per track.
> Clip view shows the step grid for one clip. `F1` / `F2` toggle between them.

---

## RECORDING & SAMPLES `RS`

| # | Action | How |
| :--- | :--- | :--- |
| RS01 | Record sample (live) | ⚠ not yet implemented |
| RS02 | Resample synth output | ⚠ not yet implemented |
| RS03 | Load sample into kit row | Row **[⚙]** → Sample section → **Browse…** |
| RS04 | Load all samples to kit | ⚠ (auto-loaded from XML on song load) |
| RS05 | Slice sample | ⚠ not yet implemented |
| RS06 | Loop resample | ⚠ not yet implemented |
| RS07 | Rename sample | ⚠ rename via OS file manager |
| RS08 | Multi-sampling | ⚠ not yet implemented |

> All samples must be inside the project's `/SAMPLES/` folder or subdirectory.
> The engine resolves classpath resources first, then copies to system temp if needed.

---

## AUDIO CLIPS `AC`

| # | Action | How |
| :--- | :--- | :--- |
| AC01 | Create new audio clip | ⚠ not yet implemented |
| AC02 | Set input source | ⚠ not yet implemented |
| AC03 | Audio monitoring | ⚠ not yet implemented |
| AC04 | Change waveform start/end | ⚠ not yet implemented |
| AC05–09 | All audio clip ops | ⚠ not yet implemented |

---

## MODIFYING SOUNDS `MS`

### Kit Track  —  open with row **[⚙]**

| # | Action | How |
| :--- | :--- | :--- |
| MS01 | Load / change sample | **[⚙]** → Sample → **Browse…** |
| MS02 | Pitch (semitones) | **[⚙]** → Pitch & Modulation → Pitch slider (−24 to +24) |
| MS03 | Reverse playback | **[⚙]** → Reverse toggle ⚠ (UI not yet in Swing KitConfigDialog) |
| MS04 | Per-sample ADSR | **[⚙]** → ADSR section ⚠ (UI not yet in Swing KitConfigDialog) |
| MS05 | Mute group / choke | **[⚙]** → Mute Group picker ⚠ (UI not yet in Swing KitConfigDialog) |
| MS06 | Sample start/end points | `Right-click` step → Step Editor |
| MS07 | Clone kit preset | ⚠ not yet implemented |
| MS08 | New kit from synth engine | ⚠ not yet implemented |
| MS09 | Kit clip affect all rows | ⚠ not yet implemented |

### Synth Track  —  open with row **[⚙]**

| # | Action | How |
| :--- | :--- | :--- |
| MS10 | Oscillator type | **[⚙]** → Oscillator section → Type combo (Sine/Saw/Square/Tri) |
| MS11 | Filter mode / cutoff / res | **[⚙]** → Filter section |
| MS12 | FM ratio / FM amount | **[⚙]** → FM Synthesis section |
| MS13 | Arpeggiator on/rate/octave | **[⚙]** → Arpeggiator section |
| MS14 | LFO 0–3 (rate/shape/depth/target) | **[⚙]** → LFO section ⚠ (UI not yet in Swing SynthConfigDialog) |
| MS15 | Change scale | ⚠ not yet implemented |
| MS16 | Change root note | ⚠ not yet implemented |
| MS17 | Chromatic mode | ⚠ not yet implemented |
| MS18 | Transpose clip up/down octave | ⚠ not yet implemented |
| MS19 | Transpose clip by semitone | ⚠ not yet implemented |
| MS20 | Clone synth preset | ⚠ not yet implemented |
| MS21 | Save preset | **[⚙]** → **💾 SAVE PRESET** (JavaFX SynthConfigDialog only) |

---

## PARAMETER CONTROLS `PD`

The hardware's gold knobs (LR = left/right, UR = up/right) map to:

| Hardware | Software equivalent |
| :--- | :--- |
| LR knob (parameter 1) | `Q` key + `Wheel` (when main window focused) |
| UR knob (parameter 2) | `W` key + `Wheel` (when main window focused) |
| Press LR/UR to select parameter row | Click the parameter row in a config dialog |
| Affect Entire (all tracks) | ⚠ not yet implemented as a toggle |

### Parameter dial layout (mirrors hardware shortcut grid)

| Row | Q-key (LR) controls | W-key (UR) controls |
| :--- | :--- | :--- |
| MASTER | Volume | Pan |
| LPF | Cutoff frequency | Resonance |
| HPF | Cutoff frequency | Resonance |
| ENV 1 | Attack | Release |
| DELAY | Feedback | Time |
| REVERB | Room size | Damping |
| MOD FX | Rate | Depth |
| SIDECHAIN | Duck depth | Release time |

| # | Action | How |
| :--- | :--- | :--- |
| PD01 | Assign Q/W knob to parameter | Click parameter label in toolbar or config dialog |
| PD02 | Check current Q/W assignment | Shown in toolbar status label |
| PD03 | Record automation | `R` + move slider/knob while playing ⚠ not yet fully implemented |
| PD04 | Delete automation | ⚠ not yet implemented |
| PD05 | Copy automation | ⚠ not yet implemented |
| PD06 | Paste automation | ⚠ not yet implemented |
| PD07 | Parameter lock per step | `Right-click` step → Step Editor (filter, pan, start/end implemented) |
| PD08 | Parameter lock per clip | ⚠ not yet implemented |

---

## LFO MODULATION `LF`

*(New in Swing engine — no hardware equivalent in Community Guide)*

| # | Action | How |
| :--- | :--- | :--- |
| LF01 | Open LFO editor (synth) | Row **[⚙]** → LFO section ⚠ (UI panel not yet in Swing SynthConfigDialog) |
| LF02 | Set LFO shape | LFO slot → Shape combo: Sine / Saw / Square / Triangle |
| LF03 | Set LFO rate | LFO slot → Rate slider (0.01–20 Hz) |
| LF04 | Set LFO depth | LFO slot → Depth slider (0 = off, no CPU cost) |
| LF05 | Set LFO target | LFO slot → Target combo: Filter / Res / Pan / Pitch / Vol / FM |
| LF06 | Scope to one track | LFO slot → Scope toggle: All / This Track |
| LF07 | Disable LFO | Set Depth to 0 |

**Depth → modulation range:**

| Target | Depth 1.0 = |
| :--- | :--- |
| Filter cutoff | ±5000 Hz around base |
| Filter resonance | ±3 Q units |
| Pan | ±1.0 full L↔R |
| Pitch | ±1 octave |
| Volume | ±50% of gain |
| FM amount | ±50% of FM depth |

---

## MIDI COMMANDS `MC`

| # | Action | How |
| :--- | :--- | :--- |
| MC01 | Set MIDI input device | **Settings > Preferences… → MIDI Input** |
| MC02 | Set MIDI output device | ⚠ not yet selectable in Preferences |
| MC03 | MIDI note triggers synth | Incoming MIDI on active channel plays synth root note |
| MC04 | MIDI note triggers kit | Incoming MIDI note triggers kit row by pitch |
| MC05 | MIDI learn parameter | ⚠ not yet implemented |
| MC06 | External controller trigger play/stop | ⚠ MIDI CC → transport mapping not yet implemented |
| MC07 | MIDI channel per kit row | ⚠ not yet in UI |
| MC08 | Nudge MIDI clock | ⚠ not yet implemented |
| MC09 | Sync scaling | ⚠ not yet implemented |
| MC10 | Un-learn controller | ⚠ not yet implemented |

> MIDI input is routed through `MidiService`. Notes received on the active channel
> trigger the engine the same way as clicking the **[○]** audition pad.

---

## SONG / CLIP / ARRANGEMENT VIEWS

### Switching views

| View | Shortcut | Description |
| :--- | :--- | :--- |
| Clip view | `F1` | Step grid for one clip |
| Song view | `F2` | Clip launcher — all tracks visible as coloured blocks |
| Arrangement view | `F3` | Linear timeline (⚠ grid renders but editing not yet wired) |

### Arrangement view `AV`

| # | Action | How |
| :--- | :--- | :--- |
| AV01 | Add clip instance to arranger | ⚠ not yet implemented |
| AV02 | Move clip instance | ⚠ not yet implemented |
| AV03 | Change instance length | ⚠ not yet implemented |
| AV04 | Delete clip instance | ⚠ not yet implemented |
| AV05 | Mute / unmute in arranger | ⚠ not yet implemented |
| AV06 | Solo in arranger | ⚠ not yet implemented |
| AV07 | Enter clip from arranger | ⚠ not yet implemented |
| AV08 | Scroll timeline | `Shift+Wheel` |
| AV09 | Zoom timeline | `Ctrl+Wheel` |
| AV10 | Rename track | ⚠ not yet implemented |
| AV11 | Insert/delete time | ⚠ not yet implemented |
| AV12 | Switch arranger ↔ song loop | ⚠ not yet implemented |

---

## LOOPER `LO`

| # | Action | How |
| :--- | :--- | :--- |
| LO01–LO12 | All looper features | ⚠ not yet implemented |

---

## WAVEFORMS & MULTI-SAMPLING `WF`

| # | Action | How |
| :--- | :--- | :--- |
| WF01 | Load single sample as synth | ⚠ not yet implemented |
| WF02 | Load single-cycle waveform | ⚠ not yet implemented |
| WF03 | Multi-sample mapping | ⚠ not yet implemented |
| WF04 | Loop start / end point | ⚠ not yet implemented |
| WF05 | Waveform view zoom | ⚠ not yet implemented |

---

## QWERTY PIANO (KEYBOARD VIEW)

When Clip view is active and a **Synth** row is focused, the QWERTY keyboard
plays notes chromatically, identical to a piano keyboard layout:

```
  S  D     G  H  J
 Z  X  C  V  B  N  M
 C4 D4 E4 F4 G4 A4 B4 C5
```

| Key | Note |
| :--- | :--- |
| `Z` | C4 |
| `S` | C#4 |
| `X` | D4 |
| `D` | D#4 |
| `C` | E4 |
| `V` | F4 |
| `G` | F#4 |
| `B` | G4 |
| `H` | G#4 |
| `N` | A4 |
| `J` | A#4 |
| `M` | B4 |

> Notes flash the isomorphic grid highlight. Future: octave shift with `Shift+Z/X`.

---

## ISOMORPHIC GRID (CLIP VIEW — SYNTH)

In Clip view with a Synth track, the grid shows scale notes as coloured pads.
Each row is one semitone higher. The isomorphic layout mirrors the hardware:

- **Left → Right**: ascending by major 2nd (one step in scale)
- **Down → Up**: ascending by perfect 4th

Chords visible as diagonal shapes — same shape = same chord type in any key.

*(See hardware guide pages 26–27 for chord shape diagrams — shapes are identical in software.)*

---

## SIDECHAIN DUCKING

No user configuration required for basic use.

| Event | What happens |
| :--- | :--- |
| Kit row 0 (kick) fires | Synth bus ducks to 15% instantly |
| 60 ms hold | Gain held at 15% |
| 120 ms ramp | Gain returns to 100% in 8 linear steps |

To customise: edit `duckedGain`, `duckMs`, `releaseMs` in `DelugeEngineDSL.sidechain_shred()`.
*(UI controls planned in a future Sidechain panel.)*

---

## COMPLETE KEYBOARD SHORTCUT REFERENCE

### Transport

| Action | Key |
| :--- | :--- |
| Play / Stop | `Spacebar` |
| Tap Tempo | `T` |
| Record | `R` |

### File

| Action | Key |
| :--- | :--- |
| New Project | `Ctrl+N` |
| Save Project | `Ctrl+S` |
| Open Project | `Ctrl+O` ⚠ |

### Navigation

| Action | Key |
| :--- | :--- |
| Clip view | `F1` |
| Song view | `F2` |
| Arranger view | `F3` |
| Scroll grid left/right | `Shift+Wheel` |
| Scroll grid up/down | `Wheel` |
| Zoom in/out | `Ctrl+Wheel` |
| Undo | `Ctrl+Z` |
| Redo | `Ctrl+Shift+Z` |

### Track

| Action | Key |
| :--- | :--- |
| Mute track 1 – 8 | `Alt+1` … `Alt+8` |
| Solo focused track | `Alt+S` ⚠ |
| Decrease track length | `Ctrl+[` (track row focused) |
| Increase track length | `Ctrl+]` (track row focused) |
| Reset track length to 16 | `Ctrl+Shift+L` ⚠ |
| Open sound editor | `E` or click **[⚙]** |
| Audition / preview | `Click` **[○]** |

### Gold Knobs (parameter control)

| Action | Key |
| :--- | :--- |
| Knob 1 (LR) | `Q` + `Wheel` |
| Knob 2 (UR) | `W` + `Wheel` |

### QWERTY Piano

| Action | Keys |
| :--- | :--- |
| Play notes C4–B4 | `Z S X D C V G B H N J M` |
| Octave down | ⚠ `Ctrl+Z` planned |
| Octave up | ⚠ `Ctrl+X` planned |

---

## MISSING UI — IMPLEMENTATION PRIORITY LIST

The following features are **fully wired in the engine and BridgeContract** but have no
Swing UI control yet. Listed roughly in priority order:

| Priority | Feature | What needs building |
| :--- | :--- | :--- |
| 🔴 High | LFO section in SynthConfigDialog | 4 LFO slots (shape/rate/depth/target/scope) |
| 🔴 High | Kit ADSR in KitConfigDialog | 4 sliders: A/D/S/R |
| 🔴 High | Mute group picker in KitConfigDialog | Dropdown: None / Group 1-4 |
| 🔴 High | Reverse toggle in KitConfigDialog | Checkbox |
| 🟡 Medium | Track length badge + right-click Set Length | Row header UI element |
| 🟡 Medium | Step probability in StepEditorPopover | Slider already in engine |
| 🟡 Medium | Undo / Redo | Pattern snapshot stack |
| 🟡 Medium | Solo button on row header | `[S]` button, soloRow field exists |
| 🟡 Medium | Tap Tempo | `T` key listener |
| 🟠 Lower | Arranger clip drag & drop | AV01–AV12 |
| 🟠 Lower | Clone clip / preset | Right-click context menu |
| 🟠 Lower | Transpose clip | `Ctrl+Up/Down` |
| 🟠 Lower | Automation recording | R + parameter change |
| 🟠 Lower | MIDI learn | Learn mode + incoming CC |
| ⬜ Future | Audio clips / Looper | Full AC / LO sections |
| ⬜ Future | Multi-sampling | WF section |
| ⬜ Future | Waveform view | Loop point editor |

---

*Swing Edition Quick Reference v1.0 — April 27, 2026*
*Engine: DelugeEngineDSL v1.2 | BridgeContract TRACKS=64 | LFO_COUNT=4*
