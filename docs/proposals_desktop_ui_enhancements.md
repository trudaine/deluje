# Premium Desktop UI/UX Proposals for ChucK-Java Deluge Port

This document outlines four high-fidelity desktop UI/UX feature proposals. Each proposal is designed to elevate the desktop software experience beyond the physical constraints of the Deluge hardware (4-character LED screens, rotary dials) while remaining **100% compatible** with the underlying XML project model and virtual audio engine.

---

## Proposal 1: The Modulation Matrix Grid (Virtual Patch Bay)

### 💡 The Concept
On the physical hardware, setting modulations (e.g., routing LFO 0 or Env 1 to Filter Cutoff, Pitch, or Pan) is powerful but invisible. You must hold a parameter dial and press an LFO button, or scroll through menus. 
We propose a **Visual Modulation Matrix Tab** that presents all modulation sources and destinations in a clean, interactive grid.

```
                  [ LFO 0 ]   [ LFO 1 ]   [ Env 0 ]   [ Env 1 ]   [ Velocity ]
Filter Cutoff    [  +42% ]   [   --  ]   [  +15% ]   [  -30% ]   [   +80%   ]
Osc A Pitch      [   --  ]   [  +12% ]   [   --  ]   [   --  ]   [    --    ]
Pan              [  -25% ]   [  +25% ]   [   --  ]   [   --  ]   [    --    ]
Master Volume    [   --  ]   [   --  ]   [  +90% ]   [   --  ]   [  +100%   ]
```

### 🎨 The Desktop UX Advantage
* **Global Visibility**: See every active modulation route in the entire synthesizer at a single glance.
* **Double-Click Routing**: Double-clicking an empty cell instantly creates a modulation route.
* **Direct Drag Adjustments**: Dragging left/right or up/down inside an active cell adjusts the modulation depth (from `-100%` to `+100%`) using a glowing mini-rotary knob or slider helper.
* **Virtual Patch Cables (Optional)**: A toggleable "Modular Patch Bay" view where colored virtual patch cables can be dragged from LFO/Env sockets directly to filter/oscillator jacks.

### ⚙️ Deluge Model Parity
* Directly updates the track's existing `LfoModel.target()`, `LfoModel.depth()`, and envelope modulation depth parameters.
* Fully serialized to and from the standard Deluge song XML—maintaining absolute file portability.

---

## Proposal 2: Tabbed Sound Preset Library & Quick-Load Browser

### 💡 The Concept
Loading synth patches on the hardware requires scrolling through numeric files like `SYN001.XML` or `SYN024.XML` on a 4-character screen.
We propose an **Integrated Sound Preset Library & Browser Sidebar** that docks next to the track configuration editor.

### 🎨 The Desktop UX Advantage
* **Dynamic Tagging & Search**: Scans the SD card (`PRESETS/` folder) in the background, parses XML files for names, and automatically categorizes them by category (e.g., *Bass, Lead, Pad, Pluck, SFX*) and author.
* **Instant Double-Click Load**: Double-clicking a preset instantly swaps the track's `SynthTrackModel`, uploads the parameters to the active virtual audio thread, and updates the UI visualizers in real-time.
* **Preset Favorites & Star Rating**: A desktop-only user configuration file allows starring presets and assigning custom tags without modifying the original Deluge XML files.
* **Visual Preset Compare**: View the parameters of the active sound side-by-side with a selected preset before loading it.

### ⚙️ Deluge Model Parity
* Operates strictly on standard Deluge `.XML` patch files.
* Loading a patch replaces the active track's instrument state using standard model-update events.

---

## Proposal 3: Interactive 3D Wavetable Scanner & Morphing Visualizer

### 💡 The Concept
Wavetable oscillators scan through consecutive single-cycle waves in a WAV file using a `Wave Index` parameter. Currently, this is a simple slider with no visual representation of the wavetable's contents or the current scan position.
We propose a **3D Wavetable Morphing Visualizer** that draws the table in a perspective 3D wireframe plot.

```
       / \             / \
      /   \           /   \      <- Single-cycle wave 1
     /-----\---------/-----\
    /   / \ \       /   / \ \    <- Single-cycle wave 2
   /---/---\-\-----/---/---\-\
  /   /     \ \   /   /     \ \  <- [Current Playhead Plane (Glowing Cyan)]
 /===========*===/===========*=\ <- Single-cycle wave 3
/             \ /             \  <- Single-cycle wave 4
```

### 🎨 The Desktop UX Advantage
* **Serum-Style 3D Plot**: A gorgeous 3D isometric projection showing the stacked single-cycle waveforms of the loaded wavetable file.
* **Glowing Playhead Plane**: A glowing horizontal plane cuts through the 3D stack, moving dynamically as the user adjusts the `Wave Index` slider or when an LFO modulates the wavetable index.
* **Mouse Wave-Drawing**: Allow the user to click and draw custom single-cycle wave shapes directly on the canvas, instantly generating and updating the playhead buffer in the audio thread!

### ⚙️ Deluge Model Parity
* Reads the standard `.wav` files referenced by the Deluge track XML.
* Integrates with the existing `wavetable` oscillator rendering loop inside the DSP voice.

---

## Proposal 4: Precision Sequencer Piano Roll with Velocity Stalks

### 💡 The Concept
The Deluge pad grid is a legendary sequencer interface. However, viewing long note gates, precise micro-timing nudges, or editing note velocities step-by-step is difficult to do without tactile dials.
We propose a **Pop-out Piano Roll Sequencer Overlay** that acts as a precise graphical companion to the main grid.

### 🎨 The Desktop UX Advantage
* **Visual Note Lengths**: Notes are drawn as horizontal bars on a piano key grid, showing exact gate lengths. Drag the right edge of any note bar to adjust its duration.
* **Micro-timing Dragging**: Hold `Alt` and drag note bars left/right to nudge note start times off-grid (micro-timing), allowing organic swing or humanized grooves.
* **Velocity Stalk Editor**: A dedicated panel at the bottom of the piano roll displaying vertical bars ("stalks") representing the velocity of each note. Simply drag the top of a stalk up or down to set precise velocity.
* **MIDI Clip Import/Export**: Drag-and-drop standard MIDI files directly onto the piano roll to convert them into Deluge track clips.

### ⚙️ Deluge Model Parity
* Directly updates the `ClipModel`'s note array, mapping properties directly to XML attributes:
  * Note start step -> `pos`
  * Note duration -> `length`
  * Note velocity -> `velocity`
  * Note micro-timing -> `microtiming`
* Retains 100% parity with the hardware step sequencer playback engine.

---

## Recommendations & Next Steps

1. **(Highly Recommended) Proposal 1: The Modulation Matrix Grid**:
   * *Why*: Synthesis on the Deluge is deeply modulation-driven. Adding this grid instantly demystifies how a sound is programmed, providing a massive UX leap that takes perfect advantage of a desktop grid layout.
2. **Proposal 4: Sequencer Piano Roll**:
   * *Why*: Expands the sequencing capabilities of the app, turning it into a highly capable, portable desktop sequencer companion.
