---
name: sync
description: Start-of-session sync for this repo. Runs scripts/agent-sync.sh and summarises what other agents (Antigravity, Claude, Copilot, or a human) did while you were away, including PRs merged before their last Copilot review was read. Use at the start of a session, after a break, before merging, or when the user says "sync" or "what changed".
---

# /sync

The procedure lives in `docs/agents/session-sync.md`. It is written for every agent,
so it is not duplicated here.

1. Run `scripts/agent-sync.sh` (add a number of hours to change the 24 h window).
2. For each PR marked `<-- READ`, do NOT act on the comment yet. Copilot reads and
   never runs anything (`docs/agents/multi-agent-review.md`, "Findings Are Hypotheses
   Until Verified"). Reproduce or read the Java and C++ code first, then state which
   findings were right and which were wrong.
3. Report to the user, shortest first:
   - what landed, and by which agent (`Agent:` trailer; "none" means an untagged commit),
   - PRs merged before their last review, and whether the late findings were real,
   - what is open, and anything the user has to decide.
4. Say what you did not check. The script reads timestamps; it does not judge whether a
   review was clean.

Read-only. Do not push, merge or comment as part of `/sync`.
