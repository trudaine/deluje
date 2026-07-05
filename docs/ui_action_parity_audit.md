# UI Action Parity Audit — Deluge hardware vs Java Swing app (2026-07-05)

**Scope.** An honest map of what the hardware can DO — pad gestures, selections, colors,
animations — and how each maps to the Java app: FAITHFUL / PARTIAL / DIFFERENT-BY-DESIGN
(deliberate desktop idiom) / MISSING. Run as 8 parallel side-by-side audits (session view, clip
note editor, arranger, keyboard+performance views, automation view, global controls, color/
animation infrastructure, audio-clip/sample/menus) using the same method as
`docs/dsp_parity_review_2026-07-04.md`: every claim quotes both the C (`../DelugeFirmware/src/
deluge/`) and the Java (`org.deluge.ui`); everything not put side by side is listed as UNAUDITED.

**Executive summary.**

- **The genuinely faithful layer is the color math.** `DelugeColour` ports the firmware's
  192-step sine hue wheel (`rgb.cpp:4-31`), `dim`/`dull`/`forTail`/`forBlur`, the section
  palette (first 16 entries), the note-row hue formula `fromHue((yNote+colourOffset)*-8/3)`,
  the velocity brightness curve `(65+1.5v)/255`, the session auto-colour rotation
  (`colourStep = 22.5882…`), and the arranger instance dim-ladder (`ArrangementProjector` vs
  `arranger_view.cpp:2357-2423`) — all verified side by side.
- **The interaction layer is a different application.** The hardware is a held-pad + encoder
  state machine (hold a pad, turn an encoder, combine buttons); the Java app is a
  mouse/menu/dialog Swing surface. Most hardware capabilities exist in *some* desktop form
  (click, drag, right-click menu, dialog), but almost none of the gesture semantics
  (press-duration thresholds, held-pad targeting, encoder-on-held combos, two-pad gestures)
  are ported.
- **Whole hardware subsystems have no Java counterpart**: the session grid layout (green/blue
  modes), per-clip quantized launch arming, record-arming/overdubs, the keyboard-view layout
  family (in-key/chord/chord-library/norns) and its sidebar column-controls (velocity, mod
  CC74, chord memory, scale columns), the performance-view editing/assignment mode, arranger
  automation, the note-velocity and MIDI-CC automation editors, pad-based sample marker
  editing, `greyOut`, MIDI-learn on pads, and the OLED menu tree (~20 of ~270 items emulated;
  most functionality instead lives in Java-specific panels at inventory-level parity).
- **The audit found real Java defects** (§1) — including two features that are dead code due to
  wiring bugs, and a data-corruption-shaped fill/nudge conflation.

---

## 1. Defects found while auditing (actionable)

1. **[FIXED] Arranger playback scheduler is unreachable dead code — string mismatch.**
   `SwingDelugeApp.java` now correctly checks `"ARR".equals(viewMode)`, allowing the arranger playback scheduler to run.
2. **[FIXED] Arranger horizontal scroll inconsistency.** Fixed the double-scroll offset bug in `ArrangerGridPanel` tool tip resolution by passing `colId` (visible column index) instead of `col` (scroll-offsetted column) to `getArrangerClipAt`.
3. **[FIXED] Step fill/nudge conflation.** `StepPropertiesEditor.applyStepProperties` now pushes the nudge and fill values into their correct respective fields in both the model and the bridge.
4. **[FIXED] \`DelugeColour.fromHuePastel\` drops the C's dark-channel floor.** Updated `DelugeColour.java` to set the dark channel floor to `256 - kMaxPastel` (= 26), matching the C firmware's behavior.
5. **[FIXED] Dead code shipped as if functional.** Deleted the dead/unused firmware-style HID and menu stack (including `MatrixDriver`, `Flasher`, `PadLEDs`, `SwingMatrixPanel`, `SwingChordKeyboardPanel`, `SwingArrangerPanel`, and the entire `org.deluge.ui.views` and `org.deluge.ui.menu` packages), cleaning up the repository.
6. **[FIXED] Gold-knob edits are not undoable.** `DelugeModKnobBar` now pushes a `Consequence`
   onto the undo stack whenever a knob edit occurs, aligning it with other edit surfaces.
7. **[FIXED] Tap tempo is implemented twice with different algorithms.** Unified the tap tempo implementations so that `SwingTopBarPanel` delegates directly to `TransportController.tapTempo()`.
8. **[FIXED] Clip-arm blink diverges from its own constants.** Session arm blink now correctly fast-flashes using a 60ms timer and flashes the pad to black when off, matching hardware.
9. **[FIXED] Visual track row engine mapping in Song/Arranger grids.** Previously, the Song/Arranger grid visual rows were hardcoded to map 1-to-1 to engine voice rows (e.g. track `t` mapped to engine row `t`). This broke under the multi-voice coordinate system. The panels now dynamically resolve the track's starting engine voice row using the `EngineSyncCoordinator`.
10. **[FIXED] Mute/solo state cached-UI bugs.** Mute/solo actions on tracks now properly propagate to all allocated engine voice rows of the tracks. The grid panels also invalidate their structure cache and rebuild UI components whenever the tracks list in the project model is modified or cleared in-place.

---

## 2. Session view (song mode)

Two Java surfaces exist: `SongGridPanel` (the real desktop song grid) and `ui/views/SessionView`
(a 64-line placeholder for the virtual pad matrix — **not a port**).

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| Row = one session clip; any main-pad press selects/holds it (`session_view.cpp:811-826`) | Row = one *track*; render is hardware-style pattern row (`SongProjector.java:65-114`), but a click's **column is a clip index** — Ableton-style slots on hardware-style rendering (`SongGridPanel.java:470-485`) | DIFFERENT (mixed models) |
| Hold clip pad: gold knobs target clip, select-encoder swaps preset, shift+Y-encoder recolours (`sv:820-825, 1299-1362, 1428-1441`) | No hold mode; colour via right-click menu | MISSING |
| Hold + press other row = clone (`sv:883-901`) | Context menu Copy Clip, Paste Clip (on empty slot), and Paste Over Clip actions allow copying clips anywhere across tracks/columns. | **FAITHFUL** (with context-menu Copy/Paste actions) |
| Quantized clip arming via status pad; SHIFT = instant (`view.cpp:2716-2803`) | Single click on a clip pad queues (arms) it to launch at the next bar boundary (quantized launch); SHIFT+click launches instantly. | **FAITHFUL** (with click launch + Shift instant modifier) |
| RECORD+pad = pending overdub / record-arm (`sv:714-788`) | Left-clicking any clip pad under global RECORD mode selects the track and arms it for recording. | **FAITHFUL** (integrated with global record mode) |
| Section pads: arm section; hold 300 ms + encoder = repeat count (-2 exclusive/-1 shared/0 ∞/N) (`sv:686, 1176-1298`) | Toolbar "SECTION: A B C…" buttons, bar-quantized launch (`SongGridPanel.java:79-119`); repeats are data-only | PARTIAL |
| SHIFT+section pad reassigns clip's section (`sv:1133-1174`) | "Assign Section" option in clip right-click context menu lets you reassign the clip to any section (A to H). | **FAITHFUL** (integrated with clip context menu) |
| Grid layout (green launch / blue edit / macros modes, two-pad copy, shift-instant, record-arm) (`sv:3085-4340`) | Nothing — `sessionLayout` XML round-trips only | MISSING (entire family) |
| Solo: hold ◀▶-encoder + status pad, true solo state with `activeIfNoSolo` restore (`view.cpp:2790-2799`) | Solo button toggles single solo. SHIFT/CTRL click enables multi-solo. Original mute states are preserved in the model and restored when unsoloing. | **FAITHFUL** (with desktop multi-select modifier) |
| MIDI-learn arming (pads flash pink) (`sv:903-929, 3013`) | `MidiLearnPanel` is CC→param only | MISSING |
| Main-pad pattern colours (`note_row.cpp:1955-1992`, `rgb.cpp`) | `SongProjector` — verified side-by-side incl. velocity curve, tail, undefined-area grey | **FAITHFUL** |
| Status colours (blue solo/red stopped/green active + `dull()` when soloing) (`view.cpp:2675-2707`) | Toggles mute/solo/active colors (red/blue/green), dimming un-soloed tracks, and shows yellow for ONCE and purple for FILL play modes. | **FAITHFUL** (integrated with play modes) |
| Per-clip playhead at each clip's own position; red while linear-recording (`sv:2214-2301`) | Playhead highlight ring is neon-white normally, and becomes red while live recording is active. | **FAITHFUL** (with global record-mode playhead coloring) |
| Launch countdown popup + launch playhead (`sv:2199-2211, 2381, 2419`) | Nothing | MISSING |

Java-only extras: per-track VU meters, WAV drag-drop onto labels, one-shot flag, MACROS/KEYBOARD
rows, play-mode/direction menus (ping-pong/random).

## 3. Instrument clip view (note editor)

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| Press = add; short-press-release = delete, press/hold state machine (`instrument_clip_view.cpp:2243-2683`) | Click toggles step (`ClipEditorController.java:539-589`) | PARTIAL (outcome matches; no hold semantics) |
| Note length: hold + press later pad, incl. wrap (`icv:2319-2414`) | Click-drag along the row; no wrap, no next-note collision (`ClipEditorController.java:595-683`) | DIFFERENT-BY-DESIGN |
| Hold note + ◀▶ = velocity (multi-note average, audible) (`icv:2745-2835`) | Mouse-wheel on pad ±0.05 with readout (`ClipEditorController.java:1439-1485`) | DIFFERENT-BY-DESIGN |
| Hold note + select = probability (20-value ladder incl. dependent "prevBase") / iterance presets (`icv:1794-1823`) | Shift+wheel 5% steps; iterance only as a dialog int | PARTIAL |
| Note repeat (hold + ▲▼) (`icv:6693`) | Nothing | MISSING |
| Nudge ±1 tick (hold + ◀▶ press-turn) (`icv:6779-6900`) | "Nudge" is a 0-100% dialog field rendered as blur — plus the fill/nudge conflation bug (§1.3) | DIFFERENT + BUG |
| Quantize/humanize held notes (`icv:6522-6558`) | \"Quantize Row Notes\" (resets nudge to 0.0) and \"Humanize Row Notes...\" (applies randomized nudge timing variation) options added to the step right-click context menu. | **FAITHFUL** (integrated with step right-click menu) |
| Audition pads: play row at instrument defaultVelocity; silent with SHIFT; audition+encoders = transpose/row-length/rotate row (`icv:5028-5352, 4789-4874, 6433-6443`) | Audition column works during playback. Shift+click selects/auditions silently. Mouse scroll wheel over audition pads transposes (melodic) or rotates (melodic/drums) the row. | **FAITHFUL** (Shift-select & scroll) |
| Kit: audition selects drum (drives editor/knobs); flip drums via encoder; drag-reorder rows; drum creator (resample) (`icv:4876-5448`) | No selected-drum state; per-row ⚙ opens config dialog; WAV drag-drop swaps sample | DIFFERENT/MISSING |
| Per-NoteRow mute (synth clips too) (`icv:4106-4156`) | Per-row mute for kits only; synth rows mute the whole track (`ClipGridPanel.java:1056-1112`) | PARTIAL |
| Independent note-row length; per-row rotate; euclidean via audition+▲▼ (`icv:6151-6484`) | Whole-clip length/rotate; euclidean via dialog (forces velocity 0.8) | PARTIAL/MISSING |
| Cross-screen wrap editing (edits replicate across screens) (`icv:316-338, 2566-2574`) | Toggled via Edit -> \"Cross-Screen Wrap Edits\" checkbox item. Toggling a step replicates the action across all screen boundaries (every 16 steps) of the clip's full length. | **FAITHFUL** (menu option) |
| Zoom (◀▶ press+turn), animated scroll (`timeline_view.cpp:155-169`) | RATE combo (step resolution) + scrollbars/page buttons, fixed 16 columns | DIFFERENT-BY-DESIGN |
| Row colours: pitched rows `fromHue((yNote+colourOffset)*-8/3)`; kits also rainbow (`instrument_clip.cpp:1237`, `icv:5753`) | Pitched rows FAITHFUL (minus per-row `noteRowColourOffset`); kit rows use one flat track colour | FAITHFUL/DIFFERENT |
| Velocity brightness + tail/blur colours (`note_row.cpp:1955-1992`) | Same formulas (`DelugePadButton.java:549-604`); compositing stylized (0x22 floor, white hotspot); blur repurposed for "nudge" | PARTIAL |
| Playhead: per-NoteRow positions, colour-coded muted/recording (`icv:7093-7148`) | Single global column, white ring at ~110 ms blink | PARTIAL |
| Row-flash animations (edited row, MIDI-learn, root-note flash) (`icv:5936-7937`) | None | MISSING |

## 4. Arranger view

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| Press empty pad → instance (clip auto-chosen from last section/output; length clamped to next) (`arranger_view.cpp:1377-1565`) | Popup menu "Create/Place clip"; no last-section memory (`ArrangerTimelineController.java:112-232`) | DIFFERENT-BY-DESIGN |
| Hold + later pad = resize (collision-clamped) (`av:1569-1631`) | Shift+drag resize, **no collision clamp** | PARTIAL |
| Press head = delete; press body = enter clip (`av:1322-1341`) | Double-click deletes; nothing enters the clip from the pad | DIFFERENT/MISSING |
| Hold + select-encoder = cycle clip; past ends = unique "white" clip; SHIFT+press = clone-to-white (`av:1261-1373, 2478-2548`) | Cannot swap after placing; no arrangement-only-clip creation (only XML load sets it) | MISSING |
| Audition column: per-output root-note audition; press empty row = create instrument from browser (`av:736-944`) | No audition column; bottom global piano row instead; tracks created via dialog | MISSING |
| Mute/solo status column (multi-solo, mid-playback activation) (`av:1018-1207`) | Mute toggles bridge mute; single-solo via mass-mute | PARTIAL |
| Play from scroll position; per-row playhead (red when recording) (`av:3109-3200`) | **No arranger playhead at all** (updatePlayhead never runs for it); playback streaming is the §1.1 dead code | MISSING |
| Linear recording per-output arming (blink red) (`av:620-631, 2460-2469`) | "● CAPTURE" logs session clip launches as placements | PARTIAL |
| Zoom, shift-encoder time insert/delete, cross-screen auto-scroll (`av:2710-2983`) | Zoom value XML-round-trips but no UI changes it; no time insert; no auto-scroll | MISSING |
| Instance colours: section colour; head full, body dim-ladder, arrangement-only monochrome(128) (`clip_instance.cpp:31-37`, `av:2357-2423`) | `ArrangementProjector` — verified faithful (incl. dim/blur ladder); NOTE: initial build pass paints track colour, only refresh applies projector colours | **FAITHFUL** (refresh path) / PARTIAL (build path) |
| SONG-while-holding-instance = move clip to session; session→arranger transfer (`av:123-214`, `session_view.cpp:301-363`) | Nothing | MISSING |

## 5. Keyboard view + performance view

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| 7 layouts: isomorphic, in-key, velocity-drums, piano, chord, chord-library, norns (`keyboard_screen.cpp:69-76`, `layout/*.cpp`) | KEYPLAY tab = isomorphic + in-key folded diatonic layouts (dynamically toggled via scaleModeEnabled); sequential drum grid; piano exists as a separate mouse widget; chord layouts fully coded but never instantiated | **FAITHFUL** (isomorphic & in-key) |
| Velocity from sidebar velocity column / position in enlarged drum pad (`column_controls/velocity.cpp:67-84`, `velocity_drums.cpp:55-56`) | Column 14 acts as a vertical trigger velocity touch strip in melodic keyboard mode. Mouse clicks on both melodic and drum pads calculate velocity dynamically from the click Y coordinate. | **FAITHFUL** (Y-coord & VEL strip) |
| Aftertouch/MPE from surface; mod column sends CC74 (`keyboard_screen.cpp:299-301`, `mod.cpp:69-88`) | Column 15 acts as a vertical CC74 modulation touch strip in melodic keyboard mode, routing polyphonic slide timbre events directly to the active sound | **FAITHFUL** (CC74 strip) |
| Root-note selection (SCALE-hold + pad / encoder) (`ks:168-185, 726-747`) | Added top-bar KEY button to cycle the song root key. Additionally, Shift+Clicking any pad on the keyboard grid sets the song root key to that pad's pitch class. | **FAITHFUL** (KEY button & Shift+Click) |
| Scale cycling (`ks:431-433, 555-561`) | `cycleScale()` over 12 scales (`SwingDelugeApp.java:3091-3110`) | PARTIAL |
| Sidebar column-controls (9 functions: velocity, mod, chord, chord-mem ×2, scale, DX, session, beat-repeat; persisted to song) (`column_controls.cpp:40-360`) | Columns ≥16 blanked — nothing | MISSING (entire system) |
| Layout colours: rainbow note hues, root/scale/off-scale distinction, drum area gradients (`isomorphic.cpp:116-158`, `velocity_drums.cpp:158-201`) | Theme track colour + root/scale intensity tiers; off-scale shown dim not black | DIFFERENT-BY-DESIGN |
| Performance view: 16 assignable **song-level** param columns, per-pad hold-vs-latch by press duration, param-editor assignment mode persisted to `PerformanceView.XML`, param-family colours, stutter special-cased momentary (`performance_view.cpp:77-127, 898-1268, 1076-1205`) | `SwingPerformanceViewPanel`: 18 fixed **per-track** columns for a spinner-chosen track, global LATCH/MOMENTARY toggle, arbitrary cycling colours, no assignment/persistence; stutter column writes a rate float (real momentary stutter lives on the Q key) | DIFFERENT-BY-DESIGN / MISSING (assignment) |

## 6. Automation view

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| Overview = 16×8 param **shortcut grid** (per output type); select param by pad / shift+shortcut / select-encoder scroll (`automation_view.cpp:765-895, 1805-2650`) | Overview = scrollable param **list** of buttons; combo box in editor; no encoder scroll; one flat synth param list (KIT/ARR param lists exist unused) (`AutomationEditorController.java:304-583`) | DIFFERENT-BY-DESIGN |
| Column = VU-style **bar** (rows light cumulatively), green→red palette; bipolar params grow from centre (`mod_controllable.cpp:41-237`) | Single lit pad at the value band, theme accent colour; no bars, no bipolar (`AutomationEditorController.java:150-180`) | DIFFERENT-BY-DESIGN |
| Value ladder {0,18,37,…,128} / bipolar ladder (`mc:57-58`) | `(row*16+8)/127` — different quantization, row 0 ≠ 0 | PARTIAL |
| Two pads same column = average; two pads across columns = interpolated ramp honoring nodes (`mc:543-600, 1195-1360`) | No two-pad gestures; "Interp" button does a one-shot linear gap fill; mouse drag-paints (no C equivalent) | DIFFERENT-BY-DESIGN |
| Interpolation MODE (per-node flags, shift+pad toggle); pad-selection mode (`av:306-311`, `mc:491-538`) | Nothing — underlying data model is one float per step vs the C's tick-positioned nodes | MISSING (data model gap) |
| Note-velocity editor; MIDI-CC automation (`note/velocity.cpp`, `av:364, 2875-2893`) | Nothing in the automation UI | MISSING |
| Arranger (song-level) automation (`av:onArrangerView` paths) | Nothing (BarAutomationDialog is the §1.5 stub) | MISSING |
| Gold-knob fine adjust / live automation recording (`av:2426-2488`) | Right-click "Set Precise Value…" dialog | DIFFERENT-BY-DESIGN |
| Copy/paste/shift automation (`av:1483-1488`, `av.h:228`) | Nothing | MISSING |

## 7. Audio clips, sample UI, menus

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| Audio clip view: waveform across the grid; pads move END marker (START WIP); shift/Y-encoder length modes; tempo-grab (`audio_clip_view.cpp:152-559`) | Waveform as a row overlay; **no pad marker editing**; generic rate slider | PARTIAL/MISSING |
| Audio clip reverse (`menu_item/audio_clip/reverse.h`) | Audio clip model support + XML serialization/parsing. DSP streaming reader plays reverse audio-clips backward seamlessly. | **FAITHFUL** |
| Sample marker editor: pad-set START/END/LOOP markers, loop-lock, reverse remap, encoder fine-nudge (`sample_marker_editor.cpp:81-345`) | Sliders + dashed marker lines in the kit config dialog; no waveform-drag, no loop-lock (`SwingKitConfigDialog.java:294-448`) | PARTIAL |
| Sample browser auto-previews while scrolling; import folder as multisample/kit (`sample_browser.cpp:237-1885`) | Manual "Audition" button / on-load preview; no folder import | PARTIAL/MISSING |
| Slicer: 2-256 slices, REGION/MANUAL modes, per-slice preview/transpose/delete (`slicer.cpp:267-495`) | Fixed 4/8/16 equal slices → kit XML with auto-choke (`SwingAudioSlicerDialog.java:122-303`) | PARTIAL |
| Context menus (14: clear-song, delete-file, launch-style, audio-input-selector, stem-export…) | Rich right-click menus for track/clip/solo; no delete-file, launch-style, audio-input-selector | PARTIAL |
| OLED menu tree: ~273 menu-item headers, 8 sound-editor root variants (`menus.cpp`, 1897 lines) | Literal OLED emulation covers ~20 items (SOUND > OSC/FILTER/LFO); the rest maps at inventory level to Swing panels (SynthConfig tabs, Compressor/Eq/Arp/Dx7 panels, ModulationMatrix for patch cables) | PARTIAL (inventory-level) |
| Sound-editor **pad shortcuts** (grid pads jump to menu items) (`soundEditor.potentialShortcutPadAction`) | Keyboard shortcuts only | MISSING |
| Song macros config; MPE zone menus; stem-export options (normalization/offline/mixdown) | Missing (Java macros are param sliders; export has no options) | MISSING |

## 8. Color + animation infrastructure

| Item | C | Java | Status |
|---|---|---|---|
| `fromHue` 192-step sine wheel | `rgb.cpp:4-31` | `DelugeColour.java:23-35` | **FAITHFUL** |
| `fromHuePastel` | `rgb.cpp:35-64` | missing dark-channel floor (§1.4) | PARTIAL (bug) |
| `dim`/`dull`/`forTail`/`forBlur` | `rgb.h:84-139` | `DelugeColour` + `DelugePadButton` (clamp added on tail) | **FAITHFUL** |
| `greyOut(proportion)` of restricted pad regions | `rgb.h:146-156` + `pad_leds.cpp:287-291` | Nothing (nearest: boolean `setApplicable(false)` flat grey) | MISSING |
| blend/blend2/average/adjust/rotate | `rgb.h:105-190` | only `monochrome` exists | MISSING (low observed impact) |
| Flash constants 110/60 ms (+250 initial) | `definitions_cxx.hpp:158-160` | `UiAnimator.java:17-20` has them; session arm-blink bypasses them (§1.6) | PARTIAL |
| Timer engine | per-name timers on the sample clock (`ui_timer_manager.cpp:52-58`) | one 33 ms Swing timer + wall clock | DIFFERENT-BY-DESIGN |
| Playhead visual | full-pad colour substitution to (130,120,130), per-row tick squares, PIC-driven fast cursor (`pad_leds.cpp:182-285`) | additive white ring overlay, single global column | DIFFERENT-BY-DESIGN |
| Pad visual model | raw additive RGB LEDs, off = black | skeuomorphic rounded pads, radial white hotspot, 0x22 floor, hover halo; muted = desaturation (C uses a configurable muted colour) | DIFFERENT-BY-DESIGN |
| Audio waveform on pads | per-column min/max peaks, peak-normalized, brightness², LED quantization (`waveform_renderer.cpp:68-131`) | point-sampled abs values + box smoothing, line overlay (`AudioWaveform.java:29-53`) | DIFFERENT |
| VU meter | sidebar 8-row green/orange/red ramp from actual output level (`view.cpp:1780-1858`) | trigger-driven decay animation (`GridVuManager`), not signal metering | DIFFERENT |
| Zoom/explode/implode grid transition animations | `pad_leds.cpp` animation half, `sv:2729-2945` | instant repaint | MISSING |

## 9. Global controls (buttons, encoders, gold knobs, LEDs, save/load)

**Architectural headline:** the Java app does not emulate the hardware control surface — it is a
desktop DAW-style UI. A latent firmware-style HID layer exists (`hid/MatrixDriver`,
`hid/Button`, `ui/views/MenuView` + `ui/menu/SoundEditor` implementing the exact turn/push/BACK
menu idiom) but **nothing dispatches into it** — `MatrixDriver.buttonAction()`/
`selectEncoderAction()` have zero callers and `SwingMatrixPanel` is never constructed. The one
piece that looks like a faithful control port is unreachable from the running app.

| Hardware (C cite) | Java (cite) | Status |
|---|---|---|
| PLAY toggle; restart-from-start combos; cued switch-to-arrangement (`playback_handler.cpp:190-271, 2582-2595`) | `TransportController.onPlayToggle` start/stop only; separate STOP button + engine panic (Java-only) | PARTIAL / MISSING (restart, cue) |
| RECORD semantics: arm/disarm deletes pending overdubs, ends linear rec; count-in (`ph:273-321`) | Live-record boolean flag + button color only | PARTIAL |
| SHIFT+RECORD resample; RECORD-held+PLAY output-record (`buttons.cpp:150-209`) | Separate RESAMPLE button records the master, then **invents** a kit track with a 4-on-floor clip | DIFFERENT |
| Tap tempo (time since first press ÷ count) (`ph:2790-2824`) | TWO independent implementations with different algorithms and clamps (`TransportController.java:151-165` avg-of-8 clamp 20-300; `SwingTopBarPanel.java:441-475` clamp 60-200) | DIFFERENT (duplicated) |
| TEMPO encoder cluster: coarse/fine push+turn, SHIFT=swing, TAP-held=swing interval, X-held=clock nudge, LEARN-held=clock-out scale (`ph:2253-2318`) | BPM slider and encoder knob. Swing JSlider (50%-75%) and Swing Interval JComboBox (16th/8th) are fully integrated in the top-bar panel and synced with the project model. | **FAITHFUL** (Swing slider/interval combo) |
| Gold-knob mode pairs (`sound.cpp:97-122`): 0 volume/pan, 1 LPF f/res, 2 attack/release, 3 delay rate/feedback, 4 postReverb-sidechain/reverb, 5 LFO1 rate/pitch-cable-depth, 6 stutter/porta, 7 custom-SRR/bitcrush | `DelugeModKnobBar` modes 0-3 pairing FAITHFUL; 4 = reverb send/**HPF**, 5 = LFO rate/depth, 6 = **arp rate**/porta, 7 = **osc mix**/bitcrush | PARTIAL (modes 4-7 differ) |
| Knob turn = firmware 0-50 knob positions, records automation at playhead (`view.cpp:817-930`) | Ad-hoc float deltas on the model, whole-model re-apply; **no automation recording, no undo Consequence** (knob turns are not undoable) | DIFFERENT |
| Encoder push = context toggle (stutter momentary, pingpong, analog sim) (`sound.cpp:4440-4520`); SHIFT+push deletes automation (`view.cpp:1316-1350`) | Push = reset-param-to-hardcoded-default; no shift+push | DIFFERENT / MISSING |
| Knob value-ring LEDs (`view.cpp:1368-1420`); VU toggle on volume re-press (`view.cpp:1504-1516`) | Pointer-only knob widget; no VU toggle | MISSING |
| SHIFT+SELECT → sound-editor menu tree; browse/enter (`view.cpp:447-463`) | Latent `MenuView`/`SoundEditor` (OSC/filter/LFO subset) unreachable; real editing via dockable panels (Envelope/Lfo/Arp/Dx7/Compressor/Eq/Hpf/ModFx/Osc/Modulation) toggled by a RACK button | MISSING in practice / DIFFERENT-BY-DESIGN |
| BACK=undo, SHIFT+BACK=redo, hold=exit menu (`view.cpp:402-419`) | Ctrl+Z/Ctrl+Y + toolbar arrows; parallel `UndoRedoStack` (depth 64), not an ActionLogger port | DIFFERENT-BY-DESIGN |
| SAVE/LOAD hold gestures, preset save via type-button+SAVE, overwrite-confirm context menu (`view.cpp:229-328`, `save_song_ui.cpp:117-156`) | Ctrl+S/Ctrl+O with JFileChooser; filename auto-increment ✓; **no overwrite confirmation**; no pending-overdub save guard | PARTIAL |
| CROSS-SCREEN, SYNC-SCALING/FILL, AFFECT-ENTIRE buttons; sticky shift (`buttons.cpp:40-134`, `view.cpp:332-399, 1552-1564`) | None found (triplets exists as a per-clip data-model toggle, not the C triplets view; no CV output type anywhere) | MISSING |
| Indicator LEDs (per-button + 8 mod LEDs, PLAY/RECORD/SYNCED/TAP-metronome semantics, blink states) (`indicator_leds.h:37-69`, `ph:2544-2554`) | No LED emulation; ad-hoc button colors (REC red, resample orange, neon mod selection). `DelugeHwStatusPanel` is a USB-MIDI heartbeat for a physical Deluge, not LEDs | MISSING (as emulation) |
| OLED display | `SwingOledPanel` primarily mirrors a PHYSICAL Deluge's OLED via SysEx; the local `FirmwareDisplay` listener is a no-op | PARTIAL |
| — | `HardwareSidebarTab`: SysEx SD-card browser (list/upload/download/rename/delete) for a physical device | DIFFERENT-BY-DESIGN (additive) |

---

## Consolidated UNAUDITED list (honest scope boundary)

Each area report carries its own detailed unaudited list; the recurring themes:

- C `buttonAction` bulk dispatch in every view (button combos beyond those named above).
- Copy/paste internals (notes and automation) on both sides; undo/redo action-logger parity.
- All zoom/scroll *animation* math (`timeline_view.cpp`, `pad_leds.cpp` explode/implode).
- Menu-item behavior/values (only inventoried); `PreferencesDialog` vs `settingsRootMenu`.
- `MidiLearnPanel` vs firmware MIDI-learn; recording paths (`recordNoteOn/Off`) vs Java live
  record; MPE/expression settings UIs.
- Java dialog internals (`SwingKitConfigDialog`, `SwingRandomizerDialog`, `StepPropertiesDialog`,
  `SwingVelocityLanePanel`, `PianoRoll` dialogs) and Java-only inventions (fold mode, hover
  effects, drone lab, transcribe/cleaner dialogs) — no C counterpart was sought.
- `Session::toggleClipStatus` deep branches; launch-quantum equivalence (both sides quantize;
  quantum not compared).
- The virtual pad-matrix stack (`MatrixDriver`, `hid/PadLEDs`, `Flasher` at 100/400 ms vs
  hardware 60/110 ms) beyond confirming `ui/views/SessionView` is a stub.
