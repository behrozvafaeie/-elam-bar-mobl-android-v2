#!/usr/bin/env bash
set -Eeuo pipefail

APK="artifacts/app-debug.apk"
PREVIOUS_APK="previous/app-debug.apk"
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

# adb wait-for-device only means adbd is reachable. It does NOT mean Android
# framework/system_server or Package Manager has finished booting.
echo "Waiting for Android framework, boot completion, and Package Manager..."
ready=0
for i in $(seq 1 240); do
  adb_state="$(adb get-state 2>/dev/null || true)"
  if [ "$adb_state" = "device" ]; then
    boot_completed="$(timeout 10s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    boot_animation="$(timeout 10s adb shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r' || true)"
    if [ "$boot_completed" = "1" ] && [ "$boot_animation" = "stopped" ]; then
      # Probe Package Manager only after the framework reports boot complete.
      if timeout 20s adb shell pm list packages >/dev/null 2>&1; then
        ready=1
        echo "Android boot and Package Manager are ready (attempt $i/240)."
        break
      fi
      echo "Boot is complete, but Package Manager is not responding ($i/240)."
    else
      echo "Android framework is still booting (boot_completed='$boot_completed', bootanim='$boot_animation'; $i/240)."
    fi
  else
    echo "ADB is not in device state (state='$adb_state'; $i/240)."
  fi
  sleep 5
done
if [ "$ready" -ne 1 ]; then
  echo "Android framework and Package Manager did not become ready within 20 minutes."
  dump_diagnostics
  exit 1
fi

# Allow late boot services to settle before attempting any package operation.
sleep 10
if ! timeout 30s adb shell pm list packages >/dev/null; then
  echo "Package Manager stopped responding after boot completion."
  dump_diagnostics
  exit 1
fi

echo "APK size:"
ls -lh "$APK"
echo "Checking previous 1.6.4 baseline APK..."
if [ ! -s "$PREVIOUS_APK" ]; then
  echo "Previous 1.6.4 APK artifact is missing."
  exit 1
fi

# The synthetic lower-version baseline exercises Android's in-place update path.
# It is not a substitute for comparing against the historical APK installed by a user.
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
signer_fingerprint() {
  "$APKSIGNER" verify --print-certs "$1" | awk -F': ' '/Signer #1 certificate SHA-256 digest:/ { print tolower($2); exit }'
}
BASELINE_SIGNER="$(signer_fingerprint "$PREVIOUS_APK")"
UPDATE_SIGNER="$(signer_fingerprint "$APK")"
if [ -z "$BASELINE_SIGNER" ] || [ -z "$UPDATE_SIGNER" ] || [ "$BASELINE_SIGNER" != "$UPDATE_SIGNER" ]; then
  echo "APK signing certificate mismatch; refusing to run the in-place update test."
  echo "Baseline signer: $BASELINE_SIGNER"
  echo "Update signer:   $UPDATE_SIGNER"
  exit 1
fi
echo "Baseline and update APK signing certificates match: $UPDATE_SIGNER"
install_and_verify() {
  local apk_path="$1"
  local expected_version="$2"
  local label="$3"
  echo "Installing $label (up to 1200 seconds)..."
  set +e
  timeout 1200s adb install -r "$apk_path" 2>&1 | tee "$INSTALL_LOG"
  local install_rc=${PIPESTATUS[0]}
  set -e
  if [ "$install_rc" -ne 0 ]; then
    echo "adb install returned $install_rc; checking whether Package Manager completed installation anyway..."
    if ! timeout 60s adb shell dumpsys package "$PACKAGE" | grep -F "versionCode=$expected_version"; then
      echo "$label installation did not complete."
      dump_diagnostics
      return 1
    fi
    echo "$label is installed despite the adb client timeout/exit."
  fi
  timeout 60s adb shell dumpsys package "$PACKAGE" | grep -F "versionCode=$expected_version" || {
    echo "Installed package version does not match $expected_version."
    dump_diagnostics
    return 1
  }
}

install_and_verify "$PREVIOUS_APK" 40 "baseline 1.6.4 APK"
echo "Writing a private app-data marker before update..."
adb shell run-as "$PACKAGE" mkdir -p files
printf '%s' 'keep-data-across-update-1.6.4-to-1.6.6' | adb shell run-as "$PACKAGE" tee files/update_marker.txt >/dev/null
printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="update_marker">keep-shared-preferences-across-update</string></map>' | adb shell run-as "$PACKAGE" tee shared_prefs/update_test.xml >/dev/null
adb shell am force-stop "$PACKAGE" || true

echo "Installing 1.6.6 over the existing 1.6.4 app..."
install_and_verify "$APK" 42 "in-place 1.6.6 update"
echo "In-place update installation completed and package version verified."
adb shell run-as "$PACKAGE" cat files/update_marker.txt | grep -Fx "keep-data-across-update-1.6.4-to-1.6.6"
adb shell run-as "$PACKAGE" cat shared_prefs/update_test.xml | grep -F "keep-shared-preferences-across-update"
echo "Private files and shared-preferences data survived the in-place update."

# Logcat buffer clearing is diagnostic housekeeping, not an app test.
# Some emulator images return a non-zero exit even after a successful install/update.
if ! adb logcat -c; then
  echo "WARNING: Could not clear logcat buffer; continuing with existing logs."
fi
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
