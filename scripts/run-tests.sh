#!/usr/bin/env bash
set -uo pipefail

# run-tests.sh
#
# Runs every test layer in the project and prints one summary at the end:
#
#   1. Unit tests        (:app:testDebugUnitTest)          host-side, always runs
#   2. Screenshot goldens(:app:validateDebugScreenshotTest) host-side, always runs
#   3. Behavior tests    (:app:connectedDebugAndroidTest)   needs a device or emulator
#
# The instrumented layer is skipped — not failed — when nothing is attached, so this is safe to
# run on a machine with no device and in CI without an emulator job. Gradle carries the same
# guard (an onlyIf on connected*AndroidTest), but that one still builds both APKs before it
# skips; deciding here saves that work and makes the reason visible.
#
# Usage:
#   scripts/run-tests.sh              run everything available
#   scripts/run-tests.sh --host-only  skip the instrumented layer even if a device is attached
#   scripts/run-tests.sh --help
#
# Note: `set -e` is deliberately off. Every layer runs even when an earlier one fails, so one
# command reports the whole picture; the exit code is non-zero if any layer failed.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

HOST_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --host-only) HOST_ONLY=1 ;;
    -h|--help)
      sed -n '3,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "ERROR: unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

[[ -x ./gradlew ]] || { echo "ERROR: ./gradlew not found — run this from the repo." >&2; exit 2; }

# adb lives in the SDK; fall back to PATH so a plain `brew install android-platform-tools` works.
resolve_adb() {
  local candidate
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    candidate="$root/platform-tools/adb"
    [[ -n "$root" && -x "$candidate" ]] && { echo "$candidate"; return 0; }
  done
  command -v adb 2>/dev/null || true
}

# Device ids in state "device". "offline" and "unauthorized" are attached but can't run a test,
# so they deliberately don't count.
attached_devices() {
  local adb="$1"
  [[ -z "$adb" ]] && return 0
  "$adb" devices 2>/dev/null | tail -n +2 | awk '$2 == "device" { print $1 }'
}

ADB="$(resolve_adb)"
DEVICES=()
while IFS= read -r line; do [[ -n "$line" ]] && DEVICES+=("$line"); done < <(attached_devices "$ADB")

echo "== Jellyshelf test run =="
if [[ -z "$ADB" ]]; then
  echo "adb:     not found (set ANDROID_HOME, or put adb on PATH)"
else
  echo "adb:     $ADB"
fi
if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "devices: none attached"
  # Only a hint — booting an emulator is the caller's call, never this script's.
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/emulator/emulator" ]]; then
    avds="$("$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | paste -sd', ' -)"
    [[ -n "$avds" ]] && echo "         available AVDs: $avds"
    [[ -n "$avds" ]] && echo "         start one with: \$ANDROID_HOME/emulator/emulator -avd <name> &"
  fi
else
  echo "devices: ${DEVICES[*]}"
fi
echo

UNIT_RESULT="" ; SCREENSHOT_RESULT="" ; INSTRUMENTED_RESULT=""
FAILED=0

run_layer() {
  local label="$1" ; shift
  echo "-- $label --"
  if ./gradlew "$@" --console=plain; then
    echo "PASS: $label"
    echo
    return 0
  fi
  echo "FAIL: $label"
  echo
  FAILED=1
  return 1
}

run_layer "unit tests" :app:testDebugUnitTest \
  && UNIT_RESULT="passed" || UNIT_RESULT="FAILED"

run_layer "screenshot goldens" :app:validateDebugScreenshotTest \
  && SCREENSHOT_RESULT="passed" || SCREENSHOT_RESULT="FAILED"

if [[ "$HOST_ONLY" -eq 1 ]]; then
  INSTRUMENTED_RESULT="skipped (--host-only)"
  echo "-- behavior tests: skipped (--host-only) --"
  echo
elif [[ ${#DEVICES[@]} -eq 0 ]]; then
  INSTRUMENTED_RESULT="skipped (no device)"
  echo "-- behavior tests: skipped, no device or emulator attached --"
  echo
else
  run_layer "behavior tests (on ${DEVICES[0]})" :app:connectedDebugAndroidTest \
    && INSTRUMENTED_RESULT="passed" || INSTRUMENTED_RESULT="FAILED"
fi

echo "== Summary =="
printf '  %-20s %s\n' "unit tests"         "$UNIT_RESULT"
printf '  %-20s %s\n' "screenshot goldens" "$SCREENSHOT_RESULT"
printf '  %-20s %s\n' "behavior tests"     "$INSTRUMENTED_RESULT"

if [[ "$FAILED" -ne 0 ]]; then
  echo
  echo "Reports: app/build/reports/tests/ (unit), app/build/reports/screenshotTest/ (goldens),"
  echo "         app/build/reports/androidTests/ (behavior)."
  echo "Goldens that changed on purpose are re-baked with:"
  echo "  ./gradlew :app:updateDebugScreenshotTest"
  exit 1
fi

echo
echo "All available layers passed."
