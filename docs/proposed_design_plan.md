# Design Proposal and Implementation Plan: Deluge Workstation Enhancements

This document outlines the proposed design and implementation plan to resolve the key feature gaps identified in the audit. Each section includes verification, proposed user interface changes, and technical code design.

---

## 1. Horizontal Track Content Shifting (Rotate Note Sequences)

### Verification
We verified that `KeyboardShortcutManager.java` contains no references to arrow keys (`VK_LEFT`, `VK_RIGHT`, `VK_UP`, `VK_DOWN`), and there is no UI option or method in `ClipModel.java` that shifts all note columns sideways. The feature is completely missing.

### Proposed UI Design
*   **Keyboard Shortcuts:**
    *   `Alt + Left Arrow`: Shift/rotate all notes on the focused track 1 step to the left.
    *   `Alt + Right Arrow`: Shift/rotate all notes on the focused track 1 step to the right.
*   **Quick Help / Status Bar:**
    *   When the user focuses a track, the status bar helper will mention the `Alt + Arrow` shortcut for horizontal shifting.

### Proposed Code Design
1.  **Model Extension (`ClipModel.java`):**
    *   Add a method `public void shiftNotesHorizontally(int steps)`:
        *   Iterate over all `NoteRowModel` objects in the clip.
        *   For each row, rotate the array of steps horizontally by the specified number of steps (positive for right, negative for left).
        *   Wrap steps that move past the bounds of the track length using modulo arithmetic (`Math.floorMod(col + steps, stepCount)`).
2.  **Controller Wiring (`ClipEditorController.java`):**
    *   Expose a delegate method `public void shiftFocusedTrackNotes(int steps)`.
    *   Create a new `Consequence` (e.g., `TrackShiftConsequence`) to capture the pre-shift and post-shift state of the note rows, pushing it to the `UndoRedoStack` so the shift action is fully undoable.
3.  **Keyboard Routing (`KeyboardShortcutManager.java`):**
    *   Add a case in `keyPressed` for `KeyEvent.VK_LEFT` and `KeyEvent.VK_RIGHT` when `Alt` is pressed.
    *   Invoke `app.activeGridPanel().shiftFocusedTrackNotes(offset)` and trigger a grid repaint.

---

## 2. Armed Launch Blinking Feedback (Song Grid Panel)

### Verification
In `SongGridPanel.java`, clicking a mute square immediately changes the background color. Although the engine correctly queues the clip launch for the next bar boundary using `bridge.setLaunchQueue(t, c)`, the UI provides no visual indication that the track is "armed" and waiting.

### Proposed UI Design
*   The launch pad button (second from the right in Song View) should flash rapidly between its active color and white (or a dimmed color) when the track is armed to start or stop, providing clear visual feedback matching the physical hardware behavior.

### Proposed Code Design
1.  **Armed State Tracking (`SongGridPanel.java`):**
    *   In the pad rendering logic of `SongGridPanel.java`, check if a clip is currently queued:
        ```java
        int queuedClipIdx = bridge.getLaunchQueue(trackIndex);
        boolean isArmed = (queuedClipIdx >= 0);
        ```
2.  **Swing Animation Timer:**
    *   Introduce a shared `javax.swing.Timer` in `SongGridPanel` that fires every 200ms.
    *   Maintain a toggle boolean state (`blinkPhase`).
    *   If a track pad is marked as `isArmed`, render its background using the normal color during `blinkPhase == true`, and a bright white highlight or dimmed color during `blinkPhase == false`.
    *   Repaint the affected pads on timer ticks. When the launch queue is consumed (`bridge.getLaunchQueue(trackIndex) == -1`), stop blinking and show the solid target color.

---

## 3. LFO Global vs. Local Layout Refactoring (UI Cleanup)

### Verification
`LfoPanel.java` displays a "Scope" selector combobox for all 4 LFOs. However, the C++ engine hardcodes LFO 0 & 2 as global, and LFO 1 & 3 as local. The combobox selection is ignored by the engine during compilation.

### Proposed UI Design
*   Remove the "Scope" combobox from all 4 LFO rows.
*   Display a static, clear label in its place:
    *   **LFO 0 & LFO 2:** "Global (shared across voices)"
    *   **LFO 1 & LFO 3:** "Local (per-voice retriggered)"

### Proposed Code Design
1.  **Refactor `LfoPanel.java`:**
    *   Remove `scopeCombo` creation and listeners around line 343.
    *   Add a `JLabel` instead:
        ```java
        boolean isGlobal = (lfoIdx == 0 || lfoIdx == 2);
        JLabel scopeLabel = new JLabel(isGlobal ? "Global" : "Local");
        scopeLabel.setForeground(isGlobal ? Color.GREEN : Color.CYAN);
        scopeLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
        controlsPanel.add(scopeLabel, c);
        ```

---

## 4. Physical Undo/Redo Buttons (Top Bar)

### Verification
The metronome and tap tempo controls are visible in the top bar, but the back/undo button is not represented. Undo/redo is only accessible via keyboard shortcuts or the edit menu.

### Proposed UI Design
*   Add two small buttons next to the transport controls in `SwingTopBarPanel.java` labeled `[ ⟲ Undo ]` and `[ ⟳ Redo ]`.

### Proposed Code Design
1.  **Component Addition (`SwingTopBarPanel.java`):**
    *   Instantiate `JButton undoBtn` and `JButton redoBtn`.
    *   Apply matching dark theme styles and layout positioning.
2.  **Action Binding:**
    *   Wire the action listeners to delegate to `SwingDelugeApp` methods:
        ```java
        undoBtn.addActionListener(e -> app.doUndo());
        redoBtn.addActionListener(e -> app.doRedo());
        ```
    *   Enable/disable the buttons dynamically by querying `projectModel.getUndoRedoStack().canUndo()` / `canRedo()` on project changes.
