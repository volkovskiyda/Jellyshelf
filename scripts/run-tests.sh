#!/usr/bin/env bash
set -uo pipefail

# run-tests.sh
#
# Runs every check in the project and prints one summary at the end:
#
#   1. Static analysis   (detekt, :app:lintDebug)           host-side, always runs
#   2. Unit tests        (:app:testDebugUnitTest)           host-side, always runs
#   3. Screenshot goldens(:app:validateDebugScreenshotTest) host-side, always runs
#   4. Behavior tests    (:app:connectedDebugAndroidTest)   needs a device or emulator
#
# :app:testSummary then aggregates the JUnit XML of every test layer and the detekt/lint XML
# reports into one HTML page at app/build/test-summary/index.html — on failures too, since that is
# when a per-layer breakdown is most useful.
#
# The instrumented layer is skipped — not failed — when nothing is attached, so this is safe to
# run on a machine with no device and in CI without an emulator job. Gradle carries the same
# guard (an onlyIf on connected*AndroidTest), but that one still builds both APKs before it
# skips; deciding here saves that work and makes the reason visible.
#
# That layer also carries the two live tests, which hit a real Jellyfin when the repo root has a
# filled .test.env and skip themselves when it does not — LiveEndpointTest reads, LiveUiJourneyTest
# drives the app end to end and undoes every write. Nothing here switches them on or off; the
# config file is the switch. See "Demo and live tests" in the README.
#
# Which device runs that layer matters, because Gradle's default is every attached one at once —
# and with .test.env filled that is one concurrent LiveUiJourneyTest per device, all signing into
# the same Jellyfin as the same user. That is not hypothetical: it has already cost a run a 401
# mid-journey. So with more than one device attached the script asks, arrow keys and enter, rather
# than picking or fanning out silently. --device and --all answer it up front; a single attached
# device is not worth a question and is used as-is.
#
# Without a terminal to ask on (CI, piped output, a backgrounded run) the question cannot be put, so
# the old behaviour stands: every device, with the warning printed. Pass --device or --all there.
#
# ANDROID_SERIAL is not an input. AGP reads it to target a device, so the script exports it from
# whatever was chosen here — and overrides, rather than obeys, one already in the environment. That
# keeps a single answer to "which device", instead of two that can disagree.
#
# --awake is the other end of a failure that does not look like one: a physical device whose screen
# locks mid-run fails with `No compose hierarchies found`, which reads as a broken test rather than a
# dark screen. The flag holds the screen on for the run and puts the three settings back when it
# ends — on Ctrl-C and on failure too, via a trap, since a device left on stay-awake holds its screen
# lit until someone notices. It applies to the devices the layer will actually run on, emulators
# excepted: those do not lock, and the settings would outlive the run in the AVD's state. The
# settings, the values restored, and why one is deleted rather than set: internal/adb-stay-awake.md.
#
# Usage:
#   scripts/run-tests.sh                     run everything available
#   scripts/run-tests.sh --device <serial>   one device, no prompt (see `adb devices`)
#   scripts/run-tests.sh --all               every attached device, no prompt
#   scripts/run-tests.sh --awake             hold physical screens on for the run, restore at the end
#   scripts/run-tests.sh --host-only         skip the instrumented layer even if a device is attached
#   scripts/run-tests.sh --no-checks         skip static analysis, run only the test layers
#   scripts/run-tests.sh --help
#
# Note: `set -e` is deliberately off. Every layer runs even when an earlier one fails, so one
# command reports the whole picture; the exit code is non-zero if any layer failed.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

HOST_ONLY=0
RUN_CHECKS=1
WANT_ALL=0
WANT_AWAKE=0
WANT_DEVICE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host-only) HOST_ONLY=1 ;;
    --no-checks) RUN_CHECKS=0 ;;
    --all) WANT_ALL=1 ;;
    --awake) WANT_AWAKE=1 ;;
    --device)
      # A missing value would otherwise swallow the next flag as a serial.
      [[ $# -ge 2 ]] || { echo "ERROR: --device needs a serial (see \`adb devices\`)" >&2; exit 2; }
      WANT_DEVICE="$2"; shift
      ;;
    --device=*) WANT_DEVICE="${1#--device=}" ;;
    -h|--help)
      # Delimited by the header's first and last lines rather than by line numbers, which silently
      # went stale once already and cut the usage list off mid-way.
      sed -n '/^# run-tests.sh$/,/^#   scripts\/run-tests.sh --help$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "ERROR: unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

[[ "$WANT_ALL" -eq 1 && -n "$WANT_DEVICE" ]] &&
  { echo "ERROR: --all and --device are contradictory; pass one." >&2; exit 2; }

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

# "Pixel 5, API 34" beside the serial: emulator-5554 and 192.168.11.111:5555 are not names anyone
# recognises under pressure. One adb round trip per device, and an unreachable device just loses
# its label rather than holding up the menu.
describe_device() {
  local adb="$1" serial="$2" model api
  model="$("$adb" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n')"
  api="$("$adb" -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')"
  [[ -z "$model" && -z "$api" ]] && return 0
  echo "${model:-unknown}${api:+, API $api}"
}

# --awake skips these. A locally launched AVD is `emulator-5554`, but one reached over TCP carries an
# address for a serial like any hardware device, so when the prefix says nothing the properties do.
is_emulator() {
  local adb="$1" serial="$2" characteristics qemu
  [[ "$serial" == emulator-* ]] && return 0
  characteristics="$("$adb" -s "$serial" shell getprop ro.build.characteristics 2>/dev/null)"
  [[ "$characteristics" == *emulator* ]] && return 0
  # Both are `1` on an emulator and unset on hardware; which one carries it depends on the image's
  # age. Only these two are read here, so matching a bare `1` cannot collide with another value.
  qemu="$("$adb" -s "$serial" shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' 2>/dev/null)"
  [[ "$qemu" == *1* ]]
}

# Serials whose settings this run changed and must put back. Populated by awake_hold only.
AWAKE_HELD=()

# Values and rationale: internal/adb-stay-awake.md. Held for the whole run rather than per layer,
# because the gap between two Gradle invocations is long enough for a screen to lock.
awake_hold() {
  local serial
  for serial in ${DEVICES[@]+"${DEVICES[@]}"}; do
    if is_emulator "$ADB" "$serial"; then
      printf 'awake:   %-24s skipped, emulator\n' "$serial"
      continue
    fi
    # Recorded before the writes, not after: a device that takes the first setting and fails the
    # second still has to be put back, and undoing one that never landed costs nothing.
    AWAKE_HELD+=("$serial")
    if "$ADB" -s "$serial" shell \
      'settings put global stay_on_while_plugged_in 7 &&
       settings put system screen_off_timeout 1800000 &&
       settings put secure lock_screen_lock_after_timeout 1800000' >/dev/null 2>&1; then
      printf 'awake:   %-24s screen held on, restored when the run ends\n' "$serial"
    else
      printf 'awake:   %-24s could not be set — carrying on without it\n' "$serial" >&2
    fi
  done
}

# Runs from a trap, so it must not disturb the exit status the run had arrived at, and must survive
# being called twice — the INT handler exits, which fires the EXIT trap on top of it.
awake_restore() {
  local status=$? serial
  [[ ${#AWAKE_HELD[@]} -eq 0 ]] && return $status
  local -a held=("${AWAKE_HELD[@]}")
  AWAKE_HELD=()
  for serial in "${held[@]}"; do
    # `delete` for the secure one: it had no value before, and `put` would pin it to one it never had.
    if "$ADB" -s "$serial" shell \
      'settings put global stay_on_while_plugged_in 0 &&
       settings put system screen_off_timeout 120000 &&
       settings delete secure lock_screen_lock_after_timeout' >/dev/null 2>&1; then
      printf 'awake:   %-24s settings restored\n' "$serial"
    else
      printf 'awake:   %-24s NOT restored — undo by hand, see internal/adb-stay-awake.md\n' \
        "$serial" >&2
    fi
  done
  return $status
}

# Arrow-key menu, written straight to the terminal and read from it, so it still works when the
# caller pipes stdout somewhere (`| tee`). Bash 3.2 is the floor here — macOS ships nothing newer —
# so `read -t` takes whole seconds only, and the escape-sequence read below relies on arrow keys
# always delivering their remaining two bytes at once.
#
# Terminal state is held for the whole menu, not per keystroke: `read -s` only silences echo while
# it blocks, so keys pressed between repaints would print into the drawing. Restoring it is not
# optional — a Ctrl-C that skipped this would hand back a terminal with no echo and no cursor.
PICKER_STTY=""
picker_restore() {
  printf '\033[?25h' >/dev/tty 2>/dev/null
  [[ -n "$PICKER_STTY" ]] && stty "$PICKER_STTY" </dev/tty 2>/dev/null
  PICKER_STTY=""
}

# Writes the chosen serial to stdout, or "ALL"; returns 1 if the caller aborted.
pick_device() {
  local -a serials=("$@")
  local count=${#serials[@]} cursor=0 total=$((${#serials[@]} + 1)) key rest i marker
  {
    echo
    echo "Several devices are attached — which one should the behavior tests run on?"
  } >/dev/tty
  # Draw once, then repaint in place by walking the cursor back up over the block.
  for ((i = 0; i < total; i++)); do echo >/dev/tty; done
  PICKER_STTY="$(stty -g </dev/tty 2>/dev/null)"
  stty -echo </dev/tty 2>/dev/null
  printf '\033[?25l' >/dev/tty
  trap 'picker_restore; echo >/dev/tty; exit 130' INT
  while true; do
    printf '\033[%dA' "$total" >/dev/tty
    for ((i = 0; i < total; i++)); do
      [[ $i -eq $cursor ]] && marker="❯" || marker=" "
      if [[ $i -lt $count ]]; then
        printf '\r\033[K  %s %-24s %s\n' "$marker" "${serials[$i]}" "${LABELS[$i]}" >/dev/tty
      else
        printf '\r\033[K  %s %-24s %s\n' "$marker" "All devices" \
          "runs the layer on all $count, concurrently" >/dev/tty
      fi
    done
    IFS= read -rsn1 key </dev/tty || { trap - INT; picker_restore; return 1; }
    case "$key" in
      # Enter. Every byte the menu emits goes to the tty, never to stdout — stdout carries the
      # chosen serial and nothing else, or the caller captures a stray newline with it.
      "") trap - INT; picker_restore; break ;;
      $'\033')
        # An arrow is ESC [ A/B; a lone escape times out after a second and is ignored.
        IFS= read -rsn2 -t 1 rest </dev/tty || continue
        case "$rest" in
          "[A") cursor=$(((cursor - 1 + total) % total)) ;;
          "[B") cursor=$(((cursor + 1) % total)) ;;
        esac
        ;;
      k) cursor=$(((cursor - 1 + total) % total)) ;;
      j) cursor=$(((cursor + 1) % total)) ;;
      q) trap - INT; picker_restore; echo >/dev/tty; return 1 ;;
    esac
  done
  [[ $cursor -lt $count ]] && echo "${serials[$cursor]}" || echo "ALL"
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

LABELS=()
for d in ${DEVICES[@]+"${DEVICES[@]}"}; do LABELS+=("$(describe_device "$ADB" "$d")"); done

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "devices: none attached"
  # Only a hint — booting an emulator is the caller's call, never this script's.
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/emulator/emulator" ]]; then
    avds="$("$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | paste -sd', ' -)"
    [[ -n "$avds" ]] && echo "         available AVDs: $avds"
    [[ -n "$avds" ]] && echo "         start one with: \$ANDROID_HOME/emulator/emulator -avd <name> &"
  fi
else
  for ((i = 0; i < ${#DEVICES[@]}; i++)); do
    [[ $i -eq 0 ]] && printf 'devices: ' || printf '         '
    printf '%-24s %s\n' "${DEVICES[$i]}" "${LABELS[$i]}"
  done
fi

# --device names a device that has to exist. Stop rather than fall back to another one: the caller
# asked for a specific device, and testing a different one is a worse answer than no answer. Gradle
# would fail on this too, but only after building both APKs.
if [[ -n "$WANT_DEVICE" ]]; then
  FOUND=0
  for d in ${DEVICES[@]+"${DEVICES[@]}"}; do [[ "$d" == "$WANT_DEVICE" ]] && FOUND=1; done
  if [[ "$FOUND" -eq 0 ]]; then
    if [[ "$HOST_ONLY" -eq 1 ]]; then
      echo "         --device $WANT_DEVICE is not attached — moot under --host-only."
      DEVICES=()
    else
      echo "ERROR: --device $WANT_DEVICE is not attached, or is offline/unauthorized." >&2
      exit 2
    fi
  else
    DEVICES=("$WANT_DEVICE")
  fi
fi

# The question is only worth asking when it has more than one answer and something to ask on.
# --host-only makes it moot; no terminal means it cannot be put at all, and the run falls back to
# what Gradle would have done anyway rather than stalling on a prompt nobody can see.
if [[ ${#DEVICES[@]} -gt 1 && "$HOST_ONLY" -eq 0 && "$WANT_ALL" -eq 0 ]]; then
  # Openable, not merely present: a process with no controlling terminal (nohup, some CI runners)
  # still has a /dev/tty entry that errors on open, and the menu would then spray failures instead
  # of falling through to the no-terminal path.
  if [[ -t 0 ]] && { : >/dev/tty; } 2>/dev/null; then
    if CHOICE="$(pick_device ${DEVICES[@]+"${DEVICES[@]}"})"; then
      [[ "$CHOICE" != "ALL" ]] && DEVICES=("$CHOICE")
    else
      echo "Aborted — no device chosen." >&2
      exit 130
    fi
  else
    echo "         NOTE: no terminal to ask on, so the layer runs on all ${#DEVICES[@]}."
    echo "         Pass --device <serial> or --all to say which."
  fi
fi

if [[ ${#DEVICES[@]} -gt 1 ]]; then
  echo "         NOTE: the behavior layer runs on all ${#DEVICES[@]}. With .test.env filled that is"
  echo "         one LiveUiJourneyTest per device, concurrently, all writing to the same server."
elif [[ ${#DEVICES[@]} -eq 1 ]]; then
  # The one place ANDROID_SERIAL is set: AGP reads it to target a device, and exporting it here —
  # rather than reading whatever the environment held — keeps the chosen, reported and tested
  # device the same one by construction.
  export ANDROID_SERIAL="${DEVICES[0]}"
  echo "running: ${DEVICES[0]}"
fi
# Fanning out is the one case that must not inherit a stale serial from the caller's environment.
[[ ${#DEVICES[@]} -gt 1 ]] && unset ANDROID_SERIAL

# After the choice rather than before it, so --awake reaches the devices the layer will actually run
# on and leaves the ones the picker ruled out untouched. The traps go up before the first write, so
# every way out of the script — summary, Ctrl-C, a layer that dies — comes back through the restore.
if [[ "$WANT_AWAKE" -eq 1 ]]; then
  if [[ "$HOST_ONLY" -eq 1 ]]; then
    echo "awake:   moot under --host-only — nothing runs on a device"
  elif [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "awake:   no device to hold awake"
  else
    trap 'awake_restore' EXIT
    trap 'awake_restore; exit 130' INT
    trap 'awake_restore; exit 143' TERM
    awake_hold
  fi
fi
echo

CHECKS_RESULT="" ; UNIT_RESULT="" ; SCREENSHOT_RESULT="" ; INSTRUMENTED_RESULT=""
FAILED=0
# Printed as an absolute file:// URL at the end — terminals linkify that, a relative path
# they do not, and the point of the report is that it opens in one click.
SUMMARY_REPORT="$PWD/app/build/test-summary/index.html"

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

if [[ "$RUN_CHECKS" -eq 1 ]]; then
  # detekt covers Kotlin style/complexity, lint the Android-specific checks. lintDebug only —
  # the release variant would report the same findings twice.
  run_layer "static analysis" detekt :app:lintDebug \
    && CHECKS_RESULT="passed" || CHECKS_RESULT="FAILED"
else
  CHECKS_RESULT="skipped (--no-checks)"
  echo "-- static analysis: skipped (--no-checks) --"
  echo
fi

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
  # Every device, not just the first: with no ANDROID_SERIAL that is what Gradle actually runs on.
  run_layer "behavior tests (on ${DEVICES[*]})" :app:connectedDebugAndroidTest \
    && INSTRUMENTED_RESULT="passed" || INSTRUMENTED_RESULT="FAILED"
fi

# Always aggregated, pass or fail. The report reads whatever XML is on disk and stamps itself
# with a generation time, so a layer that did not re-run this time is visible as such.
./gradlew :app:testSummary --console=plain -q || true

print_report_link() {
  if [[ -f "$SUMMARY_REPORT" ]]; then
    echo "Combined report: file://$SUMMARY_REPORT"
  else
    # The summary task is best-effort (|| true above); say so rather than print a dead link.
    echo "Combined report: not generated — run ./gradlew :app:testSummary" >&2
  fi
}

echo "== Summary =="
printf '  %-20s %s\n' "static analysis"    "$CHECKS_RESULT"
printf '  %-20s %s\n' "unit tests"         "$UNIT_RESULT"
printf '  %-20s %s\n' "screenshot goldens" "$SCREENSHOT_RESULT"
printf '  %-20s %s\n' "behavior tests"     "$INSTRUMENTED_RESULT"

if [[ "$FAILED" -ne 0 ]]; then
  echo
  print_report_link
  echo "Per-layer detail: app/build/reports/tests/ (unit),"
  echo "  app/build/reports/screenshotTest/ (goldens), app/build/reports/androidTests/ (behavior)."
  if [[ "$SCREENSHOT_RESULT" == "FAILED" ]]; then
    echo "Screenshot goldens differ. If the change is intentional (new feature, fixed typo),"
    echo "re-bake the baselines and re-run:"
    echo "  ./gradlew :app:updateDebugScreenshotTest"
  fi
  exit 1
fi

echo
echo "All available layers passed."
print_report_link
