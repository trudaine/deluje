# Antigravity Behavioral Rules

## Build Integrity and Test Safety
- **Never Commit or Push Failing Tests**: Under no circumstances should you commit or push code that causes tests to fail on the main branch. Keeping the build green is a top priority.
- **Handling External/Known Failures**: If a test is failing due to external factors (such as a corrupted hardware reference file, environment issues, or missing resources) but you need to commit the underlying code fix:
  - You **must** explicitly disable or ignore the test (e.g., using `@Disabled` in JUnit 5 or `@Ignore` in JUnit 4) *before* committing.
  - Always add a comment next to the disabled test explaining exactly why it is disabled and what is required to re-enable it.
- **Verify Before Committing**: Always run the relevant tests locally and verify that the build is green before performing or proposing a commit/push.

## Documentation Grounding, Tone, and Build Efficiency
- **Strict Code-Grounding for Documentation**: Never assume, guess, or invent parameters, UI button names, keyboard shortcuts, or operational logic. Every label, menu entry, keyword match, and configuration detail documented in the guidebook *must* be verified directly against the Java source code (using `grep_search` or `view_file`).
- **User-Facing Tone Only**: Keep documentation strictly focused on the end-user. Avoid any developer jargon (such as virtual threads, daemon threads, JNI hooks, or Java Swing class names) in the user manual.
- **Skip Test Builds for Documentation-Only Changes**: If an edit only modifies markdown (`.md`) or documentation files, do **not** run any compiler or test suite commands (`mvn clean test`). Documentation-only changes should be committed and pushed directly.

## DSP Parity & Faithfulness Auditing
- **Adversarial Audit Mindset**: Treat the "line-for-line port" assertion as a claim to verify, not a prior to trust. Assume there are hidden translation bugs (especially signed-vs-unsigned bitwise shifts, loop wrap fractions, scaling shifts like `>>` vs `>>>`, and routing gates) until verified.
- **Java-to-C Side-by-Side Audit**: When working on or reviewing any DSP subsystem class in `org.deluge.firmware2` or `org.deluge.engine`, open the exact C++ source file under `../DelugeFirmware/src/deluge` and read both texts side-by-side. 
- **Scorecard Validation**: Always validate DSP changes against the time-resolved spectral scorecard (`FidelityScorecardTest`) and reset the noise seed. Do not rely solely on passing unit tests or simple RMS/autocorrelation metrics, as they can mask timeline-shifted or timbre-shifted divergences.
- **Strict Code Grounding for Citations**: Always cite the exact C++ source file and line number (e.g. `// C voice.cpp:1056`) inside the Java codebase for any ported logic or parity fix.

## Zero-Fabrication Audit & Citation Rule (Strict Verification Protocol)
- **Zero Hallucination of Method Names and Symbols**: Before writing ANY C++ or Java method name, class name, or symbol into an audit document, markdown report, code comment, or commit message (e.g. `commandToggleTempoBlink()` or `horizontalEncoderButtonAction()`), you **must** execute a literal terminal search (`grep`, `find`) across `../DelugeFirmware/src/deluge/` or `src/main/java/` to verify that the exact character sequence exists verbatim on disk.
- **No Plausible Guessing**: If a terminal search for a suspected C++ or Java function returns zero results, **never** invent or guess a plausible-sounding name to fill in a table cell or explanation. Instead, search by behavior/keyword to find the real handler, or explicitly record that no direct function exists for that action.
- **Mandatory Verification of Shift/Combo Gestures**: Never assume what a button combination (`Shift + [Button]` or `Press + Turn`) does based on what the single button does. Trace the exact `if (b == ... && Buttons::isShiftButtonPressed())` or `buttonState` branch inside `../DelugeFirmware/src/deluge/gui/` before making any claim about sub-menus, confirmations, or shortcuts.

## UI Control Wiring & Interactive Verification Protocol
- **End-to-End Listener Tracing**: Never declare a UI control (button, slider, toggle, keyboard shortcut, dialog control) functional by layout inspection alone. You **must** trace its event handler to verify three distinct links:
  1. The listener is registered and actively receiving events.
  2. The listener mutates the underlying target model (`TrackModel`, `ClipModel`, `ProjectModel`).
  3. The listener triggers the live audio engine/bridge synchronization call (`bridge.pushModelToBridge()`, `syncHighFidelityEngine()`).
- **No Unsubscribed Event Broadcasts**: When using event buses or broadcast shortcuts (such as key press listeners), execute a terminal `grep_search` to verify at least one active subscriber exists and receives the payload.
- **Variable Grid Layout Boundaries**: Never hardcode step/column index thresholds (such as `col >= 16`). Always test UI pad and utility column logic against all supported layout modes (16-step, 24-step, and transposed pitch-mapped views).

## DSP Initialization & XML Default Alignment Protocol
- **C++ Sound Initialization Line-by-Line Audit**: When investigating low preset scorecard scores, line-by-line audit parameter initialization and clip overlay rules in `SongXmlParser.java` / `InstrumentXmlParser.java` against `sound.cpp:146-210` before attributing discrepancies to external recordings or filter tuning.
- **Verbatim C Integer Arithmetic**: Do not substitute saturating operators (`add_saturate`) or custom safety clamping where the reference C++ code uses standard 32-bit wrapping integer arithmetic (`+`, `<<`), unless explicitly required and documented for near-Nyquist audio safety.


