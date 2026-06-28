#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Provision JDK 27-ea per machine (shared with build.sh); sets JAVA_HOME + JAVA_EXEC.
# shellcheck source=scripts/ensure-jdk27.sh
. scripts/ensure-jdk27.sh

JAR_NAME="deluge-swing.jar"

echo "Launching Deluge ($JAR_NAME)..."
"$JAVA_EXEC" --add-modules jdk.incubator.vector -jar "$JAR_NAME" --swing
