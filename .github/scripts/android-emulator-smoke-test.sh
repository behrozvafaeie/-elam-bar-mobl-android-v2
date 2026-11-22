#!/usr/bin/env bash
set -Eeuo pipefail

APK="artifacts/app-debug.apk"
PACKAGE="ir.elambar.mobl"
ACTIVITY="$PACKAGE/.MainActivity"

echo "Waiting for Android device..."
adb wait-for-device

echo "Waiting for Android package manager..."
ready=0
for i in $(seq 1 36); do
  if timeout 10s adb shell pm list packages >/dev/null 2>&1; then
    ready=1
    break
  fi
  echo "Package manager not ready ($i/36)"
  sleep 5
done
if [ "$ready" -ne 1 ]; then
  echo "Android package manager did not become ready."
  adb logcat -d -v threadtime | tail -n 500 || true
  exit 1
fi

echo "Installing APK..."
if ! timeout 180s adb install -r "$APK"; then
  echo "APK install failed."
  adb logcat -d -v threadtime | tail -n 500 || true
  exit 1
fi

adb logcat -c
echo "Starting app explicitly..."
set +e
timeout 60s adb shell am start -W -n "$ACTIVITY"
launch_rc=$?
set -e
echo "Activity launch exit code: $launch_rc"

echo "Waiting 20 seconds for startup..."
sleep 20

echo "===== APP PROCESS ====="
if adb shell pidof "$PACKAGE"; then
  echo "App process is alive."
  process_rc=0
else
  echo "App process is not running."
  process_rc=1
fi

echo "===== CRASH / ANDROID RUNTIME LOGS ====="
adb logcat -d -v threadtime | grep -E -A 60 -B 12 "FATAL EXCEPTION|AndroidRuntime|Process: $PACKAGE|$PACKAGE.*(Fatal|Exception)|am_crash" | tail -n 400 || true

echo "===== RECENT APP LOGCAT ====="
adb logcat -d -v threadtime | grep -F "$PACKAGE" | tail -n 250 || true

if [ "$launch_rc" -ne 0 ] || [ "$process_rc" -ne 0 ]; then
  echo "STARTUP_SMOKE_TEST_FAILED"
  exit 1
fi

echo "STARTUP_SMOKE_TEST_PASSED"
