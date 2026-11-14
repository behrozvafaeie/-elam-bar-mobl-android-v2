#!/usr/bin/env bash
set -euo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"

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

echo "Launching app..."
timeout 30s adb shell am force-stop ir.elambar.mobl
timeout 30s adb shell monkey -p ir.elambar.mobl 1
sleep 8
if timeout 20s adb shell pidof ir.elambar.mobl; then
  echo "SUCCESS: APK installed and app process is running."
else
  echo "FAILURE: App process did not remain running after launch."
  timeout 20s adb logcat -d -t 250 || true
  exit 1
fi
