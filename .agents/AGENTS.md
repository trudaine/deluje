# Antigravity Behavioral Rules

## Pull Request & Copilot Review Loop (Mandatory)
- **Never Push Directly to `main`**: `main` is protected by a GitHub ruleset. Every change — code, tests, scripts, or documentation — must go on a feature branch, open a pull request, and run through the automatic Copilot review loop ([copilot-review-loop.md](../docs/agents/copilot-review-loop.md)).
- **Start and End Sessions Cleanly**: Run `scripts/agent-sync.sh` at the start of every session and before merging any PR ([session-sync.md](../docs/agents/session-sync.md)). End every commit message with an `Agent:` trailer (`Agent: claude`, `Agent: agy`, or `Agent: human`).
- **PR Workflow**:
  1. Branch from `main`: `git checkout -b <branch>`
  2. Run local checks (`./build.sh spotless:apply && ./build.sh test -B -Dgpg.skip=true` for code; `bash scripts/check-doc-links.sh` for docs; `bash -n <script.sh>` for shell scripts).
  3. Commit (with `Agent:` trailer) and push: `git push -u origin <branch>`
  4. Open PR: `gh pr create --base main --head <branch> --title "..." --body "..."`
  5. **Copilot Review Loop**: Copilot reviews automatically on PR creation and on every push (`review_on_push: true`). Read all three places in the review: line comments, the collapsed `<details>Suppressed comments</details>` block, and the overview (`Open (N)` / `Previously missed (N)`).
  6. Reproduce and verify every finding against the Java code and `../DelugeFirmware/src/deluge/` C++ reference before acting. Fix verified issues, push, and iterate until the latest review is clean ([multi-agent-review.md](../docs/agents/multi-agent-review.md)).

## Build Integrity and Test Safety
- **Never Commit or Merge Failing Tests**: Under no circumstances should you merge code that causes tests to fail on `main`. Keeping the build green is a top priority.
- **Handling External/Known Failures**: If a test is failing due to external factors (such as a corrupted hardware reference file, environment issues, or missing resources) but you need to commit the underlying code fix:
  - You **must** explicitly disable or ignore the test (e.g., using `@Disabled` in JUnit 5 or `@Ignore` in JUnit 4) *before* committing.
  - Always add a comment next to the disabled test explaining exactly why it is disabled and what is required to re-enable it.
- **Verify Before Opening/Merging a PR**: Always run the relevant tests locally and verify that the build is green before pushing or merging a pull request.

## Documentation Grounding, Tone, and Build Efficiency
- **Strict Code-Grounding for Documentation**: Never assume, guess, or invent parameters, UI button names, keyboard shortcuts, or operational logic. Every label, menu entry, keyword match, and configuration detail documented in the guidebook *must* be verified directly against the Java source code (using `grep_search` or `view_file`).
- **User-Facing Tone Only**: Keep documentation strictly focused on the end-user. Avoid any developer jargon (such as virtual threads, daemon threads, JNI hooks, or Java Swing class names) in the user manual.
- **Skip Test Builds for Documentation-Only Changes**: If an edit only modifies markdown (`.md`) or documentation files, do **not** run any compiler or test suite commands (`mvn clean test`). Run `bash scripts/check-doc-links.sh` and open a PR for the documentation branch.

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

## Strict Path Hygiene & Zero-Rush Execution Protocol
- **Zero Hardcoded Machine Paths**: Under no circumstances should you ever write or commit hardcoded absolute machine paths (e.g., `/Users/...` or literal home directory strings) in production code, tests, or temporary scripts. All file and resource loading must use strictly relative project paths (`src/main/resources/...`, classloader resources) or documented system properties (e.g., `-Ddeluge.card`).
- **Mandatory Pre-Commit Diff Audit**: Before executing any `git commit` command, you **must** review the full output of `git diff` to verify that zero hardcoded paths, zero temporary debug prints, and zero unverified shortcuts or hacks are included in the staged changes.
- **Dedicated Test-Driven Validation**: When resolving a bug or implementing a parser feature, never rely solely on global regression suites. You **must** create and commit a dedicated, portable unit test in the appropriate test package to permanently guard and isolate the specific behavior.



