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

echo "Launching app without blocking on Android's first-frame wait..."
timeout 30s adb shell am force-stop "$PACKAGE"
adb logcat -c
timeout 30s adb shell am start -n "$MAIN_ACTIVITY"

app_pid=""
for attempt in $(seq 1 24); do
  app_pid="$(timeout 10s adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  if [ -n "$app_pid" ]; then
    echo "App process detected (pid=$app_pid; check $attempt/24)."
    break
  fi
  echo "Waiting for app process ($attempt/24)..."
  sleep 5
done

if [ -z "$app_pid" ]; then
  echo "FAILURE: App process did not start."
  timeout 20s adb logcat -d -t 600 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" || true
  exit 1
fi

echo "Allowing the first screen to settle..."
sleep 15
app_pid_after="$(timeout 15s adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$app_pid_after" ]; then
  echo "FAILURE: App process exited after launch."
  timeout 20s adb logcat -d -t 600 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" || true
  exit 1
fi

echo "Recent app-specific error scan:"
if timeout 20s adb logcat -d -t 600 | grep -E "$PACKAGE|AndroidRuntime|FATAL EXCEPTION|ANR in" | grep -E "FATAL EXCEPTION|ANR in $PACKAGE|Process: $PACKAGE" ; then
  echo "FAILURE: App-specific crash or ANR markers were found."
  exit 1
else
  echo "SUCCESS: APK installed, launch command accepted, app process stayed alive, and no app-specific fatal crash or ANR markers were found."
fi
