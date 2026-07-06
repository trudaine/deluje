# 📖 Deluge-Java Workstation: How-To Guides

Welcome to the desktop Workstation How-To Guides. This wiki-style guide explains how to perform all standard Synthstrom Deluge operations and workflow tasks directly within the Java Workstation interface.

---

## Table of Contents
1. [Clip & Note Editing](#1-clip--note-editing)
    * [How to Change Clip Length](#how-to-change-clip-length)
    * [How to Rotate / Shift Notes in a Clip](#how-to-rotate--shift-notes-in-a-clip)
    * [How to Add/Remove Columns in a Clip](#how-to-addremove-columns-in-a-clip)
    * [How to Clear a Clip](#how-to-clear-a-clip)
    * [How to Edit Note Velocity, Probability, and Gate (Length)](#how-to-edit-note-velocity-probability-and-gate-length)
    * [How to Edit Note Fill Settings](#how-to-edit-note-fill-settings)
2. [Drum Kits](#2-drum-kits)
    * [How to Create a New Drum Kit](#how-to-create-a-new-drum-kit)
    * [How to Load a Folder of Samples as a Kit](#how-to-load-a-folder-of-samples-as-a-kit)
    * [How to Move a Drum Kit Row](#how-to-move-a-drum-kit-row)
    * [How to Delete a Drum Kit Row](#how-to-delete-a-drum-kit-row)
    * [How to Clean Unused Drum Kit Rows](#how-to-clean-unused-drum-kit-rows)
    * [How to Configure Sample Repeat Mode / Affect Entire Kit](#how-to-configure-sample-repeat-mode--affect-entire-kit)
3. [MIDI & External Control](#3-midi--external-control)
    * [How to MIDI Learn a Parameter to a Hardware Control](#how-to-midi-learn-a-parameter-to-a-hardware-control)
    * [How to Setup Specific Track MIDI Follow Channels](#how-to-setup-specific-track-midi-follow-channels)
    * [How to Control Track Mutes & Solos via MIDI CC](#how-to-control-track-mutes--solos-via-midi-cc)
4. [Multisample Preset Mapping](#4-multisample-preset-mapping)
    * [How to Transpose / Detune a Multisample Keyzone](#how-to-transpose--detune-a-multisample-keyzone)

---

## 1. Clip & Note Editing

### How to Change Clip Length
*   **Method 1 (Double length):** Hold **Shift** on your keyboard and click the virtual `◀ ▶` horizontal scroll encoder in the main grid panel. This instantly doubles the track length and duplicates the existing pattern into the new section.
*   **Method 2:** Click the **inspect** or double-click the track header to open the track properties sidebar, and adjust the **Length** spinner control.

### How to Rotate / Shift Notes in a Clip
*   To rotate or shift all sequenced notes inside the currently focused track left or right:
    *   Press **`Alt + Left Arrow`** to shift/rotate notes 1 step to the left.
    *   Press **`Alt + Right Arrow`** to shift/rotate notes 1 step to the right.
    *   Notes that shift past the start or end boundary will wrap around automatically.

### How to Add/Remove Columns in a Clip
To add or remove step columns at a specific position inside a clip:
1.  **Shift the clip** (using `Alt + Arrow keys`) until the boundary where you want to add or remove columns aligns with the end of the clip.
2.  **Adjust the clip length** (using the track properties Length spinner) to add or remove steps at the end.
3.  **Shift the clip back** to its original position.

### How to Clear a Clip
*   Right-click any empty space in the sequencer grid to open the context menu and select **"Clear Clip"** to erase all notes.

### How to Edit Note Velocity, Probability, and Gate (Length)
Instead of holding pads and turning hardware encoders, you can adjust parameters on active note steps directly:
*   **Note Velocity:** Scroll your mouse wheel up or down over an active note pad. The pad's visual brightness will scale dynamically to show velocity strength.
*   **Step Probability:** Hold **Shift** and scroll your mouse wheel over an active note pad. Alternatively, right-click the pad and adjust the **Probability** slider.
*   **Gate (Step Length):** Hold **Alt** and scroll your mouse wheel over an active note pad to extend or shorten the sustain tail.

### How to Edit Note Fill Settings
*   Hold **Shift + Alt** and scroll your mouse wheel over an active note pad to cycle note fill parameters.
*   A transient indicator `[ FILL: 85% ]` or `[ FILL: OFF ]` will flash on the top bar's OLED readout display to show the current setting.

---

## 2. Drum Kits

### How to Create a New Drum Kit
*   In the track list panel, click **"+" (Add Track)** and select **"Kit Track"** to create a blank drum kit.

### How to Load a Folder of Samples as a Kit
*   In the **Library browser** (left sidebar), find the directory containing your audio files.
*   Right-click the folder and select **"Load Folder as Drum Kit"**. This automatically populates kit rows with all supported samples found inside the folder.

### How to Move a Drum Kit Row
*   In the drum list panel (left side of the Kit Grid), click and drag any drum row header vertically to reposition it.

### How to Delete a Drum Kit Row
*   Right-click the drum row header you want to remove and select **"Delete Row"** from the context menu.

### How to Clean Unused Drum Kit Rows
To clean up your workspace by removing drum rows that do not contain any sequenced notes:
1.  Open the **Kit Configurator** by clicking the kit icon on the track header.
2.  Click **"Clean Unused Rows"**.

### How to Configure Sample Repeat Mode / Affect Entire Kit
1.  Open the **Kit Configurator** panel.
2.  To apply parameter changes (like envelopes or filter cuts) to all sample slots simultaneously, check the **"Affect Entire Kit"** option before tweaking knobs.

---

## 3. MIDI & External Control

### How to MIDI Learn a Parameter to a Hardware Control
To bind any on-screen knob, fader, or parameter to a knob/fader on your external MIDI keyboard/controller:
1.  Right-click the target knob or slider on the synth editor panel and select **"MIDI Learn"**.
2.  Move the physical knob or fader on your external MIDI controller. The workstation will detect the incoming Control Change (CC) message and bind the control instantly.

### How to Setup Specific Track MIDI Follow Channels
To bind incoming MIDI messages to specific tracks regardless of which track has UI focus:
1.  Open the **Preferences** panel.
2.  Navigate to the **MIDI Follow Channels** settings.
3.  Assign specific MIDI input channels (1–16) to the corresponding track numbers.

### How to Control Track Mutes & Solos via MIDI CC
External MIDI controllers mapped to specific track follow channels can control mix states directly:
*   Transmit **CC 89** to toggle the track's **Mute** state.
*   Transmit **CC 90** to toggle the track's **Solo** state.

---

## 4. Multisample Preset Mapping

### How to Transpose / Detune a Multisample Keyzone
To detune individual sample zones inside a multisample instrument:
1.  Double-click the sample track header to open the synth options, and click **"Keyzone Mapper"**.
2.  Click on a mapped keyzone rectangle in the visual editor map.
3.  In the details panel under **PITCH LIMITS**, adjust the **Transpose** spinner (range of ±48 semitones). The synthesis engine will instantly update sample playback rates in real time.

---

## 5. Hardware Synchronization & DSP Parity Calibration

### How to Sync Songs and Presets Over USB
To transfer songs and presets directly to and from your physical Deluge's SD card over USB without removing the card:
1.  Connect the physical Deluge to your computer via USB and verify it is powered on.
2.  Right-click the **Hardware Status Panel** (or the **Pure SD Card Explorer** sidebar) in the Workstation.
3.  *   Select **"Pull Calibration Files"** or **"Sync Card Content"** to clone songs/presets from the Deluge SD card onto your computer.
    *   Select **"Push Current Song to Card"** to save your active project directly to the Deluge SD card.

### How to Run a Live DSP Parity Calibration Session
To capture sample-accurate rendering audio from the physical Deluge to calibrate a synth patch's envelope, filters, or FM index:
1.  Connect the physical Deluge via USB and ensure it is running the custom C++ firmware compiled from the `feat/dsp-buffer-dump` branch.
2.  In your terminal, launch the live hardware capture harness:
    ```bash
    mvn test -Dtest=HardwareDspTapTest -Pslow-tests -Dgpg.skip=true -Dtap.onset=true
    ```
3.  When prompted by the console, strike a note pad on the physical Deluge. The harness will detect the note-on trigger, arm the capture at note-onset, and read back the 4096-sample rendering buffer over USB.
4.  Run the index sweep or scorecard test suite (e.g., `FmIndexSweepTest` or `FmCalibrationScorecardTest`) to compare the capture against the Java simulation and determine the necessary parameter adjustments.
