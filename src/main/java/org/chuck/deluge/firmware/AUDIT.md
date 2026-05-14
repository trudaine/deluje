# Deluge Firmware Port Audit: Missing Classes & Gaps

This document identifies C++ classes and components from the [original firmware](https://github.com/SynthstromAudible/DelugeFirmware) that do not yet have a bit-accurate equivalent in the Java port.

## 1. DSP & Rendering Gaps

These classes define the core sound of specific Deluge features.

| C++ Class | Category | Status in Java | Impact |
| :--- | :--- | :--- | :--- |
| `DX7Engine / fm_core` | DSP | **Ported** | Bit-accurate FM kernel implemented in `FmCore.java`. |
| `GranularProcessor` | DSP | **Ported** | Basic grain management implemented in `GranularProcessor.java`. |
| `TimeStretcher` | DSP | **Ported** | Crossfading and hop logic implemented in `TimeStretcher.java`. |
| `Stutterer` | DSP | **Ported** | Real-time buffer-based stutter implemented in `Stutterer.java`. |
| `HPLadder` | DSP | **Ported** | High-Pass Transistor Ladder implemented in `HpLadderFilter.java`. |
| `FilterSet` | Orchestration | **Ported** | Full routing and chaining implemented in `FilterSet.java`. |
| `AbsValueFollower` | DSP | **Ported** | Sidechain tracking implemented in `AbsValueFollower.java`. |
| `FFTConfigManager` | DSP | **Ported** | Stub for FFT configuration added in `FFTConfigManager.java`. |

## 2. Model & Modulation Gaps

| C++ Class | Category | Status in Java | Impact |
| :--- | :--- | :--- | :--- |
| `Sidechain` | Modulation | **Ported** | Internal envelope follower implemented in `SideChain.java`. |
| `ScaleMapper` | Logic | **Ported** | Basic scale mapping implemented in `ScaleMapper.java`. |
| `Action / ActionLogger` | Logic | **Ported** | Basic undo/redo logger implemented in `ActionLogger.java`. |
| `Consequence` | Logic | **Ported** | Base class for event-driven updates added in `Consequence.java`. |
| `ClipMinder` | Logic | **Ported** | Stub for clip monitoring added in `ClipMinder.java`. |

## 3. Storage & Sample Management

The Deluge has a highly optimized memory/disk strategy due to its limited 32MB SDRAM.

| C++ Class | Category | Status in Java | Impact |
| :--- | :--- | :--- | :--- |
| `SampleCache` | Memory | **Ported** | Basic structure for sample data blocks in `SampleCache.java`. |
| `MultisampleRange` | Model | **Ported** | Basic mapping for multi-sample instruments in `MultisampleRange.java`. |

## 4. HID & Drivers (Intentional Gaps)

These components are specific to the physical Deluge hardware or its resource constraints and are replaced by standard Java/OS equivalents. However, some "drivers" act as valuable abstraction layers for the UI logic.

| C++ Class | Replacement | Status | Benefit of Porting |
| :--- | :--- | :--- | :--- |
| `Cluster / ClusterQueue`| Java File IO | **Intentional Gap** | None (Obsolete in Java) |
| `MatrixDriver` | `SwingMatrixPanel` | **Partial** | Abstract pad press/release states and route events to firmware UI. |
| `PadLEDs` | `SwingMatrixPanel` | **Partial** | Virtual framebuffer for the 8x16 grid; handles bit-accurate animations and layering. |
| `Encoder / Button` | `SwingMasterFxPanel` / `topBar` | Functionally Equivalent | Input routing for knobs and buttons. |
| `OLED / SevenSegment` | `FirmwareDisplay` (Simulated) | **Ported** | Real-time text/number feedback. |
| `UART / RSPI / SSI` | `rtmidijava` / `ChuckAudio` | Functionally Equivalent | MIDI and Audio low-level IO. |

## 5. Potential UI Abstraction: Virtual Hardware Layer

Porting `MatrixDriver` and `PadLEDs` would create a "Virtual Hardware" abstraction:
1.  **Logical Grid State**: `MatrixDriver` tracks which pads are physically held, allowing for complex multi-button shortcuts already implemented in the firmware.
2.  **Display Framebuffer**: `PadLEDs` provides an RGB array (`image`) that `SwingMatrixPanel` can simply paint, decoupling the "what to show" (firmware logic) from the "how to draw" (Swing).
3.  **Occupancy Masking**: Replicates the `occupancyMask` system where different UI layers "claim" parts of the grid, simplifying complex overlapping views.


## Summary of Ported Items (New)

1.  **`AbsValueFollower`**: Full implementation of the envelope follower for RMS/Peak tracking.
2.  **`ScaleMapper`**: Foundational `NoteSet` and `ScaleChange` logic for high-fidelity scale snapping.
3.  **`ActionLogger`**: Standard stack-based undo/redo mirroring the firmware's command pattern.
4.  **`SampleCache`**: Support for managing large sample datasets in segments.
5.  **`MultisampleRange`**: Logic to handle instruments mapped across multiple WAV files.
