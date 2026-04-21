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
*   **Play/Stop:** `Spacebar`.
*   **Save/New Project:** `Ctrl+S` / `Ctrl+N`.
*   **Export Audio:** `File > Export Audio`.
*   **BPM / Swing:** Top-level sliders + TAP tempo button.

### B. Mouse Interaction (Grid)
*   **Toggle Step:** Left-click cell.
*   **Long Note Entry (Synth):** 
    *   **Action:** Press and hold a cell, then drag horizontally. 
    *   **Visual Feedback:** A "Tied Note" bar extends across the cells. 
    *   **Auto-Scroll:** If the cursor reaches the left or right edge of the grid while dragging, the view scrolls horizontally at a constant rate, allowing notes to span multiple "pages" of the sequence.
*   **Context Menu:** Right-click cell for **Quantize, Transpose, Legato, Delete**.
*   **Fine Timing:** Hold `Shift` + Drag note to disable "Snap to Grid".
*   **Multi-Select:** Click and drag a "Marquee" box over notes or clips.
*   **Duplicate:** `Alt + Drag` an object.
*   **Copy/Paste:** `Ctrl+C` / `Ctrl+V`.

### C. Navigation & View Control
*   **Horizontal Scroll:** `Shift + Mouse Wheel` or drag the bottom scrollbar.
*   **Zoom (Time):** `Ctrl + Mouse Wheel` (Horizontal) to fit more or fewer steps into the view.
*   **Auto-Scroll:** Dragging a note or marquee box to the edge of the grid triggers automatic horizontal scrolling.

### D. Advanced Editing (Pop-ups)
*   **Graph Editor (Synth):** Double-click a Clip to open a node-based **OSC & FM Matrix**. Connect operators via drag-and-drop lines.
*   **Randomize / Generate:** Action button in Synth/Kit dialogs to procedurally generate patches or drum kits (based on external tools like Deluge_Random_Patch).
*   **Multisampling Editor (Kit):** Dedicated waveform view. Drag multiple WAVs onto a virtual keyboard to map zones by pitch.

---

## 4. Synthesis & Audio Engine

### Signal Chain (Insert FX)
`Source (Osc/Sample) -> Bitcrush -> Distortion -> LPF/HPF -> Mod FX (Chorus) -> EQ -> Send (Delay/Reverb) -> Master Bus`.

### Modulation System
*   **4 Envelopes (ENV_0-3):** Exponential stages. ENV_0 routes to Amplitude.
*   **4 LFOs:** 2 Per-Voice (resets on note-on) + 2 Global (free-running).
*   **Filters:** Ladder (12/24dB) and State Variable (SVF) with continuous Morph (LP -> BP -> HP).
*   **Sidechain:** Routing menu to select "Source" (e.g., Kick) and "Target" (e.g., Synth) with ducking depth.

---

## 5. Technical Implementation (The Bridge)

*   **Shared Data:** `BridgeContract.java` defines the shared arrays between Java (UI) and ChucK (DSP).
*   **Synchronization:** Writes from UI are batched in a `pendingChanges` queue and flushed once per frame to avoid VM deadlocks.
*   **Hot-Swap:** Instrument types (Synth/Kit) are managed by independent ChucK shreds, swappable via `Machine.replace()` without stopping the clock.

---
*Specification Version 1.0 — April 21, 2026*
