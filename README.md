# Deluge Java Workstation (deluje)

Welcome to **Deluge Java Workstation**, a high-fidelity, standalone emulation and software implementation of the Synthstrom Audible Deluge synthesizer and sequencer. 

This repository contains a complete, self-contained Java implementation of the Deluge workstation, featuring a rich Swing-based grid UI, a custom DSP synthesis engine, MIDI/SysEx integration, and comprehensive project serialization. It has been fully decoupled from the ChucK-Java project to serve as a dedicated, portable, and production-ready codebase.

---

## 🚀 Key Features & Technology Stack

- **Runtime**: **JDK 27 (Early-Access)** utilizing advanced features like Project Loom (Virtual Threads) for lightweight concurrency and the **Vector API** (`jdk.incubator.vector`) for high-performance audio DSP.
- **UI Subsystem**: A responsive, pure Swing grid interface mimicking the physical Deluge pad layout, featuring advanced gestures, scrolling, and real-time OLED emulators.
- **Audio DSP Engine**: High-fidelity sound generators (Synth, Drum Kit, MIDI) with unipolar envelopes, tangent high-frequency safety guards, and double-overflow saturation protection.
- **Build & Formatting**: Portable Maven build system with automated formatting verified via Spotless.
- **Integrations**: Multi-channel MIDI mapping, Ableton-style track routing, and SysEx remote control.

---

## 📖 Project Documentation Directory

This repository is heavily documented. Below is a structured directory of all available documentation, guides, blueprints, and audits.

### 1. User Guides & Quick Reference
*   **[Deluge Workstation Guidebook](src/main/resources/docs/DELUGE_GUIDEBOOK.md)**: The comprehensive manual covering the entire workstation, key layouts, grid modes, and operations.
*   **[Advanced Parameters User Guide](docs/deluge_advanced_parameters_user_guide.md)**: A deep dive into modulating and configuring advanced synth and kit parameters.
*   **[MIDI Importer User Guide](docs/deluge_midi_importer_user_guide.md)**: Guide on importing external MIDI files and mapping them to Deluge tracks.
*   **[Quick Commands Reference](docs/deluge_workstation_quick_commands.md)**: A handy cheat-sheet of button combinations and shortcut commands.
*   **[Linux MIDI Configuration Guide](docs/linux_midi_guide.md)**: Setup and troubleshooting guide for MIDI devices on Linux systems.

### 2. System Architecture & Technical Specifications
*   **[Deluge Architecture & Design Spec](DELUGE_DESIGN.md)**: The core design document outlining the UI-to-DSP bridge, threading models, and state synchronization.
*   **[Song XML Specification](SONG_XML_SPEC.md)**: The structural spec for the Deluge XML file format.
*   **[Song XML Schema (XSD)](docs/deluge.xsd)**: The formal XML Schema definition for validating song files.
*   **[Firmware Parity Reference](FIRMWARE_PARITY.md)**: A gap analysis and parity reference comparing this implementation to the native C++ Deluge firmware.
*   **[Hardware Fidelity & DSP Guidelines](HARDWARE_FIDELITY.md)**: Core mathematical and DSP rules (fixed-point arithmetic, saturation guards, envelope mapping) to ensure audio stability and prevent clipping.

### 3. Integration Blueprints & Feature Studies
*   **[Ultimate Integration Blueprint](docs/ultimate_integration_blueprint.md)**: Strategic plan for advanced audio routing, multi-core DSP, and hardware-software hybrid setups.
*   **[Ableton Integration Blueprint](docs/ableton_integration_blueprint.md)**: Blueprint for integrating and mapping Deluge tracks directly into Ableton Live.
*   **[Tune Crafter Integration Study](docs/deluge_tune_crafter_integration_study.md)**: A study exploring integration with external algorithmic composition and generative tools.
*   **[Remote Control Blueprint](docs/remote_control_blueprint.md)**: Protocol and architecture design for controlling the workstation remotely via network/SysEx.

### 4. Subsystem Reviews & Audits
*   **[UI and MIDI Subsystem Review](docs/ui_and_midi_subsystem_review.md)**: A detailed review of the UI event dispatch thread separation and MIDI scheduling stability.
*   **[Web Explorer & MIDI Stability Review](docs/web_explorer_midi_stability_review.md)**: Analysis of browser-based explorers and MIDI latency/jitter.
*   **[Sysex Gap Audit Report](docs/sysex_gap_audit_report.md)**: A gap analysis of SysEx command coverage compared to the hardware specification.
*   **[Synth Presets Report](docs/deluge_synth_presets_report.md)**: A report on preset loading, compatibility, and XML row configurations.
*   **[Synth Configurator UI Review](docs/synth_configurator_ui_review.md)**: An evaluation of the synthesis parameters UI and knob mapping.
*   **[Desktop UI Enhancements Proposals](docs/proposals_desktop_ui_enhancements.md)**: Design ideas and proposals for future desktop-specific UI enhancements.
*   **[Branch Audit Report](docs/deluge_branch_audit_report.md)**: Quality assurance audit of the codebase during the standalone transition.

---

## 🛠️ Building and Running

### Prerequisites
Nothing pre-installed is required when you use the self-contained `build.sh` (below): it provisions
both the JDK and Maven automatically. For a manual build you need:
- **Java JDK 27 (Early-Access)** or higher.
- **Maven 3.9+** — or just use the bundled wrapper (`./mvnw`), no system Maven needed.

### Build & Validate
Compile, format-check, and run all unit, integration, and E2E regression tests.

**Self-contained (recommended — no global installs):** auto-downloads JDK 27-ea (into `./jdk27`,
from Eclipse Adoptium) and Maven (via the wrapper), then builds:
```bash
./build.sh                 # = clean package   (build.bat on Windows)
./build.sh test            # any Maven goals/args pass through
```

**With a system JDK 27**, via the Maven Wrapper (pinned to Maven 3.9.16) or your own Maven:
```bash
./mvnw clean package       # or: mvn clean package
```

### Run the Workstation
To launch the Swing-based workstation UI (`run.sh`/`run.bat` also auto-provision JDK 27-ea):
```bash
mvn exec:java -Dexec.mainClass="org.deluge.ui.SwingDelugeApp"
```
*(You can also configure the virtual SD card directory by passing `-Ddeluge.card=/path/to/your/card`)*
