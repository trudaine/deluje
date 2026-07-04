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
