#!/usr/bin/env bash
set -euo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="ir.elambar.mobl"
MAIN_ACTIVITY="$PACKAGE/.MainActivity"

echo "Waiting for Android framework services..."
ready=0
for attempt in $(seq 1 72); do
  booted="$(timeout 8s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  packages="$(timeout 8s adb shell cmd package list packages 2>/dev/null | head -n 1 || true)"
  settings="$(timeout 8s adb shell settings get system screen_off_timeout 2>/dev/null | tr -d '\r' || true)"
  if [ "$booted" = "1" ] && [ -n "$packages" ] && [ -n "$settings" ] && [[ "$settings" != *"Can't find service"* ]]; then
    ready=1
    echo "Android package manager and settings service are ready."
    break
  fi
  echo "Framework not ready yet (check $attempt/72); boot=$booted settings=$settings"
  sleep 5
done

if [ "$ready" -ne 1 ]; then
  echo "Android framework services did not become ready."
  adb devices -l || true
  exit 1
fi

echo "Installing APK..."
timeout 120s adb install --no-streaming -r "$APK"

echo "Verifying launcher activity..."
timeout 20s adb shell cmd package resolve-activity --brief "$PACKAGE" || true

echo "Launching the app's declared MAIN activity..."
timeout 30s adb shell am force-stop "$PACKAGE"
adb logcat -c
launch_output="$(timeout 45s adb shell am start -W -n "$MAIN_ACTIVITY" 2>&1)" || {
  echo "$launch_output"
  echo "App launch command failed; recent app logs follow."
  timeout 20s adb logcat -d -t 400 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" || true
  exit 1
}
echo "$launch_output"
if ! grep -q "Status: ok" <<< "$launch_output"; then
  echo "FAILURE: Android did not confirm that the main activity started."
  timeout 20s adb logcat -d -t 400 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" || true
  exit 1
fi

sleep 8
app_pid="$(timeout 15s adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -n "$app_pid" ]; then
  echo "SUCCESS: APK installed, MAIN activity start was accepted, and app process is running (pid=$app_pid)."
else
  echo "FAILURE: App process did not remain running after launch."
  timeout 20s adb logcat -d -t 400 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" || true
  exit 1
fi

echo "Recent app-specific error scan:"
if timeout 20s adb logcat -d -t 400 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" | grep -E "FATAL EXCEPTION|ANR in $PACKAGE|Process: $PACKAGE" ; then
  echo "FAILURE: App-specific crash or ANR markers were found."
  exit 1
else
  echo "No app-specific fatal crash or ANR markers found in the captured log window."
fi
