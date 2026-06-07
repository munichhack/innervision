#!/usr/bin/env bash
# Build, install, and launch on a USB-connected Android device.
# Retries push/install because adb over USB can drop mid-transfer on some cables.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -f .toolchain/env.sh ]]; then
  # shellcheck disable=SC1091
  source .toolchain/env.sh >/dev/null
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.blindvision.arpose"

echo "Building…"
./gradlew assembleDebug

echo "Waiting for device…"
adb wait-for-device

echo "Installing (with retries)…"
for i in 1 2 3 4 5; do
  if adb push "$APK" /data/local/tmp/bv.apk >/dev/null 2>&1; then
    OUT="$(adb shell pm install -r /data/local/tmp/bv.apk 2>&1)" || true
    echo "$OUT"
    if echo "$OUT" | grep -q Success; then
      adb shell am force-stop "$PKG" || true
      adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
      echo "Done — app launched."
      exit 0
    fi
  fi
  echo "Attempt $i failed; retrying in 2s…"
  adb wait-for-device 2>/dev/null || true
  sleep 2
done

echo "Install failed. Try: adb kill-server && adb start-server && adb devices" >&2
exit 1
