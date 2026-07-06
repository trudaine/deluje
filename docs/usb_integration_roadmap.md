# USB Integration Roadmap: Deluge Firmware & Workstation Client

This document outlines the strategic roadmap for expanding the USB integration between the physical Deluge hardware and the Java Workstation client. With the core USB MIDI + CDC Serial layer now fully operational and sync-capable, we propose the following phases for future development.

---

## Technical Feasibility & Prioritization Analysis

### Option 4: USB Audio Streaming (UAC2)
* **Our Assessment**: **The Ultimate Community Request (Highest Value), but High Complexity.**
* **Why it's the holy grail**: Direct digital audio streaming from the Deluge into a DAW over USB (similar to Elektron's Overbridge) eliminates the need for external audio interfaces and analog cabling.
* **Technical Challenges & Worries**:
  * **Clock Drift**: The host computer's audio clock and the Deluge's internal DAC/codec clock will drift. To prevent audible pops, clicks, or dropouts, we must implement adaptive sample-rate conversion or asynchronous feedback endpoints (adjusting packet sizes based on SOF tokens).
  * **Isochronous DMA**: The custom Renesas RZ/A1 driver (`rusb1`) in the TinyUSB stack needs extension to support Isochronous endpoints. Isochronous transfers do not have hardware retransmissions, making buffer management and DMA alignment extremely sensitive.
  * **CPU Scheduling Jitter**: Streaming constant audio packets requires high-priority interrupts. This can clash with SD card read/write routines and synthesis loops, leading to buffer underruns.

### Alternative Option: Bidirectional Project/File Transfer
* **Our Assessment**: **Best Immediate Candidate (High Value, Low Complexity) - Phase 1 Completed.**
* **Why it's a great first step**: It completely removes the need for physical SD card ejection, which is a frequent workflow bottleneck for backup and song transfer.
* **Technical Simplicity**:
  * Uses the existing CDC Virtual Serial Port.
  * Low risk: Does not require real-time clock synchronization or high-priority interrupts (runs as a background task).

---

## Proposed Roadmap

```mermaid
graph TD
    A["Phase 1: Project & File Transfer (USB SD card)"] -->|Completed / CDC Serial| B["Phase 2: Visual Preset & Envelope Editor"]
    B -->|Completed / CDC Serial| C["Phase 3: Visual Pad Matrix Mirroring"]
    C -->|Medium Risk / CDC Serial| D["Phase 4: USB Audio Streaming (UAC2)"]
    D -->|High Risk / Isochronous DMA| E["Completed: Ultimate Hardware Integration"]
```

### Phase 1: Bidirectional Project & File Transfer [COMPLETED]
* **Description**: Transfer XML song files, custom synth presets, and WAV samples directly to and from the Deluge SD card over USB CDC Serial.
* **Implementation Details**:
  * **C++**: Implemented `processIncomingCdcData` and `processOutgoingFileTransfer` in [usb_sync.cpp](../../DelugeFirmware/src/deluge/io/usb/usb_sync.cpp).
    * `0x02` (Request Directory Listing) $\rightarrow$ Returns `0x03` with entries list.
    * `0x04` (Request File Read) $\rightarrow$ Streams file chunks in `0x05` (512-byte blocks) sequentially, yielding to other threads to avoid blocking CPU/audio.
  * **Java**: Implemented response parsing and listeners in [DelugeUsbSyncService.java](../src/main/java/org/deluge/usb/DelugeUsbSyncService.java).
* **How to run the Diagnostic Utility**:
  1. Flash the updated firmware on the Deluge. Ensure `USBS` settings option is `ON`.
  2. Connect the Deluge via USB.
  3. Run the Java class **[HardwareUsbFileTransferDiagnostic.java](../src/test/java/org/deluge/usb/HardwareUsbFileTransferDiagnostic.java)**.
  4. The tool will connect, request the `"/SONGS"` folder, print the list of song files, and automatically download the first XML song file, saving it as `downloaded_song.xml` locally.

### Phase 2: Interactive Preset & Envelope Editor [COMPLETED]
* **Description**: Read and write Deluge synthesizer preset parameters (like filter resonance, LPF morph, ADSR envelope stages, oscillator volumes) in real-time over the CDC Serial port.
* **Implementation Details**:
  * **C++**: Added serial packet commands in [usb_sync.cpp](../../DelugeFirmware/src/deluge/io/usb/usb_sync.cpp):
    * `0x09` (Write Parameter): Resolves parameter kind and ID, and applies value to the active output instrument in the model stack.
    * `0x0A` (Read Parameter): Retrieves the parameter's current value and returns it in a `0x0B` packet.
  * **Java**: Implemented senders and listeners in [DelugeUsbSyncService.java](../src/main/java/org/deluge/usb/DelugeUsbSyncService.java).
* **How to run the Diagnostic Utility**:
  1. Ensure a Synth clip is active on the Deluge.
  2. Run the Java class **[HardwareUsbParameterDiagnostic.java](../src/test/java/org/deluge/usb/HardwareUsbParameterDiagnostic.java)**.
  3. The tool will read the active LPF Resonance, write a new value, read it back to verify, and output the result.

### Phase 3: Visual Pad Matrix Mirroring & Remote Control
* **Description**: Render an exact 8x16 grid interactive mirror of the Deluge pads in the Workstation UI. Clicking virtual pads triggers launch/mute states on hardware, and physical pad presses illuminate the UI in real-time.
* **Implementation Plan**:
  * **C++**: Stream coordinate and RGB color data whenever matrix states change.
  * **Java**: Draw the grid canvas and send click coordinates back to hardware.

### Phase 4: USB Audio Streaming (UAC2)
* **Description**: Expose the Deluge as a class-compliant USB Audio Interface.
* **Implementation Plan**:
  * **C++**: Update `usb_descriptors.cpp` to declare a USB Audio Class 2.0 interface. Stream the final stereo render buffer over asynchronous ISO endpoints, matching host sample rate.
  * **Java/Host**: The workstation client (or any DAW) can select "Deluge Audio" as a digital input source.

---

## Additional QoL & Integration Proposals

### 1. Computer QWERTY Keyboard Naming Helper
* **Description**: Instantly name songs, presets, or samples using your computer keyboard.
* **Concept**:
  * When the Deluge enters a text-input dialogue (e.g. Save preset), it notifies the host over serial.
  * Typing on the computer keyboard transmits characters directly to the Deluge, bypassing physical encoder scrolling.
* **Complexity**: **Low.**

### 2. Over-The-Air (OTA) USB Firmware Updates
* **Description**: Flash new community firmware versions directly over USB without ejecting the SD card.
* **Concept**:
  * Workstation downloads the latest community firmware `.bin` from GitHub.
  * Writes it to the Deluge SD card root over USB CDC Serial (Phase 1).
  * Sends a command to reboot the Deluge, triggering the bootloader's self-update sequence.
* **Complexity**: **Medium.**

### 3. Integrated Preset & Sample Librarian
* **Description**: Desktop organizer to drag-and-drop presets, clean up unused samples, and audition files remotely.
* **Concept**:
  * Built entirely on top of Phase 1 file-transfer protocols.
* **Complexity**: **Medium.**

### 4. Real-time USB Oscilloscope & Spectrogram
* **Description**: Render a real-time waveform visualizer of active synth voices on the computer screen.
* **Concept**:
  * Stream low-resolution audio peak and envelope levels (50-100 fps) over CDC serial.
* **Complexity**: **Medium.**


