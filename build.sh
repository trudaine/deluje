#!/usr/bin/env bash
# Self-contained build: provisions JDK 27-ea (per machine, via scripts/ensure-jdk27.sh) and Maven
# (via the committed ./mvnw wrapper), then runs Maven. No global installs needed.
#
#   ./build.sh                 # default: clean package
#   ./build.sh test            # any Maven goals/args are passed through
#   ./build.sh -Pslow-tests test
set -e
cd "$(dirname "$0")"

# shellcheck source=scripts/ensure-jdk27.sh
. scripts/ensure-jdk27.sh

if [ "$#" -eq 0 ]; then
  set -- clean package
fi

echo "Building with ./mvnw ($*)..."
exec ./mvnw "$@"
