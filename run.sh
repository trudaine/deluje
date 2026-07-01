#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Provision JDK 27-ea per machine (shared with build.sh); sets JAVA_HOME + JAVA_EXEC.
# shellcheck source=scripts/ensure-jdk27.sh
. scripts/ensure-jdk27.sh

JAR="target/deluge-swing.jar"

# Build the self-contained Swing fat jar if it isn't present yet (via the committed mvnw wrapper).
if [ ! -f "$JAR" ]; then
  echo "$JAR not found — building it (first run)..."
  ./mvnw -q clean package -Pswing-dist -DskipTests
fi

echo "Launching Deluge ($JAR)..."
# --enable-preview is REQUIRED: the classes are compiled with preview features, so the JVM refuses
# to load them without it. --add-modules exposes the incubating Vector API used by the DSP.
"$JAVA_EXEC" --enable-preview --add-modules jdk.incubator.vector -jar "$JAR" --swing
