#!/usr/bin/env bash
# Ensures a JDK 27 (early-access) is available, downloading it per-machine if needed.
# SOURCE this script (`. scripts/ensure-jdk27.sh`) — it exports JAVA_HOME and sets JAVA_EXEC
# in the caller's shell. Used by both build.sh (build via ./mvnw) and run.sh (launch the jar).
#
# Resolution order:
#   1. ./jdk27         — a JDK already downloaded here (any OS layout: bin/java or Contents/Home)
#   2. system `java`   — if it reports version 27
#   3. download        — OpenJDK 27-ea from Eclipse Adoptium for this OS/arch into ./jdk27

# Locate bin/java under ./jdk27 (handles Linux jdk27/bin/java and macOS jdk27/Contents/Home/bin/java).
_deluge_jdk27_home() {
  local j
  j=$(find jdk27 -maxdepth 4 -type f -name java -path '*/bin/java' 2>/dev/null | head -n 1)
  if [ -n "$j" ]; then (cd "$(dirname "$j")/.." && pwd); fi
  return 0 # never fail (callers use `set -e`)
}

_deluge_system_java_is_27() {
  command -v java >/dev/null 2>&1 || return 1
  local v
  v=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
  [[ "$v" =~ ^27 ]]
}

_deluge_jdk27=$(_deluge_jdk27_home)
if [ -n "$_deluge_jdk27" ]; then
  export JAVA_HOME="$_deluge_jdk27"
  JAVA_EXEC="$JAVA_HOME/bin/java"
elif _deluge_system_java_is_27; then
  JAVA_EXEC="java" # use the system JDK 27 on PATH (and whatever JAVA_HOME it already provides)
else
  echo "Java 27 is required but was not found."
  echo "Downloading OpenJDK 27 (early-access) from Eclipse Adoptium..."

  OS=$(uname -s | tr '[:upper:]' '[:lower:]')
  ARCH=$(uname -m)
  if [ "$ARCH" = "x86_64" ]; then
    ARCH="x64"
  elif [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
    ARCH="aarch64"
  fi

  URL="https://api.adoptium.net/v3/binary/latest/27/ea/${OS}/${ARCH}/jdk/hotspot/normal/eclipse?project=jdk"
  echo "Downloading JDK 27 for ${OS} (${ARCH})..."
  curl -L -o openjdk27.tar.gz "$URL"

  echo "Extracting JDK 27..."
  mkdir -p jdk27
  tar -xzf openjdk27.tar.gz -C jdk27 --strip-components=1
  rm -f openjdk27.tar.gz

  _deluge_jdk27=$(_deluge_jdk27_home)
  export JAVA_HOME="$_deluge_jdk27"
  JAVA_EXEC="$JAVA_HOME/bin/java"
fi

echo "Using JDK: $("$JAVA_EXEC" -version 2>&1 | head -n 1)"
