#!/usr/bin/env bash
# Install the debug APK and capture a filtered logcat while reproducing the
# Parakeet 110M (nemo CTC) crash. Run this with the phone connected + USB
# debugging authorized. Output is saved to parakeet-crash.log in the repo root.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"
OUT="$REPO/parakeet-crash.log"

echo "== Checking device =="
if ! adb get-state >/dev/null 2>&1; then
  echo "No device. Plug in the phone, enable USB debugging, tap 'Allow', then:"
  echo "  adb devices   # should show your device as 'device'"
  exit 1
fi
adb devices

echo "== Installing APK =="
adb install -r "$APK"

echo
echo "On the phone now:"
echo "  1. Re-grant Accessibility: Settings > Apps > Phone Whisper > (three dots)"
echo "     > 'Allow restricted settings', then enable the accessibility service."
echo "  2. In the app, select the Parakeet 110M model (download if needed)."
echo "  3. The crash fires when the accessibility service loads the model."
echo
read -r -p "Press Enter to clear the log buffer and START capturing... "

adb logcat -c
echo "Capturing to $OUT  — reproduce the crash now, then press Ctrl-C to stop."
# Capture broadly enough to catch the native abort AND our app tags.
adb logcat -v time \
  | grep --line-buffered -iE "sherpa|onnxruntime|SIGABRT|FATAL|libc|DEBUG|tombstone|LocalTranscriber|WhisperAccessibility|phonewhisper" \
  | tee "$OUT"
