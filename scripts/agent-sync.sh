#!/usr/bin/env bash
# Where does the repo stand? Run this at the start of every agent session, and
# before merging anything. Read-only: it fetches and reads, never pushes, merges
# or comments.
#
#   scripts/agent-sync.sh [HOURS]      # default 24
#
# It answers four questions, from the live repo rather than from memory:
#   1. What landed on main, and which agent wrote it (the `Agent:` trailer)?
#   2. What is open, and has the latest push been reviewed? How many findings
#      does Copilot still list as open on it?
#   3. For each PR merged in the window: what did Copilot's LAST review list as
#      open, and did that review arrive after the merge?
#      (docs/agents/multi-agent-review.md: a PR is done only when the LATEST
#      Copilot review has nothing open.)
#   4. Is the working tree yours to use?
#
# It reports what it read. An empty section means GitHub answered "nothing", and a
# failed query prints NOT VERIFIED and makes the script exit 1 — see unverified().
set -uo pipefail

HOURS="${1:-24}"
case "$HOURS" in '' | *[!0-9]*) echo "usage: $0 [HOURS]" >&2; exit 2 ;; esac

command -v gh >/dev/null || { echo "gh is required" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

# GNU date first, BSD/macOS fallback.
SINCE="$(date -u -d "$HOURS hours ago" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-"${HOURS}"H +%Y-%m-%dT%H:%M:%SZ)"
REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)" || { echo "gh cannot read this repo" >&2; exit 2; }

# A query that FAILED is not a query that found NOTHING. Every gh call below is
# captured and checked; a failure prints NOT VERIFIED, is counted, and makes the
# script exit 1, so "(none open)" can only mean GitHub said there were none.
UNVERIFIED=0
unverified() {
  echo "NOT VERIFIED: $1 — the query failed, so this says nothing about whether there is anything to see"
  UNVERIFIED=$((UNVERIFIED + 1))
}

# gh pr list returns at most --limit rows. If we get exactly that many the list may
# be cut off, which is a failed query for our purposes, not a complete one.
LIMIT=200
truncated() { # $1 = label, $2 = JSON array
  if [ "$(jq 'length' <<<"$2")" -ge "$LIMIT" ]; then
    unverified "$1 (hit the limit of $LIMIT, so the list may be incomplete)"
  fi
}

# Copilot reports findings in THREE places, and line comments are only one:
#   1. line comments            gh api repos/R/pulls/N/comments
#   2. a collapsed "Suppressed comments" block in the review body
#   3. the overview in the review body: "Open (N)" / "N open issue(s)" and
#      "Previously missed (N)". "Previously missed" items have NO line comment.
# Counting line comments alone can report a review as clean while its body
# carries an open or previously missed finding. So read the counts from the body.
review_counts() { # $1 = one review as JSON -> "OPEN MISSED"
  jq -r '((.body | capture("(Open \\((?<a>[0-9]+)\\)|(?<b>[0-9]+) open issues?)") | (.a // .b))? // "0") + " "
       + ((.body | capture("(Previously missed \\((?<a>[0-9]+)\\)|(?<b>[0-9]+) previously missed issues?)") | (.a // .b))? // "0")' <<<"$1"
}
review_titles() { # $1 = one review as JSON: titles of the OPEN and PREVIOUSLY MISSED items only
  # The body lists Open ("Open (N)" or "N open issue(s)"), then "Resolved since last review",
  # then "Previously missed" ("Previously missed (N)" or "N previously missed issue(s)").
  # Resolved items are not open, so skip that section.
  jq -r '.body' <<<"$1" |
    awk '/<strong>([0-9]+ )?[Oo]pen( issues?| *\()/ {sec="o"} /Resolved since last review/ {sec="r"} /[Pp]reviously missed/ {sec="m"}
         /<strong>What changed|<strong>Walkthrough/ {sec="x"} sec=="o" || sec=="m" {print}' |
    { grep -oE '\[[^]]{6,160}\]\((https://github\.com/[^)]+)?#discussion|</picture> [^<]{6,160}</summary>' || true; } |
    sed -E 's/^\[//; s/\]\((https:\/\/github\.com\/[^)]+)?#discussion$//; s/^<\/picture> //; s/<\/summary>$//' |
    awk '!seen[$0]++' | head -12 | sed 's/^/      - /'
}
# Last Copilot review in a JSON array of reviews (oldest first, as GitHub returns them).
last_review() { jq -c 'last // empty' <<<"$1"; }
# Overview headline (e.g. "### 🔵 Needs a closer look" or "### 🟡 Changes recommended")
# when Copilot raises prose concerns without formatting them as "Open (N)".
review_headline() {
  jq -r '.body // ""' <<<"$1" |
    grep -E '^### ' |
    grep -vE 'Approval recommended|Reviewed|No issues|Looks good' |
    head -n1 |
    sed -E 's/^### +//' || true
}

git fetch -q origin || unverified "git fetch (the commit list below may be stale)"

echo "== Sync as of $(date -u +%FT%TZ) — window: since $SINCE ($REPO)"

echo
echo "== 1. Landed on origin/main"
# %(trailers) prints nothing when there is no Agent trailer; show that as (none)
# rather than hiding it — an untagged commit is itself worth seeing.
git log origin/main --since="$SINCE" --format='%h %an | agent=%(trailers:key=Agent,valueonly,separator=%x2C)| %s' |
  sed 's/agent=| /agent=(none) | /; s/agent=\([^|]*\)| /agent=\1 | /' |
  cut -c1-200
[ -z "$(git log origin/main --since="$SINCE" --format=%h -1)" ] && echo "(no commits in window)"

echo
echo "== 2. Open pull requests — has the LATEST push been reviewed, and what is still open?"
if OPEN="$(gh api "repos/$REPO/pulls?state=open&per_page=100" --paginate | jq -s 'add // []')"; then
  if [ "$(jq 'length' <<<"$OPEN")" -eq 0 ]; then
    echo "(none open)"
  else
    while IFS=$'\t' read -r N BRANCH TITLE SHA; do
      if ! PUSHED="$(gh api "repos/$REPO/commits/$SHA" --jq .commit.committer.date)"; then
        unverified "#$N head commit date"
        PUSHED="0000-00-00T00:00:00Z"
      fi
      if ! RAW="$(gh api "repos/$REPO/pulls/$N/reviews" --paginate)"; then
        unverified "#$N reviews"
        echo "#$N $BRANCH | $TITLE | head ${SHA:0:8} (${PUSHED:11:5})"
        continue
      fi
      REVIEWS="$(jq -s '[.[][] | select(.user.login | test("copilot"; "i"))]' <<<"$RAW")"
      LAST="$(last_review "$REVIEWS")"
      if [ -z "$LAST" ]; then
        echo "#$N $BRANCH | $TITLE | head ${SHA:0:8} (${PUSHED:11:5}) | NO Copilot review yet"
      else
        RAT="$(jq -r '.submitted_at' <<<"$LAST")"
        REVIEW_SHA="$(jq -r '.commit_id // ""' <<<"$LAST")"
        STALE=""
        if [ -n "$REVIEW_SHA" ] && [ "$REVIEW_SHA" != "$SHA" ]; then
          STALE="  <-- latest push has NOT been reviewed (reviewed ${REVIEW_SHA:0:8}, head is ${SHA:0:8})"
        fi
        if jq -e '.body | test("unable to review")' >/dev/null <<<"$LAST"; then
          echo "#$N $BRANCH | $TITLE | head ${SHA:0:8}, last Copilot review ${RAT:11:5} | ERRORED (unable to review — request another Copilot review)$STALE"
        else
          read -r O M <<<"$(review_counts "$LAST")"
          HEADLINE="$(review_headline "$LAST")"
          echo "#$N $BRANCH | $TITLE | head ${SHA:0:8}, last Copilot review ${RAT:11:5} | open=$O missed=$M$STALE"
          if [ "$O" -gt 0 ] || [ "$M" -gt 0 ]; then review_titles "$LAST"; fi
          [ -n "$HEADLINE" ] && echo "      overview: $HEADLINE"
        fi
      fi
    done < <(jq -r '.[] | [.number, .head.ref, .title[0:70], .head.sha] | @tsv' <<<"$OPEN")
  fi
else
  unverified "open pull requests"
fi

echo
echo "== 3. Merged in window — what did Copilot's LAST review say, and did it arrive before the merge?"
if ! MERGED_JSON="$(gh pr list --state merged --search "merged:>=$SINCE" --limit "$LIMIT" --json number,title,mergedAt)"; then
  unverified "merged pull requests"
else
  truncated "merged pull requests" "$MERGED_JSON"
  MERGED="$(jq -r --arg s "$SINCE" '[.[] | select(.mergedAt > $s)] | sort_by(.mergedAt) | .[]
    | "\(.number)\t\(.mergedAt)\t\(.title[0:80])"' <<<"$MERGED_JSON")"
  if [ -z "$MERGED" ]; then
    echo "(no PRs merged in window)"
  else
    while IFS=$'\t' read -r N MERGED_AT TITLE; do
      if ! RAW="$(gh api "repos/$REPO/pulls/$N/reviews" --paginate)"; then
        unverified "#$N reviews"
        continue
      fi
      REVIEWS="$(jq -s '[.[][] | select(.user.login | test("copilot"; "i"))]' <<<"$RAW")"
      TOTAL="$(jq 'length' <<<"$REVIEWS")"
      AFTER="$(jq --arg m "$MERGED_AT" '[.[] | select(.submitted_at > $m)] | length' <<<"$REVIEWS")"
      ERRORED="$(jq '[.[] | select(.body | test("unable to review"))] | length' <<<"$REVIEWS")"
      LAST="$(last_review "$REVIEWS")"
      if [ -z "$LAST" ]; then
        echo "#$N $TITLE — merged $MERGED_AT; NO Copilot review at all"
        continue
      fi
      read -r O M <<<"$(review_counts "$LAST")"
      HEADLINE="$(review_headline "$LAST")"
      RAT="$(jq -r '.submitted_at' <<<"$LAST")"
      WHEN="before"; [ "$RAT" \> "$MERGED_AT" ] && WHEN="AFTER"
      LINE="#$N $TITLE — merged ${MERGED_AT:11:5}; copilot reviews=$TOTAL, last at ${RAT:11:5} ($WHEN merge): open=$O missed=$M"
      if [ "$AFTER" -gt 0 ] || [ "$ERRORED" -gt 0 ] || [ "$O" -gt 0 ] || [ "$M" -gt 0 ] || [ -n "$HEADLINE" ]; then
        [ "$ERRORED" -gt 0 ] && LINE="$LINE, ERRORED=$ERRORED"
        echo "$LINE  <-- READ"
        [ -n "$HEADLINE" ] && echo "      overview: $HEADLINE"
        review_titles "$LAST"
        if COMMENTS="$(gh api "repos/$REPO/pulls/$N/comments" --paginate)"; then
          jq -r --arg m "$MERGED_AT" '.[] | select(.user.login | test("copilot"; "i")) | select(.created_at > $m)
            | "      after merge: \(.path):\(.line // .original_line): \(.body | gsub("\n"; " ") | .[0:200])"' <<<"$COMMENTS"
        else
          unverified "#$N line comments (the review above is real; its comments were not read)"
        fi
        jq -r '.body | select(test("Suppressed comments")) | "      (SUPPRESSED block present — read it: gh api repos/'"$REPO"'/pulls/'"$N"'/reviews)"' <<<"$LAST"
      else
        echo "$LINE"
      fi
    done <<<"$MERGED"
    echo "(open= and missed= are Copilot's own counts in its last review. They are claims, not verdicts:"
    echo " Copilot reads and never runs anything, so check each before acting, and before dismissing it.)"
  fi
fi

echo
echo "== 4. Working tree"
echo "branch: $(git branch --show-current || true)"
git status --short | head -20
[ -z "$(git status --short)" ] && echo "(clean)"

if [ "$UNVERIFIED" -gt 0 ]; then
  echo
  echo "== $UNVERIFIED query(ies) NOT VERIFIED above. Do not treat this report as complete." >&2
  exit 1
fi
