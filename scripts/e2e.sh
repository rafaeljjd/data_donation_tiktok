#!/bin/bash
# End-to-end run on a booted emulator: full study on synthetic data, then database checks.
# Usage: scripts/e2e.sh [output directory]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$HOME/.cache/feedstudy-build/e2e}"
PKG=io.github.rafaeljjd.feedstudy
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
# Gradle needs JDK 17 or later. Prefer the project's JDK over an older system one.
LOCAL_JDK="$(ls -d "$HOME"/.local/jdks/jdk-17*/Contents/Home 2>/dev/null | head -1 || true)"
if [ -n "$LOCAL_JDK" ]; then JAVA_HOME="$LOCAL_JDK"; fi
export ANDROID_HOME JAVA_HOME
ADB="$ANDROID_HOME/platform-tools/adb"
PORT=8000

mkdir -p "$OUT"
DB="$OUT/e2e.sqlite3"
rm -f "$DB" "$DB-wal" "$DB-shm"

"$ROOT/.venv/bin/python" "$ROOT/scripts/run_dev_server.py" "$DB" "$PORT" >"$OUT/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$PORT/health" >/dev/null && break
  perl -e 'select(undef,undef,undef,0.5)'
done
curl -sf "http://127.0.0.1:$PORT/health"; echo

cd "$ROOT/android"
./gradlew --console=plain -q :app:installDebug :app:installDebugAndroidTest

"$ADB" reverse tcp:$PORT tcp:$PORT
"$ADB" shell pm clear "$PKG" >/dev/null
# Precompile: on a slow machine, Android can kill an app that takes too long to start.
"$ADB" shell cmd package compile -m speed -f "$PKG" >/dev/null
"$ADB" shell cmd package compile -m speed -f "$PKG.test" >/dev/null
"$ADB" logcat -c

set +e
"$ADB" shell am instrument -w -r \
  -e class "$PKG.FullFlowTest" \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrument.log"
set -e

"$ADB" logcat -d -s FeedStudyE2E:I >"$OUT/e2e_tag.log" || true
"$ADB" logcat -d AndroidRuntime:E '*:S' >"$OUT/crash.log" || true
rm -rf "$OUT/shots"
"$ADB" pull "/sdcard/Android/data/$PKG/files/e2e" "$OUT/shots" >/dev/null || true

grep -q "OK (1 test)" "$OUT/instrument.log" || { echo "Instrumented run FAILED"; exit 1; }

kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
"$ROOT/.venv/bin/python" "$ROOT/scripts/check_e2e.py" "$DB"
