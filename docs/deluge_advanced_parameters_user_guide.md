# Deluge Advanced Parameters & Hardware Parity User Guide

This guide provides a comprehensive technical manual and user reference for the **96 advanced parameters** in the ChucK-Java Deluge Workstation. It explains how these features function on the physical Deluge hardware, details their underlying native C++ implementation, and documents their XML representation for high-fidelity round-trip serialization.

---

## 1. Arpeggiator & Step Randomizer

The Deluge features one of the most powerful hardware arpeggiators and step-randomizers in the synthesizer world. In addition to standard rate, gate, and octave transpositions, it supports micro-timing variations, chord polyphony, ratcheting, and performance-level step-randomization locks.

### 1.1. Physical Hardware Behavior
*   **Accessing Arp settings**: On the hardware, press the **ARP** button to toggle the arpeggiator. Turn the **select** encoder while pressing **ARP** to access advanced sub-menus.
*   **Step Randomizer**: Turn the **random** encoder to add velocity spread (`velSpread`), octave spread (`octaveSpread`), gate duration spread (`gateSpread`), or ratchet probability.
*   **Locking Random Sequences**: While playing, press the **RANDOM** encoder dial. This captures the active randomized sequence and "locks" the steps. The hardware writes these captured patterns into 16-byte hex-encoded locking arrays (representing step offsets for up to 16 steps) so that the randomized groove plays identically on every loop cycle.

### 1.2. Underlying Hardware Synchronization
Arpeggiator settings are loaded directly from the song XML and synchronized with the hardware synth engine.
*   **Locking Patterns**: Random locks are stored as a sequence of 16 step values.
*   **Hex Encoding**: These values are written to the XML as 32-character hex-encoded strings. The Workstation parses and saves these strings with absolute accuracy, ensuring your saved files are fully compatible with your physical Deluge.

### 1.3. Parameter & XML Specification

| XML Attribute | Description | Value Range | Workstation UI / Model Mapping |
|---|---|---|---|
| `active` | Arpeggiator active toggle | `0` or `1` | Active (Checkbox) |
| `mode` | Sequence direction | `UP`, `DOWN`, `UP_DOWN`, `RANDOM`, `WALK` | Mode (Dropdown) |
| `rate` | Rate speed multiplier | Hex-float (0.25 to 4.0) | Rate (Slider / Text field) |
| `syncLevel` | Clock note-division sync | `0`, `1`, `2`, `4`, `8`, `16`, `32`, `64` | Sync Rate (Combo box) |
| `syncType` | Rhythm sync type | `0` (Normal) or `1` (Triplet) | Sync Mode (Normal / Triplet) |
| `octaves` | Pitch octave span | `1` to `4` | Octaves count (Dropdown) |
| `noteMode` | Chord note selection pattern | `UP`, `DOWN`, `UPDN`, `RAND`, `PLAY`, `PATT` | Note Play Order (Combo box) |
| `octaveMode` | Octave progression style | `UP`, `DOWN`, `UPDN`, `ALT`, `RAND` | Octave Progression (Combo box) |
| `stepRepeat` | Step repetition multiplier | `1` to `8` | Step Repeat (Dropdown) |
| `rhythmIndex` | Silence rhythm pattern | `0` (Flat) to `49` (Preset patterns) | Rhythm pattern selection |
| `seqLength` | Active step grid length | `1` to `16` | Sequence steps count |
| `octaveSpread` | Random octave offset spread | Hex-float (0% to 100%) | Octave Spread (Slider) |
| `gateSpread` | Random gate duration spread | Hex-float (0% to 100%) | Gate Duration Spread (Slider) |
| `velSpread` | Random note velocity spread | Hex-float (0% to 100%) | Velocity Spread (Slider) |
| `ratchetAmount` | Step subdivision trigger | `0` (none) to `4` (Ratchets) | Ratchets multiplier (Dropdown) |
| `noteProbability` | Step playback probability | Hex-float (0% to 100%) | Step Trigger Probability (Slider) |
| `chordPolyphony` | Maximum notes in a chord | `1` to `8` | Chord Polyphony limit |
| `chordProbability` | Chord triggering probability | Hex-float (0% to 100%) | Chord Trigger Probability |
| `chordType` | Preset chord base scaling | `0` to `8` | Chord Type selector |
| `numOctaves` | Secondary octave range span | `1` to `4` | Secondary Octaves count |
| `kitArp` | Enable arp on drum kit slot | `0` or `1` | Kit Arp toggle (Checkbox) |
| `randomizerLock` | Random step loop lock active | `0` or `1` | Random Lock state (Checkbox) |
| `locked*Array` | 16-byte step randomization locks | 32-char hex string | Locked steps sequence |

#### XML Example:
```xml
<arpeggiator active="1" mode="UP_DOWN" rate="0x3F800000" syncLevel="16" syncType="0" octaves="2" noteMode="UP" octaveMode="UP" stepRepeat="1" rhythmIndex="0" seqLength="16" octaveSpread="0x3E800000" gateSpread="0x00000000" velSpread="0x3F000000" ratchetAmount="0" noteProbability="0x3F800000" chordPolyphony="1" chordProbability="0x00000000" chordType="0" numOctaves="2" kitArp="0" randomizerLock="1" lastLockedNoteProb="1" lockedNoteProbArray="FF804000201008040201000000000000"/>
```

---

## 2. Track & Sound-Level MIDI Learned Mappings

While ChucK-Java supports global USB/MIDI CC mappings to control application faders, the physical Deluge allows **song-specific and track-preset-specific MIDI learning**. This saves MIDI controller maps directly inside your song XML.

### 2.1. Physical Hardware Behavior
On the Deluge hardware, to map a MIDI controller knob to an instrument parameter:
1.  Hold the **LEARN** button.
2.  Wiggle the parameter dial on the Deluge (e.g., *LPF Cutoff*).
3.  Turn the physical knob or fader on your external MIDI keyboard/controller.
4.  The screen displays `LN` (Learned), binding that specific CC number and channel to that track parameter. These mappings are saved inside the song XML under the `<midiKnobs>` element.

### 2.2. Hardware MIDI Mapping Parity
Song-specific learned MIDI mappings are parsed from and written to the song XML files. The workstation fully supports absolute faders as well as relative endless dials, wiggling external controllers to map synth and filter parameters in real time.

### 2.3. Parameter & XML Specification

*   `<midiKnobs>`: Container element, containing zero or more `<midiKnob>` tags. If no custom mapping exists, written as `<midiKnobs/>`.
*   `<midiKnob>`: Represents a single learned controller binding.

#### Attributes:
*   `channel`: The incoming MIDI channel (`0` to `15`, or `255` for omni).
*   `ccNumber`: The MIDI Continuous Controller (CC) number (`0` to `127`).
*   `relative`: Sets encoder response (`0` = absolute fader, `1` = relative endless dial).
*   `controlsParam`: The target synthesizer/drum parameter string being controlled (e.g., `"volume"`, `"pan"`, `"lpfFrequency"`).
*   `patchAmountFromSource`: Optional modulation depth modifier.

#### XML Example:
```xml
<midiKnobs>
  <midiKnob channel="0" ccNumber="74" relative="0" controlsParam="lpfFrequency"/>
  <midiKnob channel="0" ccNumber="10" relative="0" controlsParam="pan"/>
</midiKnobs>
```

---

## 3. Grid Column Border Shortcuts (`columnControls`)

The Deluge grid has 8 rows and 16 columns of pads. The vertical columns on the far left and far right borders can be configured as **shortcut strips** to adjust parameters (like note velocity, pitch transpose, or modulation depth) for the currently playing notes.

### 3.1. Physical Hardware Behavior
Users can tap or slide their fingers vertically along the border columns to apply instant velocity accents or pitch bends in real-time. The shortcut assignment is clip-specific and saved inside the `<instrumentClip>` block.

### 3.2. Parameter & XML Specification
*   `<columnControls>`: Container element.
*   `<leftCol>` / `<rightCol>`: Represents the left and right shortcut columns.
    *   `type`: The assigned shortcut function. Common values:
        *   `VELOCITY`: Controls note-on velocity.
        *   `MOD`: Controls modulation depth.
        *   `PITCH`: Controls pitch-bend transpose.
        *   `NONE`: Disables border shortcuts.

#### XML Example:
```xml
<instrumentClip slot="0" name="Lead Synth">
  <columnControls>
    <leftCol type="VELOCITY"/>
    <rightCol type="MOD"/>
  </columnControls>
</instrumentClip>
```

---

## 4. Advanced Sound Engine Parameters

### 4.1. Saturation Distortion
*   **Physical Behavior**: Turn the **distortion** encoder to apply analog-modeled saturation to the sound.
*   **Engine Behavior**: The workstation supports two types of distortion: **Tube Saturation** and **Wavefolder/Fuzz**.
*   **XML Representation**:
    *   `distortionAmount`: The drive amount (Hex-float).
    *   `distortionType`: Set to `0` (Tube) or `1` (Fuzz/Wavefolder).

### 4.2. Filter Types & Slopes
*   **Physical Behavior**: Press and turn the filter encoders to switch filter shapes.
*   **Engine Behavior**: Exposes steepness options. Standard lowpass filters can be run at **12dB/octave** (2-pole) or cascaded to **24dB/octave** (4-pole) for a sharper frequency cut.
*   **XML Representation**:
    *   `filterType`: Selects `LPF`, `HPF`, or `BPF`.
    *   `filterSlope`: Sets slope steepness.
    *   `lpfHpfOrder`: Sets Lowpass/Highpass order routing.

### 4.3. Fine Semitone Pitch Tuning
*   **Physical Behavior**: Detune oscillators slightly to create thick, organic chorus effects.
*   **Engine Behavior**: Written as fine cents detuning offsets.
*   **XML Representation**:
    *   `oscAPitchAdjust` / `oscBPitchAdjust`: Fine pitch adjustments (Hex-floats).

---

## 5. Summary of Song XML Value Formats

To ensure absolute compatibility when round-tripping files, all floating-point parameters (0.0 to 1.0) and decibel/frequency numbers are encoded into a **signed 32-bit hex representation** (unified hex-float format) matching the Deluge hardware's internal structures:

*   **Unipolar Float**: `0.0` (flat) to `1.0` (max) maps to `0x00000000` to `0x3F800000`.
*   **Bipolar Float**: `-1.0` to `1.0` maps to `0xBF800000` to `0x3F800000`.
*   **Frequency (Hz)**: Sound cutoffs are converted to logarithmic values matching the physical hardware's response curve.

---

## 11. Step Iterance & Stutter Performance FX Parity

### Step Iterance & Play Conditions (`StepPropertiesDialog.java`)
Deluge-Java provides 100% C++ hardware parity for conditional step triggers (`Iterance`):
*   **Accessing Step Properties**: Right-click or shift-click a step on the sequencer grid to open **Step Parameter Properties**.
*   **Preset Conditions**: Choose standard multi-cycle play rules from the **Condition** menu (`Always (1 of 1)`, `1st of 2 (1 of 2)`, `2nd of 2 (2 of 2)`, `1st of 4 (1of4)`, `4th of 4 (4of4)`, `1st of 8 (1of8)`, etc.).
*   **Custom Cycle Bitmasks**: Select **Custom (Cycle Bitmask)** to specify a custom loop length (`1 to 8 cycles`) and toggle interactive step boxes (`1 through 8`) to specify exact cycles where the note triggers.

### Track Stutter Modes (`StutterPanel.java` & `TransportController.java`)
Synthesizer tracks support real-time hardware stutter performance modes located under the **STUTTER** tab of the **Track Inspector** or via the OLED menu (**Track Stutter Modes (Quantize / Reverse / Ping-Pong)...**):
*   **Quantize to Grid**: Locks stutter repeat loop boundaries to the musical sequencer grid.
*   **Reversed Playback**: Plays captured audio slices backward during stutter loops.
*   **Ping-Pong Bounce**: Alternates forward and backward playback across consecutive stutter repeats.

---

## 12. Live Step Recording & Exclusive Solo

### Live Step Parameter Lock Recording (`[RECORD]` + Macro Tweaks)
When **Live Record Mode** (`[RECORD]`) is engaged during sequencer playback (`isPlaying && isLiveRecordModeActive`), dragging a macro slider column (`LEVEL`, `PAN`, `FILTER`, `RESONANCE`, `LFO`, `MOD FX`, `DELAY`, `REVERB`, `STUTTER`) writes step parameter locks (`AutomationParam`) directly onto the sequence step currently under the playhead (`G_CURRENT_STEP`).

### Exclusive Solo & Un-Solo All (`Alt-Click Mute Column`)
In Clip View (`ClipGridPanel.java`), **Alt-Clicking** any track's mute pad (`column 16`) performs an **Exclusive Solo**:
*   Unmutes the clicked target track and instantly mutes all other tracks.
*   Alt-Clicking the soloed track a second time **Un-Solos All**, restoring all project tracks to unmuted playback.

