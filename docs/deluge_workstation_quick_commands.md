# 🎹 High-Fidelity Deluge Workstation Quick Commands & Shortcuts

Welcome to the **Deluge-Java Workstation**. This guide acts as your premium, high-fidelity reference manual mapping the physical Synthstrom Deluge hardware gestures (updated from the official Popular Commands reference) directly to our desktop workstation's intuitive mouse, trackpad, and keyboard shortcuts.

---

## 🧭 1. Global & Navigation Controls

These controls allow you to scroll through the grid timeline, zoom into resolutions, and manage global play states.

| Functionality | Physical Hardware Gesture | Workstation Desktop Shortcut | UI Component & Implementation |
| :--- | :--- | :--- | :--- |
| **Scroll Timeline (X)** | Turn `◄► knob` | **Shift + Mouse Scroll Wheel** OR drag the virtual `◀ ▶` encoder. | [DelugeEncoderStrip](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/controls/DelugeEncoderStrip.java) |
| **Scroll Pitch Rows (Y)** | Turn `▼▲ knob` | **Mouse Scroll Wheel** (without Shift) OR drag the virtual `▲ ▼` encoder. | [DelugeEncoderStrip](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/controls/DelugeEncoderStrip.java) |
| **Zoom Resolution** | Push `◄► knob` + Turn | **Alt/Cmd + Mouse Scroll Wheel** OR **Push + Turn virtual `◀ ▶` encoder** (Alt-drag/Right-drag). | [adjustZoomResolution()](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/SwingGridPanel.java#L2977) |
| **Global Tempo (BPM)**| Turn `Tempo knob` | **Drag the BPM slider** in the top bar or turn the virtual **TEMPO** encoder. | [SwingTopBarPanel](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/SwingTopBarPanel.java) |
| **Swing Amount** | `Shift` + Turn `Tempo knob` | *Read-only* from Song XML (no UI control currently mapped). | [SwingTopBarPanel](file:///Users/ludo/a/deluje/src/main/java/org/deluge/ui/SwingTopBarPanel.java) |
| **Undo Action** | Press `Back` | Press **Cmd+Z / Ctrl+Z**. | [Undo/Redo Stack](file:///Users/ludo/a/deluje/src/main/java/org/deluge/model/ProjectModel.java) |
| **Redo Action** | `Shift` + Press `Back` | Press **Cmd+Shift+Z / Ctrl+Shift+Z**. | [Undo/Redo Stack](file:///Users/ludo/a/deluje/src/main/java/org/deluge/model/ProjectModel.java) |

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
| **Fill Mode Shifting**  | *Firmware Dependent* | **Shift + Alt + Scroll Mouse Wheel** over active note pad OR Right-click $\rightarrow$ Set **Fill Condition** to active. | **Cyan Glow:** Steps set to play on fill glow in glowing Cyan-Blue (`0x00d2ff`). |

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

### 🎛️ Custom Grid Border Shortcuts (Grid View)
*   **Hardware Gesture:** Press `Left Column / Right Column` shortcuts to slide parameters.
*   **Workstation Shortcut:** Open the **Track Inspector** (Double-click track header or click Inspect) ➔ Navigate to the **GRID SHORTCUTS** tab to bind the Left/Right border columns to `VELOCITY`, `MOD`, or `PITCH`.
*   **Desktop Behavior:** 
    > [!TIP]
    > Clicking or sliding your finger/mouse vertically along the leftmost or rightmost column pads on the grid panel will dynamically apply the selected modulation/velocity/pitch shift to the active notes on that row!

### 🎹 Multi-Sample Keyzone Tuning (Transpose)
*   **Hardware Gesture:** Hold pad + turn `▼▲ knob` to transpose/detune sample.
*   **Workstation Shortcut:** Double-click sample track header ➔ click **Keyzone Mapper** ➔ Select a Keyzone ➔ Adjust the **Transpose** spinner in the details panel.
*   **Desktop Behavior:** 
    > [!TIP]
    > Allows you to detune/transpose individual sample keyzones in your multi-sample preset by up to ±48 semitones directly from the visual keyzone layout editor. The DSP engine updates the sample playback rate instantly upon adjustment!

---

## 🎛️ 4. The Right-Click Step Context Menu & Step Properties Dialog

Right-clicking any grid pad in **Clip View** opens a premium, context-sensitive popup menu to edit step properties instantly without opening the full dialog, or to jump directly to the **Step Properties** dialog.

### 📋 Context Menu Quick Actions
*   **Step Toggle:** Turn the step ON or OFF.
*   **Velocity Quick-Presets:** Set velocity to `100% (FF)`, `75% (mf)`, `50% (p)`, or `25% (pp)`.
*   **Fill Condition:** Toggle whether the step behaves as a **Fill** trigger.
*   **Clear Step:** Reset all parameters on the step to default.
*   **Properties...:** Opens the full high-contrast **Step Properties** dialog.

### 🎨 Pad Color Indicator Parity Guide
*   **Dim Charcoal (`#151515` / `#1a1a1a`):** Inactive or empty step.
*   **Glowing Track Color (High Intensity):** Active step (brightness scales dynamically with velocity!).
*   **Dim Track Color:** Inactive step or step with $0\%$ probability of playing on the current pass.
*   **Glowing Cyan-Blue (`0x00d2ff`):** Step with a **Fill Condition** active.
*   **White Line Overlay / Highlight:** Octave C row boundaries in Diatonic/Keyboard views.
*   **Amber Glow (`0xffaa00`):** Queued clip slot in SONG view.
*   **Active Green (`0x00cc00`):** Actively playing loop clip in SONG view.

---

## 🎛️ 5. MIDI Follow & External Controller Integration

The desktop workstation supports the Deluge community firmware's extended **MIDI Follow** protocol, allowing you to control and automate track levels, parameters, mutes, and solos from external hardware controllers (keyboards, launchpads, fader controllers) in real time.

### 🔌 Specific Track Routing & Follow Channels
Instead of just 3 global follow slots, you can map up to **16 distinct MIDI channels** to automatically follow specific tracks (Tracks 1–16).
*   **How it works:** When follow mode is active, incoming MIDI messages (Note On/Off, CCs, Pitch Bend) received on a mapped MIDI channel are automatically routed directly to that specific track, regardless of which track is currently selected/focused in the user interface.
*   **Track Mapping Configuration:** Channel mappings are configured via the MIDI input preferences router.

### 🎚️ Default Mixer CC Controls (Mute & Solo)
When using specific track follow channels, the workstation listens for the following standardized control change (CC) commands to perform track mixing operations:
*   **CC 89 (value `0` to `127`):** Toggles the **Mute** state of the targeted track.
*   **CC 90 (value `0` to `127`):** Toggles the **Solo** state of the targeted track (exclusive solo: solos the target track and mutes all other tracks; receiving CC 90 again unsolos the track and restores previous levels).

---

> [!NOTE]
> All desktop shortcuts are designed with absolute safety, preventing EDT (Event Dispatch Thread) blocking to ensure that audio playback remains click-free and responsive even during heavy track duplications, transpositions, or copy-paste operations.
