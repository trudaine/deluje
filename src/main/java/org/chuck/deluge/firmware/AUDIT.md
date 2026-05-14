# Deluge Firmware Port Audit: Missing Classes & Gaps

This document identifies C++ classes and components from the [original firmware](https://github.com/SynthstromAudible/DelugeFirmware) that do not yet have a bit-accurate equivalent in the Java port.

## 1. DSP & Rendering Gaps (High Priority)

These classes define the core sound of specific Deluge features.

| C++ Class | Category | Status in Java | Impact |
| :--- | :--- | :--- | :--- |
| `GranularProcessor` | DSP | Missing | **No Granular synthesis**. Java uses basic sample playback only. |
| `TimeStretcher` | DSP | Missing | **No real-time time-stretching**. Audio clips will pitch-shift instead of stretch. |
| `DX7Engine / fm_core` | DSP | Approximate | Java uses a legacy `NativeDx7Voice`. The **firmware's bit-accurate FM kernel** is not ported. |
| `Stutterer` | DSP | Missing | The performance **Stutter effect** is unavailable. |
| `HPLadder` | DSP | Missing | Only the Low-Pass Transistor Ladder is ported. High-pass variant is missing. |
| `FilterSet` | Orchestration | Simplified | Firmware uses a complex class to manage LPF/HPF chaining and parallel routing. Java uses a simpler serial wrapper. |

## 2. Model & Modulation Gaps

| C++ Class | Category | Status in Java | Impact |
| :--- | :--- | :--- | :--- |
| `Sidechain` | Modulation | Simplified | The firmware's **actual sidechain envelope follower** (tracking an input audio signal) is missing. |
| `Action / ActionLogger` | Logic | Approximate | Java uses a basic `UndoRedoStack`. The **firmware's specific command pattern** for history is not replicated. |
| `ScaleMapper` | Logic | Simplified | Complex scale transformations and **user-defined scale logic** are not bit-accurate. |
| `Consequence` | Logic | Missing | The firmware's internal **event-driven parameter notification** system is replaced by direct bridge writes. |

## 3. Storage & Sample Management

The Deluge has a highly optimized memory/disk strategy due to its limited 32MB SDRAM.

| C++ Class | Category | Status in Java | Impact |
| :--- | :--- | :--- | :--- |
| `SampleCache` | Memory | Missing | Java loads entire samples into heap. Hardware **dynamically streams clusters** from SD card. |
| `MultisampleRange` | Model | Missing | No support for **Multi-sample instruments** (mapping different WAVs to different keys). |
| `Cluster / ClusterQueue`| IO | Missing | Java uses standard File IO. Parity with the **priority-based SD loading** is missing. |

## 4. HID & Drivers (Intentional Gaps)

These components are specific to the physical Deluge hardware and are replaced by standard Java/Swing equivalents.

| C++ Class | Replacement | Status |
| :--- | :--- | :--- |
| `MatrixDriver / Pad` | `SwingMatrixPanel` | Functionally Equivalent |
| `Encoder / Button` | `SwingMasterFxPanel` / `topBar` | Functionally Equivalent |
| `OLED / SevenSegment` | `FirmwareDisplay` (Simulated) | Functionally Equivalent |
| `UART / RSPI / SSI` | `rtmidijava` / `ChuckAudio` | Functionally Equivalent |

## 5. Future Work Summary

To achieve **100% Logic Parity**, the next phases should focus on:
1.  **Porting the FM Kernel**: Moving from `NativeDx7Voice` to the firmware's `fm_core.cpp`.
2.  **Multi-sample Mapping**: Implementing the `MultiRange` logic to support complex sample-based instruments.
3.  **Streaming Engine**: Emulating the cluster-based streaming to allow playing projects larger than available RAM.
4.  **Granular & Stretch**: Porting the phase-vocoder and granular grains logic.
