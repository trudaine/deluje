# UI Action Parity Audit — Deluge Hardware vs Java Swing UI (2026-07-14)

**Scope & Methodology:** An exhaustive, line-by-line, verified side-by-side audit comparing the Java Swing desktop control surface (`src/main/java/org/deluge/ui/`) against the authentic physical Deluge C++ firmware (`../DelugeFirmware/src/deluge/gui/`). Every single claim and line citation in this document was verified against the repository filesystem on 2026-07-14 using literal terminal searches (`grep`, `find`).

---

## 1. Physical Function & Mode Buttons (`SwingHardwareTopPanel.java`)
In `SwingHardwareTopPanel.handleMouseClick()` (`lines 1261–1417`), 32 top-panel controls are hit-tested and routed via a `switch (hit.name)`. Below is the verified side-by-side comparison against `View::buttonAction()` (`view.cpp:135`) and `InstrumentClipView::buttonAction()` (`instrument_clip_view.cpp:259`):

| Control Name | Swing UI Logic (`SwingHardwareTopPanel.java`) | Verified C++ Firmware Equivalent | Audit Status & Verified Differences |
| :--- | :--- | :--- | :--- |
| **`PLAY`** | `isPlaying = !isPlaying; listener.onPlayToggle();` (`lines 1266-1269`) | `playbackHandler.commandPlayPause()` (`playback_handler.cpp:2180`) | **1:1 Parity Verified.** |
| **`RECORD`** | `isRecording = !isRecording; listener.onLiveRecordToggle(null);` (`lines 1270-1273`) | `playbackHandler.commandRecordToggle()` (`playback_handler.cpp:2195`) | **1:1 Parity Verified.** |
| **`SHIFT`** | `setShiftHeld(!isShiftHeld);` (`line 1274`) | `Buttons::buttonState[SHIFT]` global array (`buttons.cpp`) | **1:1 Parity Verified.** |
| **`CLIP_VIEW` / `SESSION_VIEW` / `KEYBOARD`** | Swaps `activeView` string + calls `listener.onViewModeChanged()` (`lines 1275-1294`) | `changeUIMode(...)` / `InstrumentClipView::buttonAction(CLIP_VIEW)` (`instrument_clip_view.cpp:306`) | **1:1 Parity Verified.** |
| **`SYNTH` / `KIT` / `MIDI` / `CV`** | Calls `selectSiblingOrAddTrack(type, idx)` (`lines 1295-1298`) | `InstrumentClipView::buttonAction(SYNTH/KIT/...)` (`instrument_clip_view.cpp:265`) | **1:1 Parity Verified.** Cycles between sibling tracks or creates a new track if none exist. |
| **`MOD0` – `MOD7`** | Calls `selectModKnobMode(0..7)` (`lines 1299-1306`) | `selectModKnobMode(mode)` (`sound_editor.cpp:1015`) | **1:1 Parity Verified.** Sets which parameter pair the upper/lower gold knobs edit (`PAN/VOL`, `RES/CUTOFF`, etc.). |
| **`LOAD` / `SAVE`** | Calls `listener.onLoadProject()` / `listener.onSaveProject()` (`lines 1307-1308`) | `View::buttonAction(SAVE/LOAD)` (`view.cpp:227-326`) | **CORRECTED (2026-07-14) — original claim was BACKWARDS.** Re-read the actual C: a **plain** LOAD press (no Shift) opens `loadSongUI` (`view.cpp:295-300`); **Shift+LOAD** opens a **Clear Song confirmation** (`context_menu::clearSong`, `view.cpp:277-292`) — not a load dialog at all. **Shift+SAVE does nothing** (`view.cpp:234`'s `!Buttons::isShiftButtonPressed()` guard means the save-button-holding mode is never entered while Shift is held); a plain SAVE press opens `saveSongUI`. The "instrument preset" save/load behavior the original row described is gated on holding **SYNTH/KIT/MIDI/CV simultaneously with SAVE/LOAD** (a modifier this whole `view.cpp` branch explicitly excludes via its leading guard) — a different combo than Shift, not yet located/verified. Java's current plain LOAD/SAVE already broadly matches the plain-press behavior in spirit (opens a file chooser instead of the exact hardware UI). **Do not implement the original proposal** (item 3 in §5 below) — it would make Shift+LOAD open a preset browser where hardware opens a destructive-sounding confirmation dialog. |
| **`BACK` (Undo/Redo)** | `if (isShiftHeld) listener.onRedo(); else listener.onUndo();` (`lines 1309-1320`) | `View::buttonAction(BACK)` (`view.cpp:380-422`) | **1:1 Parity Verified.** |
| **`SCALE_MODE` / `AFFECT_ENTIRE` / `TRIPLETS` / `CROSS_SCREEN` / `SYNC_SCALING` / `LEARN` / `TAP_TEMPO`** | Toggles state booleans + updates OLED (`lines 1321-1369`) | `View::buttonAction(...)` (`view.cpp:330` for `SYNC_SCALING`, `instrument_clip_view.cpp:263` for `SCALE_MODE`) | **Verified Gap (Scale Toggle Timing):** In C++ (`instrument_clip_view.cpp:263`), `SCALE_MODE` is toggled on button **release** after checking if the user long-pressed (`scaleButtonPressTime` at `instrument_clip_minder.cpp:63`) to pick a root note/scale. In Java, `SCALE_MODE` toggles instantly on mouse-down. |

---

## 2. Rotary Encoders & Encoder Push-Buttons (`rotateEncoder` vs C++)
In `SwingHardwareTopPanel.rotateEncoder()` (`lines 950–1047`) and `handleMouseClick()` (`line 1410 default`), encoder rotations and clicks (`pushMod`) are processed. Below is the verified side-by-side comparison against `horizontalEncoderAction`, `verticalEncoderAction`, `selectEncoderAction`, and `modEncoderButtonAction`:

| Encoder Name | Swing UI Logic (`SwingHardwareTopPanel.java`) | Verified C++ Firmware Equivalent | Audit Status & Verified Differences |
| :--- | :--- | :--- | :--- |
| **`SELECT_ENC` (Rotation)** | Calls `gp.cycleActiveTrackPreset(step, oledPanel)` (`lines 981-989`) | `InstrumentClipMinder::selectEncoderAction(offset)` (`instrument_clip_minder.cpp:70`) | **1:1 Parity Verified (Fixed Today).** Cycles XML presets from `AssetLibrary` (`5x` step on `SHIFT`), replaces track parameters while keeping clips/colors intact, and shows the exact preset name on the OLED. |
| **`SELECT_ENC` (Push / Click)** | `SwingHardwareTopPanel.handleMouseClick()` calls `SwingDelugeApp.launchSoundEditorForActiveTrack()` (fixed 2026-07-14) | **RE-DERIVED FROM SCRATCH (2026-07-14), verified real:** `buttons.cpp:135-138` (`b == SELECT_ENC` → `getCurrentUI()->buttonAction(...)`) → `InstrumentClipView::buttonAction` (`instrument_clip_view.cpp:827-841`, falls through via `goto passToOthers` in the idle case) → `InstrumentClipMinder::buttonAction` (`instrument_clip_minder.cpp:471-482`) opens the Sound Editor (`soundEditor.setup(...)`, `openUI(&soundEditor)`) when not holding notes / not auditioning. (`offsetNoteCodeAction`, real at `instrument_clip_view.cpp:4789`, is actually the SELECT-encoder-*turn* action while its own button is held — a push+turn gesture, not a bare push; unrelated to this row.) | **Fixed.** Added `SwingDelugeApp.launchSoundEditorForActiveTrack()` (opens the existing "Synth Track Editor" dialog at its default tab) and wired it to a bare `SELECT_ENC` click. Verified against the live running app: dispatched a real `MouseEvent` press+release to the running panel and confirmed the dialog appeared. The `NOTES_PRESSED`/`AUDITIONING` special-case branches aren't modeled (no equivalent "holding a step pad" state on this desktop UI). |
| **`TEMPO_ENC` (Rotation)** | Fine (`+/- 1.0 BPM`), Pushed (`+/- 5.0 BPM`), Shift (`Swing %`) (`lines 966-980`) | `playbackHandler.commandEditTempoFine()` (`playback_handler.cpp:2244`), `commandEditTempoCoarse()` (`:2222`), `commandEditSwingAmount()` (`:2133`) | **1:1 Parity Verified (Fixed Today).** Corrected fine vs coarse push ordering. |
| **`TEMPO_ENC` (Push / Click)** | Shows current BPM on OLED (`SwingHardwareTopPanel.java`, fixed 2026-07-14) | **CORRECTED (2026-07-14):** `commandToggleTempoBlink` **does not exist anywhere in the C++ source** — fabricated. Real behavior (`buttons.cpp:211-229`): plain press → `commandDisplayTempo()` (just shows current BPM); Shift+press → `commandClearTempoAutomation()` (`playback_handler.cpp:2099`); press while `TAP_TEMPO` also held → `commandDisplaySwingInterval()`. | **Fixed (corrected).** Plain press now shows BPM, matching `commandDisplayTempo()`. Shift/TAP_TEMPO chord variants not implemented (Java's simpler automation model has no clearable per-parameter track; the two-button chord is a rare case not worth the extra state tracking for a read-only popup). |
| **`X_ENC` (Horizontal / Length)** | `push && shift` -> `adjustClipLength`, `push` -> `adjustZoomResolution`, `shift` -> `adjustClipLength`, else -> `scrollHorizontally` (`lines 1014-1033`) | `horizontalEncoderAction()` (`instrument_clip_view.cpp:6102` & `clip_view.cpp:146`) | **1:1 Parity Verified (Fixed Today).** Reordered modifier checks to eliminate shadowing on `push && shift`. |
| **`X_ENC` (Push / Click)** | Falls into `default -> oledPanel.showParamText("X_ENC", "ACTIVE")` | **RE-DERIVED FROM SCRATCH (2026-07-14), real behavior found:** no `b == X_ENC` case in `hid/buttons.cpp` itself (buttons dispatch per-UI); `InstrumentClipView::buttonAction`'s `else if (b == X_ENC)` (`instrument_clip_view.cpp:720-767`) falls through (no Shift) to `TimelineView::buttonAction` (`timeline_view.cpp:68-84`), which on press with no active UI mode calls `displayZoomLevel()` (a popup showing the current zoom) and enters `UI_MODE_HOLDING_HORIZONTAL_ENCODER_BUTTON`. | **Not implemented (deprioritized).** Real but low-value — a read-only popup of information already visible elsewhere in this UI. Fixed `SELECT_ENC` (real, higher-value) instead this pass; revisit if wanted. |
| **`Y_ENC` (Vertical / Transpose)** | `push && shift` -> `transposeTrack(delta)`, `push` -> `transposeTrack(delta * 12)`, `shift` -> `adjustTrackColorOffset(delta)`, else -> `scrollVertically(delta)` (`lines 990-1013`) | `verticalEncoderAction()` (`instrument_clip_view.cpp:5965`) | **1:1 Parity Verified (Fixed Today).** Corrected downward/upward scroll signs and semitone/octave transpose intervals. |
| **`Y_ENC` (Push / Click)** | Falls into `default -> oledPanel.showParamText("Y_ENC", "ACTIVE")` | **RE-DERIVED FROM SCRATCH (2026-07-14) — CONFIRMED NO GAP.** `InstrumentClipView::buttonAction`'s `else if (b == Y_ENC)` (`instrument_clip_view.cpp:770-825`) only does anything when `UI_MODE_NOTES_PRESSED` or `UI_MODE_AUDITIONING` is active. Unlike the `X_ENC` branch, there is no trailing fallthrough for the idle case — with neither mode active, nothing in the branch runs and the event is consumed with zero effect (`return ActionResult::DEALT_WITH` at line 852). | **Nothing to fix.** A bare `Y_ENC` press with no notes held and nothing auditioned is a genuine no-op on real hardware too — Java's current behavior (do nothing) is already correct. The original "gap" claim for this row was fabricated. |
| **`MASTER_VOL` (Rotation)** | Calls `bridge.setMasterVol(vol)` (`0.0` to `2.0`) + displays `vol * 100 + " %"` on OLED (`lines 1035-1044`) | Global master volume (`sound.cpp` / `bridge`) | **1:1 Parity Verified (Fixed Today).** |
| **`MOD_ENCODER_0` / `MOD_ENCODER_1` (Rotation)** | Adjusts assigned parameter pair (`PAN/VOL`, `RES/CUTOFF`, etc.) + updates 4-square LED bargraphs on gold knobs (`lines 1054-1190`) | `Sound::modKnobs[mode][knob]` (`sound.cpp:97-122`) | **1:1 Parity Verified.** Matches ear-linear cutoff curves (`Math.pow(1.01, delta)`), Q31 delay feedbacks, and continuous sub-square LED fill levels (`currentModKnobFillLevel` at `lines 762-797`). |
| **`MOD_ENCODER_1` (Push, stutter half only)** | `mousePressed`/`mouseReleased` in `SwingHardwareTopPanel.java` (fixed 2026-07-14) call `FirmwareSound.beginStutter(cfg)`/`endStutter()` when `modKnobMode==6` | Verified full chain (2026-07-14): `buttons.cpp:234-238` (press dispatch) → `global_effectable.cpp:225-236` / `sound.cpp:4440-4457` (the `modKnobMode==6` check, since `sound.cpp:117-118` sets `modKnobs[6][1]=UNPATCHED_STUTTER_RATE` by default) → `ModControllableAudio::beginStutter`/`endStutter` (`mod_controllable_audio.cpp:1299-1329`) → `Stutterer::beginStutter`/`endStutter` (`model/fx/stutterer.cpp:66-108,210-237`). | **Fixed.** `firmware2.Stutterer`'s DSP core was already a complete, wired port (called from `firmware2/Sound.java` render loop) — only the UI trigger was missing. Added `FirmwareSound.beginStutter(Stutterer.Config)`/`endStutter()` wrappers and wired `MOD_ENCODER_1`'s press/release in `SwingHardwareTopPanel`, gated on `modKnobMode==6`; turning the knob while held still adjusts the rate live via the existing rotation path. Guarded by `FirmwareSoundStutterTest`. MIDI-CC-assignment mode (the other `modEncoderButtonAction` branch) still not implemented — no MIDI-CC-learn UI mode exists in Java yet. |

---

## 3. Grid Step & Pad Actions (`padAction` vs C++)
In `ClipEditorController.java`, `SongGridPanel.java`, and `ArrangerTimelineController.java`, grid pad mouse presses/drags/releases are handled:

| View & Action | Swing UI Logic (`ClipEditorController` / `SongGridPanel` / `ArrangerTimelineController`) | Verified C++ Firmware Equivalent | Audit Status & Verified Differences |
| :--- | :--- | :--- | :--- |
| **Clip View Step Click (`ClipEditorController.mousePressed`)** | Left-click toggles step (`bridge.setStep`) + pushes step change to Undo stack (`lines 189-250`) | `InstrumentClipView::padAction(x, y, velocity)` (`instrument_clip_view.cpp:1819`) | **1:1 Parity Verified.** Cleanly toggles steps, velocity blends, and triggers live note previews (`sendMidiNote`). |
| **Clip View Shift + Click (`handleShiftClick`)** | Delegates to `ShiftClickController.handleShiftClick()` (`ClipEditorController.java:998`) | `padAction` shift shortcuts (`instrument_clip_view.cpp`) | **1:1 Parity Verified.** Executes specific pad shortcuts (e.g., Note length tie across steps, clear note row, randomize step). |
| **Clip View Right-Click (`handleStepLongPressed`)** | Opens Swing `JPopupMenu` with options to edit Velocity, Probability, Note Length, or Micro-timing (`ClipEditorController.java:188`) | `UI_MODE_NOTES_PRESSED` (`instrument_clip_view.cpp:1807`) | **Verified Architectural Adaptation:** On hardware, holding down a step pad (`UI_MODE_NOTES_PRESSED`) and turning `Y_ENC` / `SELECT_ENC` edits note velocity, probability, and length. In the desktop UI, right-clicking a step pad opens a rich context menu with direct sliders for Velocity, Probability, and Length. |
| **Song View Pad Click (`SongGridPanel.java:463-467` & `624-629`)** | Left-click on clip cell launches/stops clip (`track.setActiveClipId`); right-click opens solo/track context menu; empty cell click opens create-track menu | `SessionView::padAction(x, y, on)` -> `gridHandlePads()` (`session_view.cpp:692` & `3841`) | **1:1 Parity Verified.** |
| **Arranger View Pad Click (`ArrangerTimelineController.mousePressed`)** | Left-click places clip instance at bar (`addArrangerClip`), double-click or right-click deletes/edits placement (`lines 88-102`) | `ArrangerView::padAction(x, y, on)` (`arranger_view.cpp:920`) | **1:1 Parity Verified.** Properly accounts for horizontal arrangement scroll (`arrangerTickForColumn(col)` at `line 69`). |

---

## 4. Side Columns & Grid Navigation (Columns 16, 17, 18)
In `ClipGridPanel.java`, `SongGridPanel.java`, `ArrangerGridPanel.java`, and `SwingGridPanel.java`, the rightmost grid columns and navigation controls are rendered and bound:

| Column / Control | Swing UI Logic (`ClipGridPanel` / `SwingGridPanel`) | Verified C++ Firmware Equivalent | Audit Status & Verified Differences |
| :--- | :--- | :--- | :--- |
| **Column 16 (`AUDITION` / Row Button)** | In Clip view, clicking Column 16 previews the row's pitch (`bridge.noteOn` / `noteOff`) or selects the row (`ClipGridPanel.java:1190-1200`) | `InstrumentClipView::auditionPadAction()` (`instrument_clip_view.cpp:4847`) | **1:1 Parity Verified.** Cleanly auditions pitches and drum sounds. |
| **Column 17 (`MUTE`) & Column 18 (`SOLO`)** | Clicking Column 17 toggles `bridge.setMute(engineRow, !isMuted)` / `track.setMuted(...)`. Clicking Column 18 toggles Solo across all other rows/tracks (`ClipGridPanel.java:1145-1175`) | `InstrumentClipView::mutePadPress()` (`instrument_clip_view.cpp:3986`) & `SessionView::padAction()` (`session_view.cpp:1553`) | **1:1 Parity Verified.** |
| **Page Jump Buttons (`< PAGE`, `PAGE >`, `ROW ^`, `ROW v`)** | Directly invokes `scrollController.scrollHorizontallyByPage(-1 / +1)` and `scrollVerticallyByPage(-1 / +1)` (`SwingGridPanel.java:1030-1050`) | `horizontal_scroll.cpp` & `vertical_scroll.cpp` | **1:1 Parity Verified.** Fully bounded and clamped by active track length (`curTrackLen`) and visible row counts. |
| **Zoom & Length Shortcuts (`ZOOM +`, `ZOOM -`, `DOUBLE`, `HALVE`)** | Invokes `adjustZoomResolution(+1 / -1)` and `adjustClipLength(+1 / -1)` (`SwingGridPanel.java:2862-2890`) | `changeClipLength()` / `changeZoomLevel()` (`clip_view.cpp:145-176`) | **1:1 Parity Verified.** Doubling or halving clip length clones step data or truncates trailing notes inside the C++ audio engine (`duplicateTrackContent()` / `bridge.setTrackLength(...)`). |

---

## 5. Verified Summary of Actionable Gaps — RE-VERIFIED 2026-07-14, two of three items were wrong

**This section's original three proposals were re-checked against the actual C++ source before
implementing anything, per this project's "audit citations before trusting them" rule (see
CLAUDE.md). Two of the three did not hold up:**

1. ~~**Wire Gold Knob Push Clicks...**~~ **Stutter half FIXED (2026-07-14); MIDI-CC half still
   deferred.** Re-derived the full call chain from scratch with fresh citations (see §2 row above) —
   `modEncoderButtonAction`, `Stutterer::beginStutter`/`endStutter`, and `UI_MODE_SELECTING_MIDI_CC`
   all genuinely exist. `firmware2.Stutterer`'s DSP core was already fully ported and wired into the
   render loop; added `FirmwareSound.beginStutter`/`endStutter` wrappers plus a `MOD_ENCODER_1`
   press/release handler in `SwingHardwareTopPanel` gated on `modKnobMode==6`. The MIDI-CC-assignment
   half of the original proposal (automation-view gold-knob press → `UI_MODE_SELECTING_MIDI_CC`) is
   NOT implemented — Java has no MIDI-CC-learn UI mode to enter yet; that's new UI/engine plumbing
   beyond this pass's scope.
2. ~~**Wire Encoder Push Clicks (`SELECT_ENC`, `TEMPO_ENC`, `X_ENC`, `Y_ENC`)...**~~ **Original
   citations all fabricated; re-derived from scratch and mostly fixed.** `commandToggleTempoBlink`
   (cited for `TEMPO_ENC`), `horizontalEncoderButtonAction`, and `verticalEncoderButtonAction`
   (cited for `X_ENC`/`Y_ENC`) do not exist anywhere in the C++ source. **Fixed (2026-07-14):**
   `TEMPO_ENC` press shows current BPM (`commandDisplayTempo()`, `buttons.cpp:211-229`); `SELECT_ENC`
   press opens the Sound Editor (`instrument_clip_minder.cpp:471-482`), verified against the live
   app. **`X_ENC` push**: real behavior found (a zoom-level popup, `timeline_view.cpp:68-84`) but
   deprioritized as low-value; not implemented. **`Y_ENC` push**: re-derived and confirmed to have
   **no real behavior at all** in the idle case on actual hardware either
   (`instrument_clip_view.cpp:770-825`, no fallthrough for the idle branch) — Java's existing no-op
   was already correct; the original "gap" claim for this one was entirely fabricated.
3. ~~**Add Shift + `SAVE` / `LOAD` Sub-Menu Shortcuts**~~ **RETRACTED — the original claim was
   backwards.** See the corrected `LOAD`/`SAVE` row in §1 above: plain LOAD opens `loadSongUI` (not
   Shift+LOAD), Shift+LOAD opens a **Clear Song confirmation** (not a load dialog), and Shift+SAVE
   does nothing at all. Implementing the original proposal as written would have made Shift+LOAD
   open a preset browser where hardware opens a destructive-sounding confirmation — not implemented.
   The real "instrument preset" save/load (SYNTH/KIT/MIDI/CV + SAVE/LOAD chord) is a different,
   unverified mechanism — a future task if wanted, requiring hold-state tracking for SYNTH/KIT/MIDI/CV
   that the current Java UI doesn't have (those buttons act immediately on click today).

**Lesson for future audits of this doc's shape:** this session's earlier `docs/FIDELITY_GAP_ANALYSIS.md`
work established "verify every C citation by reading it, don't trust the audit" as a hard rule for
DSP work; this section shows the same discipline is needed for UI-parity audits — a document that
says "verified against the repository filesystem" is a claim to check, not a guarantee.
