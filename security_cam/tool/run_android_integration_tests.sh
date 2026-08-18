#!/usr/bin/env bash
# Runs the Android integration suite on a booted emulator/device.
#
# Usage:
#   tool/run_android_integration_tests.sh [device_serial] [test_file]
#
# The host cannot tap system permission dialogs, so the real system permissions
# are granted via `pm grant` as soon as the fresh install is complete (never
# while the streamed install is in flight — racing `cmd package` there breaks
# the package service). The screen-off test coordinates through `[itest]`
# markers, which integration tests emit to the host driver output (not logcat):
# on `[itest] SCREEN_OFF_READY` it toggles the screen off, on `SCREEN_OFF_DONE`
# ~20s later it toggles it back on.
set -uo pipefail

ADB="${ANDROID_HOME:-/home/tpa/code/android-env/android-sdk}/platform-tools/adb"
SERIAL="${1:-emulator-5554}"
TEST="${2:-integration_test/monitoring_on_device_test.dart}"
PKG=io.securitycam.security_cam
OUT="/tmp/opencode/itest_$(basename "${TEST%_test.dart}").log"

FLUTTER=/home/tpa/code/flutter/bin/flutter
# tool/ lives inside the Flutter project root.
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT"

echo "== waiting for device $SERIAL =="
adb() { "$ADB" -s "$SERIAL" "$@"; }
for i in $(seq 1 60); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
  echo "device did not boot in time"; exit 1
}

# Clean slate: drop any stale install so the grant loop can't race the test's
# fresh streamed install (a stale package would match `pm path` immediately).
adb uninstall "$PKG" >/dev/null 2>&1

echo "== running: flutter test $TEST -d $SERIAL =="
adb logcat -c
"$FLUTTER" test "$TEST" -d "$SERIAL" > "$OUT" 2>&1 &
TEST_PID=$!

# The screen-off test coordinates through `[itest]` markers, which integration
# tests print to the HOST driver output (not logcat), so poll the captured log.
GRANTED=0
OFF_PHASE=0
while kill -0 "$TEST_PID" 2>/dev/null; do
  # Grant only once the fresh install is registered (pm path non-empty).
  if [ "$GRANTED" = 0 ] && adb shell pm path "$PKG" 2>/dev/null | grep -q base.apk; then
    for p in android.permission.CAMERA android.permission.RECORD_AUDIO \
             android.permission.POST_NOTIFICATIONS; do
      adb shell pm grant "$PKG" "$p" >/dev/null 2>&1
    done
    GRANTED=1
    echo "permissions granted"
  fi
  if [ "$OFF_PHASE" = 0 ] && grep -q "SCREEN_OFF_READY" "$OUT" 2>/dev/null; then
    OFF_PHASE=1
    sleep 5
    echo "== screen OFF =="
    adb shell input keyevent KEYCODE_POWER
  elif [ "$OFF_PHASE" = 1 ] && grep -q "SCREEN_OFF_DONE" "$OUT" 2>/dev/null; then
    OFF_PHASE=2
    adb shell input keyevent KEYCODE_POWER
    echo "== screen ON =="
  fi
  sleep 2
done

wait "$TEST_PID"
STATUS=$?
echo "== test finished (status $STATUS) =="
tail -25 "$OUT"
exit "$STATUS"