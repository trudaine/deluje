#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Provision JDK 27-ea per machine (shared with build.sh); sets JAVA_HOME + JAVA_EXEC.
# shellcheck source=scripts/ensure-jdk27.sh
. scripts/ensure-jdk27.sh

JAR="target/deluge-swing.jar"

# Build the self-contained Swing fat jar if it's missing OR any source changed since it was built
# (so edits actually reach the launched app instead of reusing a stale jar).
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" \( -name '*.java' -o -name pom.xml \) -print -quit 2>/dev/null)" ]; then
  echo "Building $JAR (missing or sources changed)..."
  ./mvnw -q clean package -Pswing-dist -DskipTests
fi

echo "Launching Deluge ($JAR)..."
# --enable-preview is REQUIRED: the classes are compiled with preview features, so the JVM refuses
# to load them without it. --add-modules exposes the incubating Vector API used by the DSP.
"$JAVA_EXEC" --enable-preview --add-modules jdk.incubator.vector -jar "$JAR" --swing
