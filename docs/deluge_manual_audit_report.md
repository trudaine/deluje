# Synthstrom Deluge: Desktop Workstation Feature Gap Audit Report

This report presents a functional audit comparing the official **Synthstrom Audible Deluge Instruction Manual** against the **Java Desktop Workstation** codebase. It identifies missing features, discrepancies in engine behavior, and UI/visual feedback limitations.

> **Status update:** the items with an implementation plan (`docs/proposed_design_plan.md`) have since been **resolved** — horizontal note shift (§1.A), the LFO scope "illusion" (§2.A), armed-launch blinking (§3.A), and physical undo/redo buttons (§3.B). Still open: **Sync-scaling** (§1.B), **cross-screen edit mode** (§1.C — intentionally not ported; the desktop uses the Piano Roll + zoom-out instead), and **high-resolution live-record timing** (§2.B).

---

## 1. Major Missing Features (Not Implemented)

### A. Horizontal Track Content Shifting (Rotate Note Sequences)
*   **Manual Feature (p. 66 / Line 410):**
    > *"If you wish to shift all notes and automation in a track sideways (in the “time” dimension), hold down the ▼▲ knob and turn the ◄► knob. Your track’s contents will be moved sideways in steps of one square at your current zoom level. If the contents move past either end of the track, they will wrap around and appear at the other end."*
*   **Java Implementation Status:** **Completely Missing.**
    *   No shifting/rotating method exists in `ClipModel.java` or `ClipEditorController.java`.
    *   No arrow key or modifier shortcuts exist in [KeyboardShortcutManager.java](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/KeyboardShortcutManager.java) to trigger sideways note shifting.

### B. Sync-Scaling
*   **Manual Feature (p. 119 / Line 1592):**
    > *"Sync-scaling is tied to the length of one track in a song, and tells the Deluge that that track’s length should be squeezed into 1 bar of incoming MIDI beat clock... To enable sync-scaling, enter track view for the track that you wish to tie sync-scaling to. Press the sync-scaling button..."*
*   **Java Implementation Status:** **Completely Missing.**
    *   No representation of a "sync-scaling track reference" exists in `ProjectModel.java` or `PlaybackHandler.java`.
    *   The sync-scaling time stretching calculations are absent from the clock and playhead step tracking loops.

### C. Cross-Screen Edit Mode
*   **Manual Feature (p. 75 / Line 608):**
    > *"At any given zoom level, if you enter cross-screen edit mode, then any editing you do will apply not only to the part of the sequence that you are currently scrolled to, but also to all other “screens” which could be scrolled to... edits would still be applied on a per-bar basis..."*
*   **Java Implementation Status:** **Completely Missing.**
    *   No cross-screen tracking state or edit broadcast handlers exist in `ClipEditorController.java` or `ClipModel.java`.

---

## 2. Behavioral Discrepancies & Code Gaps

### A. LFO Global vs. Local Scope Mismatch (UI Illusion)
*   **Discrepancy:**
    *   [LfoPanel.java:343](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/LfoPanel.java#L343) provides a "Scope" drop-down combobox allowing the user to select "All tracks" (Global) vs. "This track" (Local) for **any** of the 4 LFO slots.
    *   However, the physical Deluge does not allow changing the scope of LFOs, and neither does the engine: it maps LFO slots by **index** — **even slots (0, 2) are global, odd slots (1, 3) are local** (`FirmwareFactory`), matching `LfoModel.defaultConfig`. (Correcting this report's earlier draft, which had global/local reversed.)
    *   Our codebase also hardcodes this inside [FirmwareFactory.java:760-766](file:///Users/ludo/a/deluje/src/main/java/org/deluge/engine/FirmwareFactory.java#L760-L766) and the C++ DSP sound core. The `isLocal()` configuration field is completely ignored.
    *   This makes the "Scope" combobox in the UI a non-functional illusion.

### B. Live Recording Quantization Limits
*   **Discrepancy:**
    *   The hardware records notes at high resolution (192 PPQN) and writes unquantized events into the XML project.
    *   In the desktop workstation, [MidiInputRouter.java:199](file:///Users/ludo/a/deluje/src/main/java/org/deluge/midi/MidiInputRouter.java#L199) resolves incoming events to the active playhead step:
        ```java
        int col = activeGrid.getCurrentPlayheadStep();
        ```
    *   This forces all live-recorded notes to be hard-quantized to the current grid step (e.g. 16th notes), losing high-res timing entirely.

---

## 3. UI and Visual Feedback Limitations

### A. Missing Armed Launch Blinking Feedback
*   **Discrepancy:**
    *   In Song View, [SongGridPanel.java](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/SongGridPanel.java) lets the user queue clips or sections.
    *   The bridge correctly waits until the next bar boundary to switch the clips (`SequencerClock.java` processes the launch queue at step boundaries).
    *   However, the manual states: *"When the Deluge is playing, pressing the “launch” pad will usually not cause the track to stop or start immediately - it will instead become armed (indicated with fast blinking on its 'launch' pad)."*
    *   `SongGridPanel` does not check the launch queue to blink the pad, so there is no visual blinking feedback in the UI while waiting.

### B. Missing Physical Undo/Redo/Back Buttons
*   **Discrepancy:**
    *   The physical hardware has a dedicated `Back` button (for undo) and `Shift+Back` (for redo).
    *   In our desktop workstation, although undo/redo works via keyboard shortcuts (`Ctrl+Z` / `Ctrl+Y`), we do not replicate the back/undo physical button on the UI panel (e.g. in `SwingTopBarPanel.java`).
