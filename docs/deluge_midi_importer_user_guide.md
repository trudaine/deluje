# Deluge-Java Workstation: Native MIDI Importer User Guide

Welcome to the **Native MIDI Importer**! This guide explains how to import, map, split, and compile standard MIDI files (`.mid`, `.midi`) directly into your Deluge-Java Workstation. 

By compiling MIDI files natively, you can import external musical arrangements, chord progressions, and complex piano rolls directly into a Deluge song project, bypassing tedious step-programming on the hardware.

---

## 1. Quick Start: Importing your First MIDI File

To import a MIDI file into your active workstation:

1.  Open the workstation and navigate to the top menu bar.
2.  Select **`File -> Import MIDI File...`** (or use the keyboard shortcut).
3.  Choose any standard MIDI file from your computer.
4.  The **MIDI Import Wizard** will open, showing a card-based deck of all tracks in the file.
5.  Select which tracks to import, map them to your desired instruments, and click **`Import Into Workstation`**.
6.  The arrangement will immediately render on your interactive grid editor, ready to play and edit!

---

## 2. The MIDI Import Wizard Interface

The wizard is designed as a scrollable deck of **Track Configuration Cards**, allowing you to customize each track individually before compile-time:

```
+───────────────────────────────────────────────────────────────+
|  [X] Track 1: Bassline (128 notes)   [001 Saw Bass   ]  [-15] |
+───────────────────────────────────────────────────────────────+
|  [X] Track 2: Melody (320 notes)     [073 Piano      ]  [ 30] |
|      [X] Split Bass   at: [ C3 (60) ] [005 Mono Bass ]        |
+───────────────────────────────────────────────────────────────+
```

### Key Controls on Each Track Card:
*   **Import Toggle (Checkbox)**: Enable or disable importing this specific track. Tracks with zero notes are disabled by default.
*   **Preset Mapping (Combo Box)**: Select which Deluge instrument preset or drum kit to assign.
*   **Grid Color (Combo Box)**: Choose the pad color offset (between -60 and 60) for this track's pads on the launchpad grid.
*   **Split Bass (Checkbox & Panel)**: Activates the **Intelligent Pitch Splitter** for that track (see Section 4).

---

## 3. Dynamic Library Preset Integration

The MIDI Importer is deeply integrated with your Deluge SD card library:
*   When you open a MIDI file, the wizard automatically scans the physical `/SYNTHS/` and `/KITS/` directories set in your **Settings**.
*   All your custom, user-designed synth presets and drum kit XML configurations are loaded directly into the track mapping combo boxes!
*   If your SD card is not connected, the wizard gracefully falls back to a core list of standard preconfigured synth patches (like `073 Piano`, `001 Sync Bass`, and `006 Vaporwave Bass`), ensuring the tool is always fully functional.

---

## 4. Intelligent Pitch/Zone Splitting

Many MIDI files (and all neural audio-to-MIDI transcriptions, like those from Spotify's `basic-pitch` model) combine multiple musical parts—such as a low bassline and a high lead melody—into a single track. 

If imported directly onto a single synth track, both parts will play on the same instrument in a mixed, muddy register. The **Intelligent Pitch Splitter** solves this:

1.  On the track card, check the **`Split Bass`** option.
2.  **Select the Split Point**: Set the boundary pitch (default is `C3` / MIDI Note 60).
3.  **Choose the Bass Instrument**: Select a dedicated bass synth preset (e.g. `001 Sync Bass`) and an optional color.
4.  **How it Compiles**: 
    *   All notes *below* your split point are compiled into a brand new **Bass Track** mapped to your chosen bass synth.
    *   All notes *at or above* the split point are compiled into a **Lead Track** mapped to your main preset.
    *   This gives you two clean, professionally separated tracks instantly!

---

## 5. Technical Underpinnings: Timing & Quantization

The compiler processes note events via two parallel paths, ensuring perfect alignment with the Deluge's hardware engine:

1.  **High-Resolution Path (96 PPQ)**:
    *   MIDI note timings are scaled onto the workstation's high-resolution sequencer grid (running at 96 PPQ).
    *   This preserves all micro-timings, swing, humanized offsets, and expressive velocities from your original performance.
2.  **Quantized Step Grid (16th notes)**:
    *   Notes are simultaneously quantized to standard 16th note steps (4 steps per beat).
    *   This populates the workstation's physical grid pads, allowing you to visually program, mute, and edit steps instantly.

---

## 6. Tips for Best Results

To get the cleanest possible translation from MIDI to the Deluge format:
*   **Format 1 MIDI Files**: Use MIDI files where different instruments (e.g. drums, bass, leads) are separated into individual tracks.
*   **Name Your Tracks**: The wizard automatically reads MIDI track names. Naming your tracks in your DAW (e.g. "Bass", "Arp") makes them instantly recognizable in the wizard deck.
*   **Quantize in Advance**: If you want your notes to line up perfectly on standard 16th-note step pads on the Deluge, pre-quantize your MIDI file in your DAW before importing. If you want to keep organic, loose performances, the high-res engine will preserve them perfectly!
*   **Save Your Song**: When you click import, the song is loaded directly into the workstation's active memory. Click **`File -> Save Project`** to write it to a clean Deluge song XML on your SD card.
