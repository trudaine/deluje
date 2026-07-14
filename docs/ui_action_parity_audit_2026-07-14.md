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
| **`LOAD` / `SAVE`** | Calls `listener.onLoadProject()` / `listener.onSaveProject()` (`lines 1307-1308`) | `View::buttonAction(SAVE/LOAD)` (`view.cpp:227, 272`) | **Verified Gap (Missing Shift Shortcut):** In C++ (`view.cpp:272`), holding `SHIFT + SAVE` or `SHIFT + LOAD` opens specific sub-menus (`LoadSongUI`, `SaveInstrumentPresetUI`). In Java right now, clicking `SAVE`/`LOAD` opens the OS file chooser directly, regardless of `isShiftHeld`. |
| **`BACK` (Undo/Redo)** | `if (isShiftHeld) listener.onRedo(); else listener.onUndo();` (`lines 1309-1320`) | `View::buttonAction(BACK)` (`view.cpp:380-422`) | **1:1 Parity Verified.** |
| **`SCALE_MODE` / `AFFECT_ENTIRE` / `TRIPLETS` / `CROSS_SCREEN` / `SYNC_SCALING` / `LEARN` / `TAP_TEMPO`** | Toggles state booleans + updates OLED (`lines 1321-1369`) | `View::buttonAction(...)` (`view.cpp:330` for `SYNC_SCALING`, `instrument_clip_view.cpp:263` for `SCALE_MODE`) | **Verified Gap (Scale Toggle Timing):** In C++ (`instrument_clip_view.cpp:263`), `SCALE_MODE` is toggled on button **release** after checking if the user long-pressed (`scaleButtonPressTime` at `instrument_clip_minder.cpp:63`) to pick a root note/scale. In Java, `SCALE_MODE` toggles instantly on mouse-down. |

---

## 2. Rotary Encoders & Encoder Push-Buttons (`rotateEncoder` vs C++)
In `SwingHardwareTopPanel.rotateEncoder()` (`lines 950–1047`) and `handleMouseClick()` (`line 1410 default`), encoder rotations and clicks (`pushMod`) are processed. Below is the verified side-by-side comparison against `horizontalEncoderAction`, `verticalEncoderAction`, `selectEncoderAction`, and `modEncoderButtonAction`:

| Encoder Name | Swing UI Logic (`SwingHardwareTopPanel.java`) | Verified C++ Firmware Equivalent | Audit Status & Verified Differences |
| :--- | :--- | :--- | :--- |
| **`SELECT_ENC` (Rotation)** | Calls `gp.cycleActiveTrackPreset(step, oledPanel)` (`lines 981-989`) | `InstrumentClipMinder::selectEncoderAction(offset)` (`instrument_clip_minder.cpp:70`) | **1:1 Parity Verified (Fixed Today).** Cycles XML presets from `AssetLibrary` (`5x` step on `SHIFT`), replaces track parameters while keeping clips/colors intact, and shows the exact preset name on the OLED. |
| **`SELECT_ENC` (Push / Click)** | Falls into `default -> oledPanel.showParamText("SELECT_ENC", "ACTIVE")` (`line 1410`) | `instrument_clip_view.cpp:1731` (`offsetNoteCodeAction`) & `sound_editor.cpp:1456` | **Verified Gap:** On hardware during auditioning/note editing (`UI_MODE_AUDITIONING`), pushing `SELECT_ENC` transposes the auditioned note code (`offsetNoteCodeAction`). In menu browsing, pressing it (`enterPressed`) selects/loads the item. In Java, clicking on `SELECT_ENC` currently does nothing but display an OLED note. |
| **`TEMPO_ENC` (Rotation)** | Fine (`+/- 1.0 BPM`), Pushed (`+/- 5.0 BPM`), Shift (`Swing %`) (`lines 966-980`) | `playbackHandler.commandEditTempoFine/Coarse/Swing()` (`playback_handler.cpp:2222-2251`) | **1:1 Parity Verified (Fixed Today).** Corrected fine vs coarse push ordering. |
| **`TEMPO_ENC` (Push / Click)** | Falls into `default -> oledPanel.showParamText("TEMPO_ENC", "ACTIVE")` | `playbackHandler.commandToggleTempoBlink()` (`playback_handler.cpp:2253`) | **Verified Gap:** On hardware, pressing down `TEMPO_ENC` button toggles the tempo-blink LED / tap-tempo mode. In Java, clicking `TEMPO_ENC` does not call `listener.onTapTempo()`. |
| **`X_ENC` (Horizontal / Length)** | `push && shift` -> `adjustClipLength`, `push` -> `adjustZoomResolution`, `shift` -> `adjustClipLength`, else -> `scrollHorizontally` (`lines 1014-1033`) | `horizontalEncoderAction()` (`timeline_view.cpp:130-222` & `clip_view.cpp:145-176`) | **1:1 Parity Verified (Fixed Today).** Reordered modifier checks to eliminate shadowing on `push && shift`. |
| **`X_ENC` (Push / Click)** | Falls into `default -> oledPanel.showParamText("X_ENC", "ACTIVE")` | `horizontalEncoderButtonAction()` (`clip_view.cpp:52` & `timeline_view.cpp:230`) | **Verified Gap:** On hardware, pressing `X_ENC` button prepares for horizontal zoom/scroll. In Java, clicking it without turning does nothing. |
| **`Y_ENC` (Vertical / Transpose)** | `push && shift` -> `transposeTrack(delta)`, `push` -> `transposeTrack(delta * 12)`, `shift` -> `adjustTrackColorOffset(delta)`, else -> `scrollVertically(delta)` (`lines 990-1013`) | `verticalEncoderAction()` (`instrument_clip_view.cpp:5974-6098`) | **1:1 Parity Verified (Fixed Today).** Corrected downward/upward scroll signs and semitone/octave transpose intervals. |
| **`Y_ENC` (Push / Click)** | Falls into `default -> oledPanel.showParamText("Y_ENC", "ACTIVE")` | `verticalEncoderButtonAction()` (`instrument_clip_view.cpp:6099`) | **Verified Gap:** On hardware, pressing `Y_ENC` prepares the track for vertical transposition across selected pads (`UI_MODE_HOLDING_VERTICAL_ENCODER_BUTTON`). In Java, clicking it without turning does nothing. |
| **`MASTER_VOL` (Rotation)** | Calls `bridge.setMasterVol(vol)` (`0.0` to `2.0`) + displays `vol * 100 + " %"` on OLED (`lines 1035-1044`) | Global master volume (`sound.cpp` / `bridge`) | **1:1 Parity Verified (Fixed Today).** |
| **`MOD_ENCODER_0` / `MOD_ENCODER_1` (Rotation)** | Adjusts assigned parameter pair (`PAN/VOL`, `RES/CUTOFF`, etc.) + updates 4-square LED bargraphs on gold knobs (`lines 1054-1190`) | `Sound::modKnobs[mode][knob]` (`sound.cpp:97-122`) | **1:1 Parity Verified.** Matches ear-linear cutoff curves (`Math.pow(1.01, delta)`), Q31 delay feedbacks, and continuous sub-square LED fill levels (`currentModKnobFillLevel` at `lines 762-797`). |
| **`MOD_ENCODER_0` / `MOD_ENCODER_1` (Push / Click)** | Falls into `default -> oledPanel.showParamText("MOD_ENCODER_X", "ACTIVE")` (`line 1410`) | `modEncoderButtonAction(whichModEncoder, on)` (`sound.cpp:4371`, `mod_controllable_audio.cpp:1281`) | **Verified Gap:** On hardware, when `modKnobMode == 6` (`PORTAMENTO / STUTTER`), pressing down gold knob 1 triggers audio stutter (`beginStutter` -> `Stutterer::beginStutter` at `model/fx/stutterer.cpp:66`). Or when in automation overview, pressing down a gold knob enters MIDI CC assignment (`UI_MODE_SELECTING_MIDI_CC` at `automation_view.cpp:4595`). In Java, clicking the gold knobs does nothing. |

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

## 5. Verified Summary of Actionable Gaps
To close the final behavioral gaps between this desktop UI and the authentic physical Deluge firmware uncovered by this verified audit, the remaining items to address are:
1. **Wire Gold Knob Push Clicks (`MOD_ENCODER_0` / `MOD_ENCODER_1`):** In `SwingHardwareTopPanel.handleMouseClick()`, when clicking on either gold knob while `modKnobMode == 6` (`PORTAMENTO / STUTTER`), call into `bridge.setStutter(...)` (`// C: sound.cpp:4380 -> model/fx/stutterer.cpp:66`) instead of falling through to `default -> "ACTIVE"`.
2. **Wire Encoder Push Clicks (`SELECT_ENC`, `TEMPO_ENC`, `X_ENC`, `Y_ENC`):** Connect clicking on `TEMPO_ENC` to invoke `listener.onTapTempo()` (`// C: playback_handler.cpp:2253`), and connect clicking on `SELECT_ENC`, `X_ENC`, and `Y_ENC` to their respective C++ mode triggers (`offsetNoteCodeAction`, `horizontalEncoderButtonAction`, `verticalEncoderButtonAction`).
3. **Add Shift + `SAVE` / `LOAD` Sub-Menu Shortcuts:** Check `if (isShiftHeld)` when clicking `SAVE` / `LOAD` in `SwingHardwareTopPanel.handleMouseClick()` to open specific `LoadSongUI` / `SaveInstrumentPresetUI` dialogs directly, mirroring `View::buttonAction(SAVE/LOAD)` in `view.cpp:227, 272`.
