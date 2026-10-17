# The Copilot Review Loop — How Changes Must Land

**Do not push to `main`.** Every change goes on a branch, through a pull request, and through the
Copilot review loop below until it comes back clean. This applies to every agent working in this
repository — Claude, Gemini, Antigravity, or a human.

## GitHub Repository Protection

`main` is protected by GitHub Ruleset `main-pr-and-copilot-review`:
- Direct pushes and force pushes to `main` are blocked.
- Every change must land via a pull request targeting `main`.
- `copilot-pull-request-reviewer[bot]` runs **automatically** when a non-draft PR is opened and on
  every new commit pushed to an open PR (`review_on_push: true`).

## The Loop

```bash
git checkout -b <branch>
# ... make the change, run local checks ...
# For Java code: ./build.sh spotless:apply && ./build.sh test -B -Dgpg.skip=true
# For Markdown docs: bash scripts/check-doc-links.sh
# For shell scripts: bash -n <script.sh>
git commit && git push -u origin <branch>
gh pr create --base main --head <branch> --title "..." --body "..."

# 1. WAIT. The automatic review arrives ~2-5 minutes after the PR is opened or pushed.
# 2. READ ALL THREE PLACES (see below).
# 3. Verify every finding against the Java code, C++ firmware, and tests before acting.
# 4. Fix verified issues, commit, and push to the branch.
# 5. Copilot re-reviews automatically on push (review_on_push: true). If a re-review does not
#    trigger within ~5 minutes, request it explicitly:
gh api -X POST repos/trudaine/deluje/pulls/<N>/requested_reviewers \
  -f 'reviewers[]=copilot-pull-request-reviewer[bot]'
# 6. Repeat from 1 until the latest review shows Open (0) and no Previously missed items,
#    with no line comments and an empty suppressed block.
```

The response to the manual `requested_reviewers` POST prints `"requested_reviewers": []` and looks
like it failed. **It has not.** The review lands about two minutes later. Do not retry it in a loop.

## Read All Three Places in Every Copilot Review

Copilot reports findings across three distinct locations in a review. Reading only line comments
misses suppressed findings and overview-only items.

### 1. Line comments
```bash
N=6
gh api repos/trudaine/deluje/pulls/$N/comments \
  --jq '.[] | "\(.path):\(.line // .original_line): \(.body)"'
```

### 2. The collapsed `<details>Suppressed comments</details>` block
Copilot puts lower-confidence findings in a collapsed `<details>` section of the review body, not as
line comments. The GitHub web UI hides it behind a disclosure triangle, and `gh pr view` does not
show it.

```bash
N=6
gh api repos/trudaine/deluje/pulls/$N/reviews \
  --jq '[.[]|select(.user.login|test("copilot";"i"))]|.[-1].body' \
  | sed -n '/Suppressed/,/<\/details>/p'
```

### 3. The overview (`Open (N)` / `N open issues` and `Previously missed (N)`)
Every review body starts with an overview (`<!-- ccr-overview-v2 -->`) that lists findings by title
under `Open (N)` (or `N open issue(s)`), `Resolved since last review`, and `Previously missed (N)`.
Items under *Previously missed* have **no line comment at all**, so reading only line comments and
the suppressed block will not show them.

```bash
N=6
gh api repos/trudaine/deluje/pulls/$N/reviews \
  --jq '[.[]|select(.user.login|test("copilot";"i"))]|.[-1].body' \
  | grep -E 'Approval recommended|Changes recommended|Needs a closer look|open issues?|Open \(|Previously missed \('
```

`scripts/agent-sync.sh` prints these counts and titles for every open PR and every PR merged in the
window.

## Verify Before You Act

Copilot reads source code and **never executes anything**. It can misread fixed-point shifts, C++
firmware semantics, or test setup, and state wrong claims confidently.

- **Reproduce each finding** against the Java code, `../DelugeFirmware/src/deluge/` C++ reference,
  and unit tests before changing anything.
- Say plainly in the PR which findings were right and fixed, and which were false positives (with
  the file/line or command output proving why).
- **An errored review is not a clean review.** When the service fails it still posts
  `state: COMMENTED` with zero comments. Read the body: if it says *"Copilot encountered an error
  and was unable to review this pull request"*, re-request the review.

## Things That Look Broken and Are Not

- **No reply to comments.** Copilot does not converse. Replying to its review thread gets no
  response; only a push (or `requested_reviewers` POST) produces another pass.
- **`required_approving_review_count: 0` on the ruleset.** Copilot posts `state: COMMENTED`, never
  `APPROVED`. The ruleset blocks direct pushes to `main` and requires a PR, while keeping required
  human approvals at `0` so the PR owner can merge once the Copilot review loop is clean and CI
  checks pass.
