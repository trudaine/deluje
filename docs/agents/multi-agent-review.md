# Multi-Agent Review — How a PR Converges and Stops

This covers a PR reviewed by **more than one agent** (Claude, Gemini, Antigravity, a human) plus
Copilot. Without a stopping rule, multiple reviewers can iterate indefinitely.

## Roles

| Role | Who | What they may do |
|---|---|---|
| **Owner** | Whoever opened the PR | The only one who commits to that branch. Triages every finding, replies, and merges. |
| **Reviewers** | Any other agent or human | Comment. **Never push to someone else's branch** unless the human explicitly hands it over. |
| **Copilot** | `copilot-pull-request-reviewer[bot]` | Automatic per push. Does not read replies. Its output is an input to the owner, not a conversation partner. |

## Every Review Ends with One Verdict Line

Machine-greppable so convergence is visible without reading prose:

```
REVIEW: BLOCK        — must change before merge
REVIEW: NON-BLOCKING — worth doing, do not hold the PR
REVIEW: CLEAR        — nothing outstanding from me
```

**`BLOCK` is reserved.** Use it only for:
- A DSP or XML change that breaks C++ firmware parity or regresses `FidelityScorecardTest`.
- A failing test or broken build (`./build.sh test -B -Dgpg.skip=true` or Spotless).
- Data loss or project/song XML corruption on load or save.
- A check or test that passes while verifying nothing.
- Hardcoded machine paths (`/Users/...`) or leaked secrets.

Style preferences, naming, or "I would have structured it differently" are **`NON-BLOCKING`** — file
an issue and let the PR merge.

## Round Budgets by Scope

| The change touches | Max rounds | Rationale |
|---|---|---|
| `org.deluge.firmware2`, `org.deluge.engine`, `org.deluge.xml`, `org.deluge.project` | **up to 3** | Core DSP, timing, and hardware XML compatibility |
| `org.deluge.model`, `org.deluge.ui`, `org.deluge.midi`, `org.deluge.ableton` | **up to 2** | Sequencer model, Swing UI, and device routing |
| `scripts/`, `tools/`, `.github/workflows/` | **up to 2** | Build, harnesses, and CI gates |
| `docs/`, `*.md`, code comments | **1** | Documentation; file follow-up issues instead of looping |

A round is: owner pushes -> Copilot/reviewers respond -> owner triages.

## The Stopping Rule

A PR is **done** when both hold:

1. Copilot's latest review has **no line comments, an empty suppressed block, and nothing under
   `Open` or `Previously missed` in its overview**, and each finding it listed has been judged
   ([copilot-review-loop.md](copilot-review-loop.md) — all three places read).
2. No reviewer has an unretracted `REVIEW: BLOCK`, and CI checks (`unit-tests`, `docs-lint`) pass.

**Or the round budget above is spent** — whichever comes first. At that point any remaining
`NON-BLOCKING` finding becomes a GitHub issue linked from the PR, and the PR merges. Only a `BLOCK`
holds a PR past its round budget.

## Findings Are Hypotheses Until Verified

Copilot never runs tests or compiles code. Every finding comes from static reading.
- Always verify claims against the Java source, `../DelugeFirmware/src/deluge/` C++ reference, and
  actual test runs.
- When two reviewers disagree about whether something works or exists, **the one who ran a command
  or cited the exact `file:line` wins**. Paste the command and output in the PR.
