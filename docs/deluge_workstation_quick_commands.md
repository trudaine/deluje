# 🎹 High-Fidelity Deluge Workstation Quick Commands & Shortcuts

Welcome to the **ChucK-Java Deluge Workstation**. This guide acts as your premium, high-fidelity reference manual mapping the physical Synthstrom Deluge hardware gestures (updated from the official Popular Commands reference) directly to our desktop workstation's intuitive mouse, trackpad, and keyboard shortcuts.

---

## 🧭 1. Global & Navigation Controls

These controls allow you to scroll through the grid timeline, zoom into resolutions, and manage global play states.

| Functionality | Physical Hardware Gesture | Workstation Desktop Shortcut | UI Component & Implementation |
| :--- | :--- | :--- | :--- |
| **Scroll Timeline (X)** | Turn `◄► knob` | **Mouse Scroll Wheel** (without Shift) OR drag the virtual `◀ ▶` encoder. | [DelugeEncoderStrip](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/ui/controls/DelugeEncoderStrip.java) |
| **Scroll Pitch Rows (Y)** | Turn `▼▲ knob` | **Shift + Mouse Scroll Wheel** OR drag the virtual `▲ ▼` encoder. | [DelugeEncoderStrip](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/ui/controls/DelugeEncoderStrip.java) |
| **Zoom Resolution** | Push `◄► knob` + Turn | **Alt/Cmd + Mouse Scroll Wheel** OR **Push + Turn virtual `◀ ▶` encoder** (Alt-drag/Right-drag). | [adjustZoomResolution()](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/ui/SwingGridPanel.java#L10247) |
| **Global Tempo (BPM)**| Turn `Tempo knob` | **Drag the BPM slider** in the top bar or use the scroll wheel over it. | [SwingTopBarPanel](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/ui/SwingTopBarPanel.java) |
| **Swing Amount** | `Shift` + Turn `Tempo knob` | **Drag the Swing slider** in the top bar. | [SwingTopBarPanel](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/ui/SwingTopBarPanel.java) |
| **Undo Action** | Press `Back` | Click the **Undo button** in the top bar OR press **Cmd+Z / Ctrl+Z**. | [Undo/Redo Stack](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/model/ProjectModel.java) |
| **Redo Action** | `Shift` + Press `Back` | Click the **Redo button** in the top bar OR press **Cmd+Shift+Z / Ctrl+Shift+Z**. | [Undo/Redo Stack](file:///Users/ludo/a/chuckjava/deluge/src/main/java/org/deluge/model/ProjectModel.java) |

---

## 🎹 2. Clip Editor & Note Sequencing (Clip View)

Work in the piano roll and chromatic grids to sequence notes, adjust velocities, micro-timing, and probabilities.

| Functionality | Physical Hardware Gesture | Workstation Desktop Shortcut | High-Fidelity Desktop Feature |
| :--- | :--- | :--- | :--- |
| **Enter Note** | Tap any blank Grid Pad | **Left-click** a pad to trigger note entry. | Renders in the track's glowing signature HSB color. |
| **Delete Note** | Tap an active Grid Pad | **Left-click** an active pad to remove it. | Instantly updates the ChucK DSP synthesizer. |
| **Adjust Gate (Length)** | Hold note pad + tap end pad | **Left-click and drag horizontally** OR **Alt + Scroll Mouse Wheel** over active note pad. | **Sustain Ribbon/Tail:** Draws a gorgeous, semi-transparent tail ribbon. (Displays step length on OLED). |
| **Clone/Duplicate Note**| Hold pad + tap another row pad | **Alt + Left-click and drag** an active note to copy it to a new pitch/time. | Perfect for copying complex step automation. |
| **Transpose Note (Pitch)** | Hold pad + turn `▼▲ knob` | **Ctrl + Scroll Mouse Wheel** over active note pad. | **Real-Time DSP Sync:** Instantly transposes the note in memory and migrates its DSP audio engine channel on-the-fly! (Displays pitch value on OLED). |
| **Velocity Adjust**     | Hold note pad + turn `◄► knob` | **Scroll Mouse Wheel** over active note pad. | **Non-Linear Brightness:** Pad glow scales dynamically with note velocity! (Displays `0..127` on OLED). |
| **Step Probability**    | Hold pad + turn `Select` left | **Shift + Scroll Mouse Wheel** over active note pad OR Right-click $\rightarrow$ Slider. | Un-triggered steps dynamically dim during playback. (Displays `%` on OLED). |
| **Step Iterance**       | Hold pad + turn `Select` right| **Right-click active pad** $\rightarrow$ Set the **Iteration Condition** dropdown. | Supports complex physical iteration rules. |
| **Fill Mode Shifting**  | *Firmware Dependent* | **Right-click active pad** $\rightarrow$ Set **Fill Condition** to active. | **Cyan Glow:** Steps set to play on fill glow in glowing Cyan-Blue (`0x00d2ff`). |

---

## 🚀 3. Advanced Performance & Transposition Gestures

Workstation-exclusive parity features that speed up your music production workflow.

### 📋 Copy & Paste Notes (Clip View Pattern Snapshots)
*   **Hardware Gesture:** `Hold Learn + Push ◄► knob` (Copy) / `Hold Learn + Shift + Push ◄► knob` (Paste)
*   **Workstation Shortcut:** 
    *   **Copy:** Hold **`L`** on keyboard + **Click the virtual `◀ ▶` encoder** OR press **Ctrl + Shift + C**.
    *   **Paste:** Hold **`L`** + **Shift** on keyboard + **Click the virtual `◀ ▶` encoder** OR press **Ctrl + Shift + V**.
*   **Desktop Behavior:** 
    > [!TIP]
    > Snapshots the entire active clip's note pattern to a global clipboard. You can switch tracks or clips and paste it instantly! Flashes `"COPY"` and `"PAST"` readouts on the OLED 7-segment display. Automatically synchronizes all pasted notes to the ChucK DSP engine with absolute sample alignment.

### 🎛️ Transpose Track (Semitone)
*   **Hardware Gesture:** `Push + Turn ▼▲ knob` (Vertical scroll encoder)
*   **Workstation Shortcut:** **Alt + Drag (or Right-drag) the vertical `▲ ▼` encoder** OR **Alt + Scroll Wheel** over the vertical encoder.
*   **Desktop Behavior:** 
    > [!IMPORTANT]
    > Shifting this encoder dynamically transposes all note steps inside the active clip up or down. The note pads visually slide up/down on your grid in real time, and the synthesis engine updates its playback pitch instantly!

### 🔁 Duplicate Track Content (Double Length)
*   **Hardware Gesture:** `Shift + Push ◄► knob` (Horizontal scroll encoder)
*   **Workstation Shortcut:** Hold **Shift** + **Click the virtual `◀ ▶` encoder**.
*   **Desktop Behavior:** 
    > [!TIP]
    > Instantly doubles your track's step count (e.g., growing from 16 steps to 32 steps) and copies your programmed pattern from the first half into the second half. Perfect for writing quick variations!

### 🎼 Scale Mode Selection
*   **Hardware Gesture:** `Shift + Scale` (Cycle scales) / `Scale + Audition pad` (Root note)
*   **Workstation Shortcut:** Click the **Diatonic Scale Dropdown** in the Master FX panel to select Scales (C Major, A Minor, etc.) or toggle **Scale Mode** on/off.
*   **Desktop Behavior:** Chromatic mode displays all 12 semitones, while Diatonic mode filters the rows to scale degrees, with octave C rows illuminated with a subtle white line overlay.

---

> [!NOTE]
> All desktop shortcuts are designed with absolute safety, preventing EDT (Event Dispatch Thread) blocking to ensure that audio playback remains click-free and responsive even during heavy track duplications, transpositions, or copy-paste operations.
