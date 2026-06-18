# Deluge TuneCrafter: Audio/MIDI to Song XML Integration Study

This report provides a detailed architectural audit, mathematical deconstruction, and gap analysis of the `deluge-tune-crafter` Python codebase. It outlines a high-fidelity integration blueprint to bring native, interactive **Audio-to-MIDI transcription** and **MIDI-to-Song compilation** directly into the ChucK-Java Desktop Workstation.

---

## 1. Executive Summary

The `deluge-tune-crafter` utility is a Python-based pipeline that accomplishes two key tasks:
1.  **Audio-to-MIDI Transcription**: Converts raw audio files (WAV, MP3, FLAC, etc.) into standard MIDI files using Spotify's neural-network-based `basic-pitch` model.
2.  **MIDI-to-Song XML Compilation**: Converts standard MIDI files into fully structured Synthstrom Deluge song XML files, allowing users to import external arrangements directly onto their SD cards.

By reverse-engineering the Python tool's sequence-encoding math, we can build a **pure Java native MIDI compiler** directly into our desktop workstation. This will allow users to drag-and-drop any `.mid` file and immediately open, visualize, play, and edit it on our sequencer grid. Furthermore, we can bridge their local Python environment to run the neural audio transcriber in the background, providing a seamless, one-click **"Audio to Deluge Sequencer"** workflow.

---

## 2. Forensic Code Analysis & Pipeline Deconstruction

The `deluge-tune-crafter` pipeline operates via three primary modules:

```
[Audio Input] ──> (basic_pitch_processor.py) ──> [MIDI Output]
                                                        │
                                                        ▼
[Song XML]   <── (xml_injector.py)   <── (midi_converter.py)
```

### 2.1 Audio-to-MIDI Transcription (`basic_pitch_processor.py`)
*   Uses Spotify's `basic_pitch` neural network (via TensorFlow, ONNX, or CoreML).
*   Invokes `predict_and_save()` with an onset threshold of `0.5`, frame threshold of `0.3`, and minimum note length of `58` milliseconds.
*   Outputs a polyphonic, single-track MIDI file (`*_basic_pitch.mid`) preserving note pitches, velocities, start times, and durations.

### 2.2 MIDI-to-Clip Translation (`midi_converter.py`)
*   Loads the MIDI file using the `pretty_midi` library.
*   **Time Normalization**: Extracts the MIDI PPQ (resolution) and maps all note timings onto the Deluge's native **48 PPQ** time grid.
*   **Sequence Compilation**: Organizes notes by pitch, sorts them chronologically, and compiles each note event into a **22-character hexadecimal block** representing start position, duration, velocity, lift, and CC values.
*   **Data Structures**: Produces a list of track clip models containing note row maps and track lengths.

### 2.3 XML Injection (`xml_injector.py`)
*   Loads a baseline empty song file (`base.XML`).
*   Locates the `<sessionClips>` element and extracts the first `<instrumentClip>` to act as a deep-copy template.
*   Clears existing clips and, for each MIDI track, clones the template.
*   **Randomization**: Randomly assigns a preset name (from a list of 16 hardcoded presets like `073 Piano` or `006 Vaporwave Bass`) and a unique `colourOffset` (between -63 and 63) to each track.
*   **Injection**: Injects the compiled `<noteRow>` nodes into the clip and updates the clip's `length` attribute in destination ticks.

---

## 3. The Mathematics of Deluge Sequence Encoding

The core discovery of this study is the exact binary layout of the Deluge sequencer's note data. 

### 3.1 48 PPQ Time-Scaling Math
The Deluge sequencer runs at a fixed resolution of **48 Pulses Per Quarter note (PPQ)**. To convert any input MIDI time to the Deluge grid, the tick position $T_{\text{source}}$ must be scaled and rounded to the nearest integer:

$$T_{\text{deluge}} = \text{round}\left( \frac{T_{\text{source}} \times 48}{\text{PPQ}_{\text{source}}} \right)$$

*   *Example*: In a standard MIDI file with $960$ PPQ, a note starting at tick $480$ (an eighth note) is converted to Deluge tick $24$:
    $$T_{\text{deluge}} = \text{round}\left( \frac{480 \times 48}{960} \right) = 24$$

### 3.2 22-Character Hex Note Block Format
Within a `<noteRow>` element, the `noteDataWithLift` attribute is a continuous string starting with `0x`, followed by concatenated **22-character hexadecimal blocks** (11 bytes). Each block represents a single note event:

$$\text{Block} = \underbrace{[\text{Start Tick}]}_{8\text{ chars}} \cdot \underbrace{[\text{Duration}]}_{8\text{ chars}} \cdot \underbrace{[\text{Velocity}]}_{2\text{ chars}} \cdot \underbrace{[\text{Lift}]}_{2\text{ chars}} \cdot \underbrace{[\text{CC}]}_{2\text{ chars}}$$

#### **Binary Fields Breakdown:**
1.  **Start Tick** (8 Chars / 32-bit Integer): The absolute start position of the note in 48 PPQ ticks from the beginning of the clip.
    *   *Hex Representation*: `00000018` represents tick $24$.
2.  **Duration** (8 Chars / 32-bit Integer): The duration of the note in 48 PPQ ticks.
    *   *Hex Representation*: `00000030` represents a duration of $48$ ticks (one quarter note).
3.  **Velocity** (2 Chars / 8-bit Integer): The note-on velocity, mapped from MIDI $0\text{–}127$ directly to hex $00\text{–}7F$.
    *   *Hex Representation*: `5F` represents a velocity of $95$.
4.  **Lift** (2 Chars / 8-bit Integer): The note-off release velocity, typically set to a standard value of `40` (64 decimal).
5.  **CC / Flags** (2 Chars / 8-bit Integer): Control flags, typically set to `14` (20 decimal) for standard internal synth notes.

#### **Full Block Example:**
A quarter note starting at tick $0$ with velocity $95$ is encoded as:
*   Start: `00000000`
*   Duration: `00000030`
*   Velocity: `5F`
*   Lift: `40`
*   CC: `14`
*   **Resulting Block**: `00000000000000305F4014`

---

## 4. Key Architectural Gaps & Limitations

While the Python tool works as a basic script, it suffers from several severe workflow limitations:

1.  **Blind Preset Randomization**: Tracks are randomly assigned presets from a hardcoded list. If a MIDI file has a bass track and a piano track, they might end up swapped or mapped to random synth patches.
2.  **Static Template Reliance**: The script depends on a static `base.XML` file. If this file is missing, the tool fails. It cannot inject tracks into an *existing* song or use custom user songs as templates.
3.  **No Polyphonic Voice Separation**: The neural audio transcriber output is mixed into a single polyphonic track. If a song has a simultaneous bassline and lead melody, they are lumped into one track, playing on the same synthesizer.
4.  **Lack of Real-Time Auditioning**: There is no way to hear or edit the converted notes. Users must copy the XML to an SD card, load it on the hardware Deluge, and play it. If there were transcription errors, the entire cycle must be repeated.

---

## 5. High-Fidelity Java Integration Blueprint

We can eliminate all these gaps by building a native, interactive **Audio/MIDI Import Suite** directly inside our desktop workstation!

```
+───────────────────────────────────────────────────────────────+
|  MIDI IMPORT WIZARD: [ My_Song.mid ]                          |
+───────────────────────────────────────────────────────────────+
|  Track 1 (Bass)   ──> Map to: [ SYNTHS / 001 Saw Bass    ] 🎨 |
|  Track 2 (Melody) ──> Map to: [ SYNTHS / 073 Piano       ] 🎨 |
|  Track 3 (Drums)  ──> Map to: [ KITS   / 808 Classic Kit ] 🎨 |
|                                                               |
|  [ ] Split Polyphonic Track at Pitch: [ C3 ] (Bass / Lead)    |
+───────────────────────────────────────────────────────────────+
|  [   Cancel   ]                               [   Import   ]  |
+───────────────────────────────────────────────────────────────+
```

### 5.1 Native MIDI Import Wizard GUI (`SwingMidiImportDialog.java`)
We will create a beautiful, interactive wizard dialog:
*   **Track Mapping Deck**: Reads the input MIDI file and displays a list of all detected MIDI tracks and channels.
*   **Dynamic Preset Selector**: For each track, the user can explicitly select which **Synth Preset** or **Drum Kit** from their active Deluge library it should map to, using our existing library database!
*   **Color Customization**: Allows the user to select the grid color for each track's pads.
*   **Direct Sequencer Injection**: Once confirmed, the Java compiler compiles the note rows and injects them directly into the active editor project. The notes immediately appear on the workstation's grid, ready to be played through our high-fidelity synthesis engine and edited visually!

### 5.2 Intelligent Pitch Splitter (Zone Splitting)
To solve the single-track limitation of neural audio transcription, we will implement an **Intelligent Pitch Splitter**:
*   If enabled, the importer analyzes a track's pitch distribution.
*   Notes below a user-defined threshold (e.g., $C_3$ / MIDI Note 60) are compiled into a **Bass Track** (mapped to a bass synth).
*   Notes at or above the threshold are compiled into a **Lead Track** (mapped to a piano or lead synth).

### 5.3 Asynchronous Python Bridge
To support audio-to-MIDI transcription:
*   We will provide an "Import Audio File..." option.
*   The Java app will search for a local Python installation containing the `basic-pitch` package.
*   It will run the transcription in the background using a Java `ProcessBuilder`, capturing output logs.
*   A sleek, native Swing progress bar will show the transcription progress. Once finished, the generated MIDI is automatically loaded into our native **MIDI Import Wizard**!

---

## 6. Implementation Plan & Package Structure

We can implement this suite cleanly inside the `deluge` module using the following classes:

### 6.1 `org.chuck.deluge.midi` (Midi Compiler Package)
*   `MidiToDelugeCompiler.java`: Main compiler class. Reads a MIDI file, performs the 48 PPQ scaling, groups notes by pitch, and encodes them into the 22-character hexadecimal format.
*   `MidiTrackModel.java`: A lightweight model representing parsed MIDI tracks (name, channel, notes, length).

### 6.2 `org.chuck.deluge.ui` (UI Components Package)
*   `SwingMidiImportDialog.java`: The interactive wizard dialog showing track lists, preset mappings, color pickers, and pitch-splitting check boxes.
*   `SwingAudioTranscribeDialog.java`: The background process runner dialog. Executes the Python transcriber, displays logs, and shows a real-time progress bar.

---

## 7. Conclusion

By deconstructing the `deluge-tune-crafter` Python scripts, we have unlocked the exact specifications of the Deluge's note sequence encoding. Integrating a native **MIDI Compiler** and **Audio Transcription Bridge** into our ChucK-Java Workstation is highly feasible, architecturally clean, and represents a **massive workflow upgrade** for Deluge musicians. It turns a clunky, blind command-line conversion utility into a professional, visual, and high-fidelity desktop composition tool.
