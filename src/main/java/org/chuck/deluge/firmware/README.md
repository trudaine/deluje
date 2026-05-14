# Deluge Firmware Java Port

This directory contains a high-fidelity Java port of the original [Synthstrom Deluge Firmware](https://github.com/SynthstromAudible/DelugeFirmware).

## Motivation

The existing Java implementation in `org.chuck.deluge.engine` used floating-point approximations and different algorithms (like ZDF for filters) that resulted in a sound significantly different from the original hardware.

This port (`org.chuck.deluge.firmware`) aims for **bit-for-bit parity** by:
1. Replicating the exact **fixed-point arithmetic** (`q31_t`) used on the Deluge's ARM Cortex-M architecture.
2. Using the original **lookup tables** for oscillators, envelopes, and filters.
3. Porting the logic **line-by-line** from the original C++ source.

## Mapping Table

| C++ Class / File | Java Class | Status | Notes |
|------------------|------------|--------|-------|
| `fixedpoint.h` | `Q31.java` | Ported | Core math macros (`multiply_32x32_rshift32`, etc.) |
| `functions.cpp` | `FirmwareUtils.java` | Ported | Math utilities (`instantTan`, `interpolateTable`, etc.) |
| `lookuptables.cpp` | `LookupTables.java` | Ported | Crucial tables for sound shaping. |
| `oscillator.cpp` | `Oscillator.java` | Ported | Render oscillators using Vector API. |
| `basic_waves.cpp` | `BasicWaves.java` | Ported | High-efficiency wave rendering. |
| `sine_osc.cpp` | `SineOsc.java` | Ported | Optimized sine and FM rendering. |
| `audio_engine.cpp`| `FirmwareAudioEngine.java`| Ported | Master rendering loop and windowing. |
| `sound.cpp` | `FirmwareSound.java` | Ported | Voice allocation and global modulation. |
| `voice.cpp` | `FirmwareVoice.java` | Ported | Bit-accurate single-note signal path. |
| `wave_table.cpp` | `WaveTable.java` | Ported | High-fidelity wavetable rendering with sinc interpolation. |
| `wavetable_generator.cpp`| `WavetableGenerator.java`| Ported | FFT-based band generation for arbitrary cycles. |
| `svf.cpp` | `SVFilter.java` | Ported | Fixed-point State Variable Filter. |
| `lpladder.cpp` | `LpLadderFilter.java` | Ported | 12dB/24dB Transistor ladder with oversampling. |
| `freeverb.cpp` | `Freeverb.java` | Ported | Implementation of the Freeverb algorithm. |
| `delay.cpp` | `Delay.java` | Ported | Delay logic including ping-pong and resampling. |
| `rms_feedback.cpp`| `RMSFeedbackCompressor.java`| Ported | Master and per-clip compressor. |
| `envelope.cpp` | `Envelope.java` | Ported | Exponential ADSR state machine. |
| `song.h / .cpp` | `Song.java` | Ported | Foundational sequencer structure and tick processing. |
| `arrangement.cpp` | `Arrangement.java` | Ported | Linear timeline management and overlapping clip instances. |
| `clip_instance.h` | `ClipInstance.java` | Ported | Container for clips on the arrangement timeline. |
| `clip.h / .cpp` | `Clip.java` | Ported | Base clip logic and loop/ping-pong wrapping. |
| `note_row.h` | `NoteRow.java` | Ported | Note sequence data and triggering. |
| `playback_handler.cpp` | `PlaybackHandler.java`| Ported | Master clock and tick coordination. |
| `auto_param.cpp` | `AutoParam.java` | Ported | Automation node search and interpolation. |
| `param_manager.cpp` | `ParamManager.java` | Ported | Management of automated parameter sets. |
| `note.h` | `Note.java` | Ported | Note properties (velocity, probability, iterance). |
| `arpeggiator.cpp` | `Arpeggiator.java` | Ported | Complex rhythms, ratcheting and probability. |
| `lfo.cpp` | `LFO.java` | Ported | Warbler, Random Walk and synced waveforms. |

## Non-Ported Items

- **UI / LED Hardware**: We already have a strong **Swing-based UI** in `org.chuck.deluge.ui`. Porting the original hardware's LED/7-segment display logic is unnecessary.
- **MIDI Hardware**: We rely on the `rtmidijava` library for robust MIDI I/O. Original hardware MIDI driver code is not ported.
- **Hardware-Specific Drivers**: ADC, I2S, Flash SPI, and Task Scheduler code are skipped in favor of standard JVM/OS capabilities and the existing ChucK-Java runtime.

## MIDI & Integration Status

- **`rtmidijava` Parity**: The library supports raw byte access and we have now achieved **100% feature parity**, including multi-channel **MPE** (Timbre, Pressure, per-channel Pitch Bend) and **MIDI Clock Sync** (In/Out) via the updated `RtMidiInputRouter.java`.
- **XML Model Playback**:
    - [x] **XML Parsing**: The existing `DelugeXmlParser` correctly loads Synth, Kit, and Song files into Java models.
    - [x] **Firmware Core**: The bit-accurate DSP and Sequencer logic is ready.
    - [x] **Glue Layer**: `FirmwareFactory.java` provides a full mapping between the XML string-based parameters and the `FirmwareSound` / `Patcher` constants to accurately play the loaded sounds.
    - [x] **Real-time Recording**: The `RtMidiInputRouter` is now wired to live-record incoming MIDI notes and CCs directly into the firmware `NoteRow` and `AutoParam` structures when recording is armed.

## Integration Progress

- [x] **Hi-Fi Mode Toggle**: Added `G_HI_FI_MODE` global and `--hifi` CLI flag.
- [x] **UI Integration**: Added "Hi-Fi" checkbox in `SwingMasterFxPanel`.
- [x] **DSP Switching**: `SwitchableFilter`, `SwitchableCompressor`, `SwitchableAdsr`, and `FirmwareReverb` now dynamically swap implementations based on user preference.
- [x] **Bit-Accurate Core**: The engine now optionally uses the exact `q31_t` math ported from the hardware.
- [x] **Modulation Matrix**: Ported `Patcher` and `PatchCableSet` for authentic parameter routing.
- [x] **Global FX**: Integrated master compressor, reverb, and delay into the `FirmwareAudioEngine` bus.
- [x] **Oscillator Sync & Cross-Mod**: Implemented sample-accurate phase resetting and FM modulation.
- [x] **Wavetable Cycle Spanning**: Completed the boundary crossing math in `WaveTable.java` for smooth position modulation.
- [x] **Arpeggiator & LFOs**: Completed porting of full arpeggiator (rhythms, ratcheting, probabilities) and LFO logic.

## Current Status

The core engine (DSP, Sequencer, Modulation Matrix, Wavetables) is now bit-accurate to the firmware. The system is able to:
1. Load a standard Deluge XML ProjectModel.
2. Convert it into a firmware-equivalent Song and FirmwareSound (via FirmwareFactory).
3. Execute bit-accurate rendering with fixed-point math and sinc interpolation.
4. Live-record MIDI and MPE data directly into firmware sequence structures.
