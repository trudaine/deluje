#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Provision JDK 27-ea per machine (shared with build.sh); sets JAVA_HOME + JAVA_EXEC.
# shellcheck source=scripts/ensure-jdk27.sh
. scripts/ensure-jdk27.sh

# Locate the fat jar: root directory inside a release zip, or target/ inside a git repository.
if [ -f "deluge-swing.jar" ]; then
  JAR="deluge-swing.jar"
else
  JAR="target/deluge-swing.jar"
  # Build the self-contained Swing fat jar if it's missing OR any source changed since it was built
  # (so edits actually reach the launched app instead of reusing a stale jar).
  if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" \( -name '*.java' -o -name pom.xml \) -print -quit 2>/dev/null)" ]; then
    echo "Building $JAR (missing or sources changed)..."
    ./mvnw -q clean package -Pswing-dist -DskipTests
  fi
fi

echo "Launching Deluge ($JAR)..."
# HiDPI text. Font antialiasing (the AA flags below) is a safe win on every OS. UI *scaling*,
# however, is platform-specific and must NOT be forced everywhere:
#   - Linux (esp. ChromeOS/Crostini): X11 can't auto-detect the panel DPI, so Java renders at 1x
#     and menus/dialogs come out tiny. We default sun.java2d.uiScale=2 to fix that.
#   - macOS: Retina scaling is automatic; forcing a scale would double-scale (4x, huge). Leave unset.
# Override anywhere with e.g. DELUGE_UI_SCALE=1 (or 3). sun.java2d.uiScale scales the whole UI
# crisply (integer scale) — menus, dialogs AND the grid, which auto-recomputes its pad sizes.
case "$(uname -s)" in
  Linux*) UI_SCALE="${DELUGE_UI_SCALE:-2}" ;; # Crostini/X11: no DPI auto-detect
  *) UI_SCALE="${DELUGE_UI_SCALE:-}" ;;       # macOS auto-detects Retina; don't force
esac
SCALE_FLAGS=()
if [ -n "$UI_SCALE" ]; then
  SCALE_FLAGS=(-Dsun.java2d.uiScale="$UI_SCALE" -Dsun.java2d.uiScale.enabled=true)
fi
# --enable-preview is REQUIRED: the classes are compiled with preview features, so the JVM refuses
# to load them without it. --add-modules exposes the incubating Vector API used by the DSP.
"$JAVA_EXEC" --enable-preview --enable-native-access=ALL-UNNAMED --add-modules jdk.incubator.vector \
  "${SCALE_FLAGS[@]}" \
  -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true \
  -jar "$JAR" --swing

