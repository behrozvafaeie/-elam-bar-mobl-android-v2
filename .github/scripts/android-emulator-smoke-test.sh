#!/usr/bin/env bash
set -Eeuo pipefail

APK="artifacts/app-debug.apk"
PACKAGE="ir.elambar.mobl"
ACTIVITY="$PACKAGE/.MainActivity"
INSTALL_LOG="/tmp/adb-install.log"

dump_diagnostics() {
  echo "===== ADB DEVICES ====="
  adb devices -l || true
  echo "===== BOOT STATE ====="
  adb shell getprop sys.boot_completed || true
  adb shell getprop init.svc.bootanim || true
  echo "===== PACKAGE MANAGER ====="
  timeout 30s adb shell pm list packages -3 || true
  timeout 30s adb shell dumpsys package "$PACKAGE" || true
  echo "===== DATA PARTITION ====="
  adb shell df -h /data || true
  echo "===== INSTALL OUTPUT ====="
  if [ -f "$INSTALL_LOG" ]; then tail -n 200 "$INSTALL_LOG"; fi
  echo "===== RELEVANT ANDROID LOGS ====="
  adb logcat -d -v threadtime | grep -E -i -A 8 -B 8 "PackageManager|PackageInstaller|installd|dex2oat|vold|FATAL EXCEPTION|AndroidRuntime|$PACKAGE|ColorBuffer|adbd" | tail -n 500 || true
  echo "===== LAST ANDROID LOGS ====="
  adb logcat -d -v threadtime | tail -n 200 || true
}

echo "Waiting for Android device..."
adb wait-for-device

echo "Waiting for Android package manager..."
ready=0
for i in $(seq 1 60); do
  if timeout 20s adb shell pm list packages >/dev/null 2>&1; then
    ready=1
    break
  fi
  echo "Package manager not ready ($i/60)"
  sleep 5
done
if [ "$ready" -ne 1 ]; then
  echo "Android package manager did not become ready."
  dump_diagnostics
  exit 1
fi

echo "APK size:"
ls -lh "$APK"
echo "Installing APK (up to 600 seconds)..."
if timeout 600s adb install -r "$APK" 2>&1 | tee "$INSTALL_LOG"; then
  echo "APK installation completed."
else
  install_rc=$?
  echo "APK install failed or timed out (exit code: $install_rc)."
  dump_diagnostics
  exit 1
fi

adb logcat -c
echo "Starting app explicitly..."
set +e
timeout 90s adb shell am start -W -n "$ACTIVITY"
launch_rc=$?
set -e
echo "Activity launch exit code: $launch_rc"

echo "Waiting 30 seconds for startup..."
sleep 30

echo "===== APP PROCESS ====="
if adb shell pidof "$PACKAGE"; then
  echo "App process is alive."
  process_rc=0
else
  echo "App process is not running."
  process_rc=1
fi

echo "===== CRASH / ANDROID RUNTIME LOGS ====="
adb logcat -d -v threadtime | grep -E -A 60 -B 12 "FATAL EXCEPTION|AndroidRuntime|Process: $PACKAGE|$PACKAGE.*(Fatal|Exception)|am_crash" | tail -n 500 || true

echo "===== RECENT APP LOGCAT ====="
adb logcat -d -v threadtime | grep -F "$PACKAGE" | tail -n 300 || true

if [ "$launch_rc" -ne 0 ] || [ "$process_rc" -ne 0 ]; then
  echo "STARTUP_SMOKE_TEST_FAILED"
  dump_diagnostics
  exit 1
fi

echo "STARTUP_SMOKE_TEST_PASSED"
