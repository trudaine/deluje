#!/usr/bin/env bash
set -e

JAR_NAME="deluge-1.0-SNAPSHOT.jar"

check_java() {
  if command -v java >/dev/null 2>&1; then
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    # Handle cases like 25-ea or 25.0.1
    if [[ "$JAVA_VERSION" =~ ^25 ]]; then
      return 0
    fi
  fi
  return 1
}

if [ -f "./jdk25/bin/java" ]; then
  JAVA_EXEC="./jdk25/bin/java"
elif check_java; then
  JAVA_EXEC="java"
else
  echo "Java 25 is required but was not found."
  echo "Attempting to download OpenJDK 25 from Eclipse Adoptium..."
  
  OS=$(uname -s | tr '[:upper:]' '[:lower:]')
  ARCH=$(uname -m)
  if [ "$ARCH" = "x86_64" ]; then
    ARCH="x64"
  elif [ "$ARCH" = "arm64" ] || [ "$ARCH" = "aarch64" ]; then
    ARCH="aarch64"
  fi

  URL="https://api.adoptium.net/v3/binary/latest/25/ga/${OS}/${ARCH}/jdk/hotspot/normal/eclipse?project=jdk"
  
  echo "Downloading JDK 25 for ${OS} (${ARCH})..."
  curl -L -o openjdk25.tar.gz "$URL"
  
  echo "Extracting JDK 25..."
  mkdir -p jdk25
  tar -xzf openjdk25.tar.gz -C jdk25 --strip-components=1
  
  JAVA_EXEC="./jdk25/bin/java"
  rm openjdk25.tar.gz
fi

JAR_NAME="deluge-swing.jar"


echo "Launching Deluge ($JAR_NAME)..."
"$JAVA_EXEC" --enable-preview --add-modules jdk.incubator.vector -jar "$JAR_NAME" --swing

