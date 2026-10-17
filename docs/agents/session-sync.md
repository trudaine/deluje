# Working with More Than One Agent — Sync, Handoff, Attribution

Claude Code, Antigravity (`agy`), Copilot, and humans all work in this repo, and they cannot talk
directly to each other. **The repository and its pull requests are the shared channel.**

## 1. Start Every Session with a Sync

```bash
scripts/agent-sync.sh          # last 24 hours; pass a number for a different window (in hours)
```

Read-only. It shows:
1. What landed on `main` and which agent wrote it (`Agent:` commit trailer).
2. What pull requests are open, whether the latest push has been reviewed by Copilot, and Copilot's
   `open=` / `missed=` counts (with titles).
3. Which PRs merged in the window, what Copilot's last review said, and whether that review arrived
   after the merge.
4. Whether the local working tree is clean.

Run `scripts/agent-sync.sh` again **before merging** any PR.
- Claude Code: `/sync` runs it and summarises.
- Other agents: run `scripts/agent-sync.sh` directly.

## 2. Say Who Wrote It: The `Agent:` Trailer

End every commit message with one trailer naming the agent that wrote it:

```
Agent: claude        # Claude Code
Agent: agy           # Antigravity
Agent: human         # written by hand
```

Check recent commit attribution with:
```bash
git log --format='%h %(trailers:key=Agent,valueonly,separator=%x2C) %s' origin/main -20
```

Add the trailer to the PR body as well so the PR names its owner.

## 3. Handing Off When a Session Ends

Uncommitted local work is invisible to other agents. Commit to a branch and push it; open a draft PR
(`gh pr create --draft`) if it is not ready for review yet. Leave state in the PR body:
- **Done**: what is committed, and which checks passed (`./build.sh test -B -Dgpg.skip=true`,
  `FidelityScorecardTest`, `bash scripts/check-doc-links.sh`).
- **Pending**: what remains and why.
- **Next command**: the exact next command to run.
- **Unread**: Copilot reviews not yet triaged.

## 4. Taking Over Someone Else's Work

- Read the PR body and the latest Copilot review first (`gh pr view <N>`, plus the three places in
  [copilot-review-loop.md](copilot-review-loop.md)).
- **Do not push to another agent's open branch** unless the human explicitly asks you to take it
  over.
- Do not merge on a review that arrived before the latest push. Compare the review timestamp with
  the head commit timestamp (`scripts/agent-sync.sh` flags this automatically).
