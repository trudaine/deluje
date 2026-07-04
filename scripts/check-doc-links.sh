#!/usr/bin/env bash
# Fails if any Markdown file contains a machine-specific absolute file:// link
# (e.g. file:///Users/ludo/... or file:///home/ludo/...). Docs must use repo-relative
# links so they resolve on any machine and on GitHub.
#
# Run locally: bash scripts/check-doc-links.sh
set -euo pipefail

cd "$(dirname "$0")/.."

matches=$(grep -rnE 'file:///(Users|home)/' --include='*.md' . || true)

if [ -n "$matches" ]; then
  echo "ERROR: hardcoded absolute file:// paths found in Markdown."
  echo "Use repo-relative links instead (e.g. ../src/... from docs/, or src/... from the root)."
  echo
  echo "$matches"
  exit 1
fi

echo "OK: no hardcoded absolute file:// paths in Markdown."
