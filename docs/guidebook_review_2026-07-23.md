# Guidebook Review — 2026-07-23

Three-part review of `src/main/resources/docs/DELUGE_GUIDEBOOK.md` (2204 lines, 29 sections):
writing style (is it scenario-driven?), content coverage against the UI source code, and
screenshot/visual usage compared to the official Synthstrom Deluge Guidebook 4.0 PDF (338 pages).

## 1. Style — mixed, not consistently scenario-driven

The ~15 embedded tutorials (A–Q) are genuinely goal-first walkthroughs and the book's best asset.
But most sections lead with a feature-inventory voice ("The workstation features a…") and bolt the
scenario on at the end. Several sections are pure implementation description.

Per-section classification (a = scenario-driven, b = mixed, c = reference-dump, ✓ = legitimately
reference):

| § | Style | § | Style | § | Style |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 Track View | b | 11 Audio Tracks | b | 21 Shortcuts Matrix | c ✓ |
| 2 Synths | b | 12 Wavetable Scan | b | 22 Macros | b |
| 3 Kits | b | 13 Looper | c | 23 Preset Designer | c |
| 4 Waveform Crop | b | 14 MIDI/SD | b | 24 FX Dashboard | b |
| 5 Loop Slicer | a | 15 Performance | b | 25 Arranger Mutes | b |
| 6 Patchbay | b | 16 MPE | c | 26 Track Inspector | c |
| 7 Song/Arranger | a | 17 Settings | c | 27 Dialogs | c ✓ |
| 8 Master FX | b | 18 HW Commands | c ✓ | 28 Menus | c ✓ |
| 9 Delugeator | b | 19 Community Ref | c ✓ | 29 Premium UI | c |
| 10 Panels/Shift | c | 20 Workflow Tips | a | | |

Top rewrite candidates (should be scenario-driven but aren't) — ALL addressed in the 2026-07-23
follow-up commits: §16, §13, §23 rewritten scenario-first; §14.8's developer calibration content
moved to [hardware_dsp_tap_calibration.md](hardware_dsp_tap_calibration.md); duplicate 10.3
headings and duplicate tutorial letters L/N fixed; §10 opens with the live-tweak scenario and its
panel list dropped the PNG-filename artifacts; §17.3 reframed job-first per feature; §29 intro
reframed around what the player sees while performing. Original findings kept below for the
record:

1. **§16 MPE** — pure concept; never says how to enable/route MPE. Open with the player's goal
   (per-finger bend/slide routed to filter cutoff) and give actual steps.
2. **§13 Looper** — state-machine description; rewrite around "layer a guitar loop hands-free".
3. **§23 Preset Designer** — tab inventory duplicating §2/§10; rebuild as one patch built tab-by-tab.
4. **§10 Panels** — pad-by-pad inventory; open with the live-tweak scenario (hold Shift…).
5. **§1.11/1.12** — pixel formulas and "System Architecture" prose; replace with the FOLD scenario.
6. **§14.6/14.8** — SysEx byte tables and mvn test-harness instructions belong in `docs/`, not the
   user guide.
7. **§17.3 / §29** — marketing feature dumps; reframe each feature as what the user can now do.

Developer-jargon leaks (violate the CLAUDE.md guidebook tone rules), by line: 200–204 (pixel
formulas, `padSz`), 236–239 ("Unified Pitch Resolver"), 526/1253 (`JSlider`/`JSpinner`), 835
(engine backend names), 941 (`overdubsShouldCloneAudioTrack`), 951/974/982/1383 ("Pure JTree SD
Card Explorer" as heading), 1013 (`JDialog`), 1114 (`PatchSource.ENVELOPE_1`), 1138–1156 (git
branch, test class names, mvn commands, `org.deluge.firmware2`), 1271 (`ProjectModel`), 1381–1382
(JViewport, Maven shade), 1655–1656 (`PreferencesManager`, JAR), 2012 (`ArrangerPlaybackScheduler`
in a user-facing diagram). Also: duplicate "10.3" headings (L755, L773); duplicate tutorial letters
(L at §11.1 and §15; N at §4 and §25).

## 2. Coverage vs source code — ~80–85%, with verified wrong claims

Big workflows are covered. Two problem clusters:

### Missing (verified in code, absent or barely mentioned in the manual — all documented 2026-07-23, same-day follow-up commit)

| Feature | Code evidence | Manual home |
| :--- | :--- | :--- |
| Export to Ableton Live Set… (portable project / WAV stems / standalone .als) | `SwingDelugeApp.java:1168-1205` | §14.7 |
| MIDI Follow "Enable Track Follow Modes" + "MIDI Pad Controller Mode" prefs | `PreferencesDialog.java`, `midi/MidiFollow.java` | §14 |
| Quantize / Humanize Row Notes (step right-click) | `StepPropertiesEditor.java:62-70` | §1.5 |
| Computer-keyboard notes Z S X D C V G B H N J M = C4–B4; Q = momentary stutter | `KeyboardShortcutManager.java:36-52,121-124` | §17 |
| Tab + Click cycles step velocity 100→75→50→25% | `SwingDelugeApp.java:254-260`, `SwingGridPanel.java:1689-1700` | §1.8 |
| Settings ➔ Monitor Audio Input | `SwingDelugeApp.java:1379-1394` | §11.1 |
| Acoustics Monitor (Ctrl+M) | `SwingDelugeApp.java:1486-1495` | §29 |
| File ➔ New Window (Ctrl+Shift+N) | `SwingDelugeApp.java:981-987` | §28 |
| Prefs: UI scale (QHD/FHD/Retina), hover tooltips, GPU scopes, advanced-style UI, slider-popup vs rotary SELECT, clock source INTERNAL/EXTERNAL | `PreferencesDialog.java:146-149` | §10.4/§17 |
| Track context menu: Solo Exclusive, Unsolo All, Set Row Probability/Velocity…, Randomize Track Steps…, Synth Dashboard…, Grid Color Theme | `GridContextMenuFactory.java:44-311` | §1.2/§21 |
| Step context menu: Quick Velocity/Probability/Gate submenus, Copy/Paste Step | `StepPropertiesEditor.java:94-192` | §1.8 |
| Alt+PageUp/PageDown cycles grid layouts | `SwingDelugeApp.java:300-314` | §1.11 |
| Help ➔ Operations Manual… (F1) | `SwingDelugeApp.java:1644-1650` | §28 |
| Piano Roll Alt+Drag = nudge microtiming | `SwingPianoRollDialog.java:135` | §1.9 |
| Track Inspector "Clone Clip Variant" | `TrackInspectorDialog.java:85` | §26 |

### Wrong (manual claims contradicting code — fixed 2026-07-23, same commit as this doc)

| Manual line (pre-fix) | Claim | Code reality |
| :--- | :--- | :--- |
| 1274 | Tab toggles CLIP/SONG/ARRANGEMENT | Tab is the held velocity-cycle modifier; views switch via top-bar CLIP VIEW / SONG buttons (SONG toggles Song↔Arranger) |
| 1542 | Q W = Gold Knobs | Q = momentary stutter; W unbound |
| 1697 | F2 = Song view | No F2 binding |
| 1711 | Alt+1…8 mute tracks | Not bound |
| 1685 | Ctrl+Shift+L reset clip length | Not bound |
| 1647 | Ctrl+Up/Down tempo | Up/Down adjust the active Shift-shortcut parameter only |
| 838 | Ctrl+, opens Preferences | No accelerator; Ctrl+Shift+M opens Preferences on the MIDI tab |
| 968 | Cmd+B = SD Explorer | Ctrl+E / File ➔ Show Explorer |
| 1119 | Ctrl+Shift+I Ableton import | No accelerator |
| 2110 | Tuning under Tools | It's under Settings |
| 2140 | Tools ➔ Audio Transcribe… | File ➔ Import Audio File… |
| 1605, 1652 | "Set Samples Directory…" | "Set SD Card Root..." |
| 769, 1338, 1418 | Clone Track | Doesn't exist; Duplicate Clip / Track Inspector "Clone Clip Variant" |
| 1439 | File ➔ Load Audio Track... | AUDIO track-add button; audio files via Import Audio File / drag-drop |
| 22 (TOC) | §2.7 "Chord Keyboard (CORK & CORL)" | No such section/feature; §2.7 is the Chord Progression Suite |
| 840, 1227 | Grid profiles only 8x16/16x16 | Four modes incl. 24x16 and 16x24 |
| 1573 | Menu order File/Edit/Tools/View/Settings/Macro/Help | Actual: File, Edit, Macro, View, Tools, Settings, Help |

Incidental **code** bug found during the audit: the 16x24 grid radio item is never added to the
View menu's `ButtonGroup` (`SwingDelugeApp.java` ~1677), so its radio state won't clear.

Not audited control-by-control: synth-editor panel contents (§23), SysEx matrix (§14.6), MPE (§16),
macro syntax (§22), Shift-overlay pad map (§10.1), OLED soft keys (§10.2), encoder behaviors
(§10.3), hid/ableton packages, undo-stack table (§21.A2).

## 3. Screenshots — 14 of 29 sections have zero images

Our 54 image refs cluster on dialogs/tabs/menus (36 in §10, §24, §26–28); the workflow chapters
are nearly bare: §7 Song/Arranger, §3 Kits, §13 Overdubbing, and most of §1's subsections
(1.2–1.7, 1.9–1.12) have none. Orphaned files in `images/`: `deluge_grid_automation_editor.png`,
`deluge_grid_automation_overview.png`, `deluge_grid_zoom.jpg` (re-link candidate for §1.4/§1.11).

The official Guidebook runs ~0.6–1 figure per page in concept chapters, concentrated on workflow
illustration: annotated pad-grid mockups with callouts, pad-color legends, behavior timelines
(play direction, swing offsets, velocity scale, triplets), signal-flow voice diagrams, and
control-callout drawings. Every chapter opens with a numbered workflow flowchart.

Prioritized screenshots to add (Swing app captures unless noted):

1. §7.1 — Song view with mute/launch column, one clip armed; caption the color states
2. §7.2 — Arranger timeline with clip instances + playhead
3. §1.2 — Kit clip grid with named rows, one row muted
4. §3.1 — Kit after Smart Auto-Mapper (kick/snare/hat rows, choke groups)
5. §1.7 — Note head pad with dim tail pads (length/extension)
6. §1.4 — Zoom two-state (re-link `deluge_grid_zoom.jpg` or recapture)
7. §1.6 — Triplet view 3-of-4 column layout
8. §8.1 — Bottom master strip (volume, swing, tempo, transport), annotated
9. §1.9 — Piano Roll with a multi-bar melody
10. §2.1 — Subtractive signal-flow diagram (mermaid): osc→noise→HPF→LPF→amp→FX with env/LFO taps
11. §25.1 — Arranger headers with one track muted, one soloed, live-capture indicator
12. §13.3 — Audio track overdub controls with recorded PCM layers
13. §1.10 — Grid after applying a Euclidean pattern (dialog already pictured in §27)
14. §17.2 — Re-reference existing `deluge_tuning.png` (currently only in §27)
15. §22.1 — Macro recorder while recording

Cross-cutting conventions worth adopting from the official book: an opening workflow flowchart
(mermaid) per major chapter, and a pad-color legend under every grid screenshot.

## 4. Screenshot quality audit (2026-07-23 follow-up) — scrollbars, clipping, accessibility

All 56 images were visually audited (three parallel reviewers, every file opened and inspected)
for scrollbars that betray a wrong default size, clipped/inaccessible controls, and readability.

**Verdict on scrollbars:** none of the captures shows a "bad" scrollbar (a dialog whose controls
are cut off behind one). The only scrollers visible are inherent ones — the grid's row scroller,
the piano roll's pitch axis, the 32-item algorithm list. The failures found were the opposite
kind: content clipped *without* a scrollbar, truncated labels, and wrong captures entirely.

### Fixed same day (code + regenerated captures)

| Problem | Root cause | Fix |
| :--- | :--- | :--- |
| `deluge_grid_automation_{overview,editor}.png` and `deluge_main_grid_shift.png` byte-identical to `deluge_main_sequencer.png` (4 copies of one Song-view shot) | Pipeline switched to card "AUTOMATION" but the card is registered "AUTO" (silent no-op); shift overlay reads `SwingHardwareTopPanel.isShiftActive()`, not the grid flag; `main_sequencer` never forced CLIP view | Generator fixed on all three counts; all four images now distinct and correct |
| OVERVIEW/EDITOR toggle in the automation workspace did nothing (screenshot symptom of an app bug) | The overview/editor flag isn't part of `refresh()`'s structureChanged fingerprint, so toggling took the `refreshInPlace()` path | `setAutoOverviewMode` now forces a full rebuild; the panel's toggle routes through it |
| `deluge_synth_tab_algorithm.png`: every routing string read "OP1 ? OP2 ? …" | Panel read `Dx7EngineLookupTables.ALGORITHMS` — a 2048-zero array no code ever fills — and its "FB=7-algo" formula was invented | `formatAlgorithmMini` now reads the engine's real `FmCore.ALGORITHMS` (index i = operator 6−i) and renders carrier/modulator roles + the actual feedback operator |
| `deluge_recording_cleaner.png`: bottom buttons truncated/overlapping | Dialog fixed at 850×580, too narrow for its action bar | 1100×620 with a minimum size; action bar renders complete |
| "MASTER EFFECTS CONSOLI" title on all four FX-console shots | Emoji-prefixed label under-measures, clipping the final glyph | Trailing padding on the title label |

### Cosmetics — second pass (fixed 2026-07-23, follow-up commit)

- Modulation-matrix column headers now draw diagonally (were an unreadable run-on at 22px
  columns); header band raised to fit.
- Kit dialog's left column (sample/waveform/UTILITY & GROUPS) wrapped in a scroll pane — it
  previously clipped its bottom with no scrollbar.
- Automation tab header switched to BorderLayout so a long clip name can't run under the
  "Clear All Automation" button.
- BarAutomationDialog given a real preferred size + padding (pack() collapsed it to a 215×135
  fragment in captures).
- Sidebar explorer's selected tab now gets dark text on the light selected pill (was unreadable).
- Emoji-title clipping ("KEYZONE ROLL"→"ROLI", wavetable "NONE"→"NONI") fixed with trailing
  label padding; performance-view SIDECHAIN column relabeled SDCHN to fit; Euclidean Apply
  button made opaque (some look-and-feels painted white-on-white); algorithm list now opens
  scrolled to the top.

### Still open (low value)

- Wavetable editor "Save wavetable to SD..." button label can ellipsize at narrow widths.
- FM ratio unit label in the OSC/FILTER/FM tab can truncate ("×0…").
- MIDI-settings channel spinners are very narrow; drone-lab Style dropdown is low-contrast.
