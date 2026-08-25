#!/usr/bin/env bash
# Runs the native (Kotlin) on-device integration suite on a booted emulator.
#
# Usage:
#   tool/run_android_integration_tests.sh [device_serial] [test_class]
#
# test_class is a fully-qualified androidx.test class name; pass
# "io.securitycam.level2.ScreenOffGateTest" to run only the screen-off gate,
# or omit the -e class filter with ALL=1 to run every androidTest class.
#
# The host cannot tap system permission dialogs, so the real permissions are
# granted via `pm grant` after the APKs are installed. The screen-off test
# coordinates through `[itest]` markers emitted to LOGCAT (Log.i("itest", …)):
# on `SCREEN_OFF_READY` the script toggles the display off ~5s later, and back
# on when `SCREEN_OFF_DONE` appears.
#
# Clip audio assertion: clips always carry the mic track, so monitoring tests
# default to expectClipAudio=true. Override with EXPECT_CLIP_AUDIO=false.
#
# Build type: instrumentation runs against the MINIFIED "staging" build by
# default (R8 keeps validated on every pass). Override with BUILD_TYPE=debug.
set -uo pipefail

SDK="${ANDROID_HOME:-/home/tpa/code/android-env/android-sdk}"
ADB="$SDK/platform-tools/adb"
SERIAL="${1:-emulator-5554}"
TEST_CLASS="${2:-io.securitycam.level2.MonitoringInstrumentedTest}"
EXPECT_AUDIO="${EXPECT_CLIP_AUDIO:-true}"
BUILD_TYPE="${BUILD_TYPE:-staging}"
SUFFIX=""
[ "$BUILD_TYPE" = "staging" ] && SUFFIX=".staging"
PKG="io.securitycam.level2$SUFFIX"
TEST_PKG="$PKG.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
OUT="/tmp/opencode/itest_native_$(basename "${TEST_CLASS##*.}").log"
LOGCAT="/tmp/opencode/itest_native_$(basename "${TEST_CLASS##*.}").logcat.log"

# tool/ lives inside level2/android's parent; gradle runs in android/.
ANDROID_ROOT="$(cd "$(dirname "$0")/../android" && pwd)"
GRADLE="./gradlew"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

adb() { "$ADB" -s "$SERIAL" "$@"; }

echo "== waiting for device $SERIAL =="
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
  echo "device did not boot in time"; exit 1
}

echo "== building + installing APKs =="
case "$BUILD_TYPE" in
  staging) BT_TASK="Staging" ;;
  debug)   BT_TASK="Debug" ;;
  *) echo "unsupported BUILD_TYPE=$BUILD_TYPE (staging|debug)"; exit 1 ;;
esac
# 420s: composite build+install — a cold pass re-R8s both APKs (~2-3 min) and
# streams the ~90 MB staging APK; plain warm installs finish in ~35 s.
(cd "$ANDROID_ROOT" && timeout 420 $GRADLE ":app:install$BT_TASK" ":app:install${BT_TASK}AndroidTest") || {
  echo "gradle install failed"; exit 1
}

# Grant system permissions on the freshly installed app (never while a
# streamed install is in flight).
for p in android.permission.CAMERA android.permission.RECORD_AUDIO \
         android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "$p" >/dev/null 2>&1
done
echo "permissions granted"

INSTR_ARGS=(-w -e expectClipAudio "$EXPECT_AUDIO")
if [ "$TEST_CLASS" != "all" ]; then
  INSTR_ARGS+=(-e class "$TEST_CLASS")
fi

adb logcat -c
(adb logcat > "$LOGCAT" 2>&1) &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null' EXIT

echo "== running: am instrument ${INSTR_ARGS[*]} $TEST_PKG/$RUNNER =="
# Run in the background so this script can react to [itest] logcat markers
# mid-run (screen off/on coordination) — mirrors the Flutter-era flow.
adb shell am instrument "${INSTR_ARGS[@]}" "$TEST_PKG/$RUNNER" > "$OUT" 2>&1 &
TEST_PID=$!

OFF_PHASE=0
while kill -0 "$TEST_PID" 2>/dev/null; do
  if [ "$OFF_PHASE" = 0 ] && grep -q "SCREEN_OFF_READY" "$LOGCAT" 2>/dev/null; then
    OFF_PHASE=1
    sleep 5
    echo "== screen OFF =="
    adb shell input keyevent KEYCODE_POWER
  elif [ "$OFF_PHASE" = 1 ] && grep -q "SCREEN_OFF_DONE" "$LOGCAT" 2>/dev/null; then
    OFF_PHASE=2
    adb shell input keyevent KEYCODE_POWER
    echo "== screen ON =="
  fi
  sleep 2
done

wait "$TEST_PID"
RAW=$?
# adb shell over the legacy protocol may not propagate the remote exit code;
# trust the runner's summary line instead.
if grep -qE "^OK \([0-9]+ tests?\)" "$OUT"; then
  STATUS=0
else
  STATUS=1
fi
kill "$LOGCAT_PID" 2>/dev/null
echo "== test finished (raw=$RAW status=$STATUS) =="
tail -25 "$OUT"
exit "$STATUS"
