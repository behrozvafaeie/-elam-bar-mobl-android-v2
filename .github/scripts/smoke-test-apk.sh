#!/usr/bin/env bash
set -euo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"

wait_for_package_manager() {
  local count=0
  while [ "$count" -lt 90 ]; do
    local booted
    booted="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [ "$booted" = "1" ] && adb shell cmd package list packages >/dev/null 2>&1; then
      return 0
    fi
    count=$((count + 1))
    sleep 5
  done
  return 1
}

attempt=1
while [ "$attempt" -le 3 ]; do
  echo "APK install/launch attempt $attempt of 3"
  if wait_for_package_manager &&
     adb install --no-streaming -r "$APK" &&
     adb shell am force-stop ir.elambar.mobl &&
     adb shell monkey -p ir.elambar.mobl 1 &&
     sleep 8 &&
     adb shell pidof ir.elambar.mobl; then
    echo "APK installed and app process is running."
    exit 0
  fi

  echo "Attempt $attempt failed; checking/restarting emulator before retry."
  if [ "$attempt" -lt 3 ]; then
    adb reboot || true
    sleep 10
    adb wait-for-device || true
  fi
  attempt=$((attempt + 1))
done

echo "APK installation or launch failed after 3 attempts."
adb devices -l || true
exit 1
