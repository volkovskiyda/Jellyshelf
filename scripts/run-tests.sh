#!/usr/bin/env bash
set -uo pipefail

# run-tests.sh
#
# Runs every check in the project and prints one summary at the end:
#
#   1. Static analysis   (detekt, ktlintCheck, :app:lintDebug)  host-side, always runs
#   2. Unit tests        (:app:testDebugUnitTest)           host-side, always runs
#   3. Screenshot goldens(:app:validateDebugScreenshotTest) host-side, always runs
#   4. Release build     (:app:assembleRelease)             host-side, always runs; then installed
#                                                           and cold-launched on the device, if any
#   5. Behavior tests    (:app:connectedDebugAndroidTest)   needs a device or emulator
#
# :app:testSummary then aggregates the JUnit XML of every test layer and the detekt/ktlint/lint XML
# reports into one HTML page at app/build/test-summary/index.html — on failures too, since that is
# when a per-layer breakdown is most useful.
#
# The instrumented layer is skipped — not failed — when nothing is attached, so this is safe to
# run on a machine with no device and in CI without an emulator job. Gradle carries the same
# guard (an onlyIf on connected*AndroidTest), but that one still builds both APKs before it
# skips; deciding here saves that work and makes the reason visible.
#
# The release layer is the one build the delivery jobs ship and the only one that runs R8, resource
# shrinking, lintVital and the baseline-profile expansion — none of which any other layer touches,
# and every one of which has failed a build that was green everywhere else. It takes the same
# version properties the workflows pass, so the APK reports what a tester's would, and it excludes
# the two mapping uploads (Crashlytics, Kotzilla): this is a check, not a delivery, and the delivery
# jobs upload the mapping of the build they actually ship. It never skips. Without
# keystore.properties the APK comes out unsigned and without app/kotzilla.json the plugin turns
# itself off, which is what a pull-request checkout gets — and both still run R8, which is the
# point.
#
# A build that assembles is not a build that starts, and the two have come apart: on 2026-10-08 a
# release crashed inside Firebase Performance's start-up trace on every launch, which reached
# neither Crashlytics nor Kotzilla and was caught by a tester. So when a device is in play the
# layer goes on to install the APK it just built and cold-launch it — force-stop, start the
# launcher activity, wait, then check that the process is still there and that the crash log buffer
# names nobody. That is a smoke test, not a journey: it proves the minified build survives start-up
# on real silicon, and nothing after. It needs a signed APK, so a checkout without
# keystore.properties builds and stops there, with a note; --host-only and an empty device list
# stop there too. The install is `-r`, over whatever release build the device holds, keeping its
# data — this package is the one testers run, so on a shared device it may hold real credentials.
# A device holding a *newer* versionCode is left alone, since a release package cannot be
# downgraded without an uninstall and the uninstall would take that data with it. The launch is a
# real release launch: it reports a session to production Kotzilla and Firebase like any tester's
# would, under this checkout's versionCode.
#
# When every layer is green the script ends by naming the Test Lab run both workflows will put the
# commit through, all six devices in one matrix, and asks whether to run it now — scripts/testlab.sh
# does the run, and the question is put only when there is a terminal to put it on. It is a
# question rather than a layer because of what it spends: quota that the push itself needs (the
# note at the top of that script has the arithmetic), five minutes, and a network. --testlab
# answers yes up front. A pre-push run of the same matrix is, by construction, redundant with the
# push's own; what it buys is a main that never goes red, and the answer to whether that is worth
# the quota changes from one day to the next.
#
# That layer also carries the live tests, which hit a real Jellyfin when the repo root has a filled
# .test.env and skip themselves when it does not — LiveEndpointTest and LiveStreamReconnectTest
# read, LiveUiJourneyTest drives the app end to end and undoes every write, LiveMetadataApiTest
# syncs the device's library with and without the bot server's metadata API. Nothing here switches
# them on or off; the config file is the switch — and which *keys* are filled decides which of them
# run, so a live run with the metadata API pair blank is the without-the-API case rather than a gap.
# See "Demo and live tests" in the README.
#
# Which device runs that layer matters, because Gradle's default is every attached one **at once**.
# That is measured rather than assumed: the same class across two devices took max(13s, 5s), not
# the sum. For the offline tests it is free parallelism. For the live ones it is a bug — one
# concurrent LiveUiJourneyTest per device, all signing into the same Jellyfin as the same user,
# which has already cost a run a 401 mid-journey.
#
# So on more than one device the layer runs in two phases: everything outside the live package fans
# out as before, then the live package runs once per device with ANDROID_SERIAL pinned, strictly
# one at a time. Every test is still *offered* to every device; only the overlap is gone. Whether it
# ran is a separate question: the live tests skip themselves rather than fail, so a phase in which
# every one of them skipped is a green invocation that touched nothing. That reports SKIP rather
# than PASS and names the device in the summary — see live_layer_covered — because the alternative
# is coverage that silently never happened. One device is one invocation, unchanged. Because Gradle clears the results directory on each invocation, each
# phase's XML is stashed and put back before the summary — see stash_instrumented_results.
#
# The script still asks which device to use with more than one attached, since the choice is now
# about time rather than safety. --device and --all answer it up front; a single attached device is
# not worth a question and is used as-is. Without a terminal to ask on (CI, piped output, a
# backgrounded run) the question cannot be put, so every device runs.
#
# ANDROID_SERIAL is not an input. AGP reads it to target a device, so the script exports it from
# whatever was chosen here — and overrides, rather than obeys, one already in the environment. That
# keeps a single answer to "which device", instead of two that can disagree.
#
# A `.device` file in the repo root is the standing form of that answer: one line holding a serial
# or the word `all`, read when no --device/--all was passed. It exists for git worktrees, where the
# prompt would otherwise appear on every run in every worktree; scripts/worktree.sh writes it. A
# flag still wins, since it answers for this run specifically. Unlike --device, a `.device` naming
# something unattached is a warning and not an error — standing config outlives a booted emulator,
# so the run falls back to the normal choice rather than refusing to test at all.
#
# Only one checkout at a time should drive a device, so the instrumented layer takes a lock in the
# git common dir — shared by every worktree of this repo. A second run finding a live holder waits
# for it rather than asking anything: the host layers have already run by then, and the answer to
# "shall I wait" was always yes. Ctrl-C is the way out. The contended resource is the Jellyfin
# account, not the phone, so a second device does not make the overlap safe. --concurrent overrides
# it for the cases where the collision cannot happen — no .test.env, or -Pandroid.testInstrumentation
# filters away the live tests. See instrumented_lock_claim.
#
# Another UiAutomation client on the device is refused rather than run into. Only one process can
# hold UiAutomation, and a Maestro driver left on a shared phone (or a `uiautomator dump`, or another
# project's instrumentation) leaves this run's tests with none: every shell command they issue
# answers "Not connected!", and every live test skips as "server unreachable". On 2026-10-06 that was
# 23 failures and 11 skips in a suite with nothing wrong in it. It is named up front as a note, while
# the host layers run, and checked again before the device layer, which it fails without starting —
# see uiautomation_blocked. Nothing here stops the other process: it may be someone else's.
#
# --awake is the other end of a failure that does not look like one: a physical device whose screen
# locks mid-run fails with `No compose hierarchies found`, which reads as a broken test rather than a
# dark screen. The flag holds the screen on for the run and puts the three settings back when it
# ends — on Ctrl-C and on failure too, via a trap, since a device left on stay-awake holds its screen
# lit until someone notices. It applies to the devices the layer will actually run on, emulators
# excepted: those do not lock, and the settings would outlive the run in the AVD's state. Three
# settings: stay_on_while_plugged_in (7 = AC, USB and wireless), screen_off_timeout and
# lock_screen_lock_after_timeout, the last two raised to 30 minutes. Restore puts the first two
# back to stock (0 and two minutes) and deletes the third rather than setting it, because a stock
# device has no value for it at all — a number there would be a change the run never undid.
#
# Usage:
#   scripts/run-tests.sh                     run everything available
#   scripts/run-tests.sh --device <serial>   one device, no prompt (see `adb devices`)
#   scripts/run-tests.sh --all               every attached device, no prompt
#                                            (or put either answer in a `.device` file — see above)
#   scripts/run-tests.sh --awake             hold physical screens on for the run, restore at the end
#   scripts/run-tests.sh --concurrent        don't wait for another worktree's device run
#   scripts/run-tests.sh --host-only         skip the instrumented layer even if a device is attached
#   scripts/run-tests.sh --no-checks         skip static analysis, run only the test layers
#   scripts/run-tests.sh --testlab           when green, run the Test Lab matrix without asking
#   scripts/run-tests.sh --help
#
# Note: `set -e` is deliberately off. Every layer runs even when an earlier one fails, so one
# command reports the whole picture; the exit code is non-zero if any layer failed.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

HOST_ONLY=0
RUN_CHECKS=1
WANT_ALL=0
WANT_AWAKE=0
WANT_CONCURRENT=0
WANT_TESTLAB=0
WANT_DEVICE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host-only) HOST_ONLY=1 ;;
    --no-checks) RUN_CHECKS=0 ;;
    --all) WANT_ALL=1 ;;
    --awake) WANT_AWAKE=1 ;;
    --concurrent) WANT_CONCURRENT=1 ;;
    --testlab) WANT_TESTLAB=1 ;;
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

# `.device` fills in only what no flag answered, so a flag never has to fight the file. Read here
# rather than at the point of use so that everything downstream sees one pair of variables, however
# they were set. PINNED_DEVICE records that the answer came from the file, which changes what a
# device-not-attached means further down.
PINNED_DEVICE=0
if [[ "$WANT_ALL" -eq 0 && -z "$WANT_DEVICE" && -f .device ]]; then
  DEVICE_FILE_VALUE="$(tr -d '[:space:]' <.device)"
  case "$DEVICE_FILE_VALUE" in
    "") ;;
    all|ALL) WANT_ALL=1; PINNED_DEVICE=1 ;;
    *) WANT_DEVICE="$DEVICE_FILE_VALUE"; PINNED_DEVICE=1 ;;
  esac
fi

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

# "<model>, API 34" beside the serial: emulator-5554 and 192.168.100.20:5555 are not names anyone
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

# Values and rationale in the --awake note at the top. Held for the whole run rather than per layer,
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

# The API 37 emulator image (google_apis rev 6.0.0, the latest as of 2026-08-30) ships an SELinux
# policy that denies the mediacodec domain access to an app's memfd buffer queue, which kills every
# c2.goldfish.* video decoder at its first input buffer. The live journey's playback step then
# fails with "the stream advanced only 0s" — on the direct stream and on the HLS fallback alike,
# since both decode through goldfish. Everything short of permissive was tried and lost: the
# -feature -HardwareDecoder boot flag leaves the components registered, stopping the goldfish C2
# service hangs codec creation instead, and the image refuses adb remount. So: permissive, per run,
# because setenforce resets on every AVD reboot. Emulators only and API 37 up only — hardware has
# no goldfish codecs (and no adb root), and the API 36 phone image decodes fine while enforcing.
goldfish_selinux_hold() {
  local serial api enforce
  for serial in ${DEVICES[@]+"${DEVICES[@]}"}; do
    is_emulator "$ADB" "$serial" || continue
    api="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r\n')"
    [[ "${api:-0}" -ge 37 ]] || continue
    enforce="$("$ADB" -s "$serial" shell getenforce 2>/dev/null | tr -d '\r\n')"
    if [[ "$enforce" != "Enforcing" ]]; then
      printf 'selinux: %-24s already %s\n' "$serial" "${enforce:-unreadable}"
      continue
    fi
    # adb root restarts adbd, so the shell that follows has to wait the device back.
    if "$ADB" -s "$serial" root >/dev/null 2>&1 &&
      "$ADB" -s "$serial" wait-for-device shell setenforce 0 >/dev/null 2>&1; then
      printf 'selinux: %-24s permissive until the AVD reboots — the API %s image kills goldfish video decoders enforcing\n' \
        "$serial" "$api"
    else
      printf 'selinux: %-24s could not go permissive — expect the live playback step to stall\n' "$serial" >&2
    fi
  done
}

# Every process on the chosen devices that holds, or is about to take, UiAutomation: an `am
# instrument` (a Maestro driver is one, `dev.mobile.maestro.test`) or a uiautomator run. Printed as
# `serial pid args`. Called before this run starts its own instrumentation, so anything found
# belongs to someone else.
uiautomation_holders() {
  local serial
  for serial in ${DEVICES[@]+"${DEVICES[@]}"}; do
    "$ADB" -s "$serial" shell 'ps -A -o PID,ARGS' 2>/dev/null | tr -d '\r' |
      grep -E 'com\.android\.commands\.(am\.Am instrument|uiautomator)' |
      awk -v s="$serial" '{ pid = $1; $1 = ""; print s, pid, substr($0, 2) }'
  done
}

# Prints what holds UiAutomation and how to free it, and answers whether the device layer must not
# run. `note` only warns; `block` is the check that refuses the layer.
uiautomation_blocked() {
  local mode="$1" holders serial pid args
  [[ -n "$ADB" ]] || return 1
  holders="$(uiautomation_holders)"
  [[ -n "$holders" ]] || return 1
  echo
  if [[ "$mode" == block ]]; then
    echo "BLOCKED: another process holds UiAutomation, so the behavior tests would fail with"
    echo "         \"Not connected!\" and the live ones skip as unreachable. Not running them:"
  else
    echo "NOTE:    another process holds UiAutomation — free it before the behavior tests start,"
    echo "         or that layer will refuse to run:"
  fi
  while read -r serial pid args; do
    printf '           %-22s pid %-6s %s\n' "$serial" "$pid" "${args:0:110}"
    if [[ "$args" == *dev.mobile.maestro* ]]; then
      echo "           a Maestro driver — stop it with: $ADB -s $serial shell am force-stop dev.mobile.maestro"
    else
      echo "           stop it with: $ADB -s $serial shell kill $pid   (check whose it is first)"
    fi
  done <<<"$holders"
  echo
  return 0
}

# One worktree at a time may drive a device, because the live tests make the *server* the contended
# resource, not the phone: LiveUiJourneyTest signs into the same Jellyfin as the same user and
# drives the same item, so a second run overlapping it has already cost a run a 401 mid-journey.
# Giving each worktree its own device does not help — hence a lock rather than more hardware.
#
# It lives in the git common dir, which every worktree of this repo shares and no worktree commits.
# mkdir is the claim: it is atomic on every filesystem worth caring about, where a test-then-write
# on a plain file is not.
#
# Finding it held means waiting, with nothing asked. The one question worth putting — "shall I wait
# or run anyway?" — has the same answer every time, and putting it needs someone watching the
# terminal at the exact moment the host layers finish, which is the moment they have wandered off.
# So: wait by default, --concurrent to opt out, and no abort path at all. Ctrl-C is how you give up,
# and it costs nothing, because the host layers are already done by the time the wait starts.
INSTRUMENTED_LOCK=""
LOCK_HELD=0
# Interactive waiting is uncapped: a person can see the progress line and interrupt. Without a
# terminal there is no one to interrupt, and a wait that never ends is a hung CI job rather than a
# careful one — so it gives up waiting after this and runs anyway, which is the lesser failure.
LOCK_WAIT_CAP=900
LOCK_POLL=5

# Waits here run to tens of minutes, and "still waiting (2700)" reads as a bug rather than a number
# of seconds.
fmt_duration() {
  local total="$1"
  if [[ "$total" -lt 60 ]]; then printf '%ds' "$total"
  elif [[ $((total % 60)) -eq 0 ]]; then printf '%dm' "$((total / 60))"
  else printf '%dm%ds' "$((total / 60))" "$((total % 60))"
  fi
}

# A holder whose process is gone left the lock behind by being killed -9 or by a power cut; the
# claim is only meaningful while the claimant is alive.
lock_holder_alive() {
  local pid="$1"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

# Fields, never sourced: the file is written by another process, and sourcing it would execute
# whatever ended up inside. Sets the four HOLDER_* globals, blanked first so a truncated or
# half-written info file cannot leave the previous holder's values standing.
HOLDER_PID="" ; HOLDER_DIR="" ; HOLDER_DEV="" ; HOLDER_WHEN=""
lock_read_holder() {
  HOLDER_PID="" ; HOLDER_DIR="" ; HOLDER_DEV="" ; HOLDER_WHEN=""
  [[ -f "$INSTRUMENTED_LOCK/info" ]] || return 0
  IFS=$'\n' read -r -d '' HOLDER_PID HOLDER_DIR HOLDER_DEV HOLDER_WHEN \
    < <(cat "$INSTRUMENTED_LOCK/info"; printf '\0')
}

lock_take() {
  printf '%s\n%s\n%s\n%s\n' "$$" "$PWD" "${DEVICES[*]}" "$(date '+%Y-%m-%d %H:%M:%S')" \
    >"$INSTRUMENTED_LOCK/info"
  LOCK_HELD=1
}

# Sharing a device is not the flaky case, it is the broken one: the second run reinstalls the same
# .debug APK over the first mid-suite and kills it. --concurrent asserts "these two cannot collide",
# and on one device that is simply untrue — so it is refused rather than warned about. Disjoint
# devices are what the flag is for. Reads the HOLDER_* globals; call after lock_read_holder.
refuse_if_device_shared() {
  local d shared=""
  for d in ${DEVICES[@]+"${DEVICES[@]}"}; do
    [[ " $HOLDER_DEV " == *" $d "* ]] && shared+=" $d"
  done
  [[ -n "$shared" ]] || return 0
  echo >&2
  echo "ERROR: --concurrent, but the run already under way is on the same device:$shared" >&2
  printf '         holder   %s (pid %s)\n' "${HOLDER_DIR:-unknown}" "$HOLDER_PID" >&2
  echo "         Two runs on one device reinstall the APK over each other — a guaranteed" >&2
  echo "         failure, not a flake. Drop --concurrent to queue behind it, or pass --device" >&2
  echo "         with a device it is not using." >&2
  exit 2
}

# --concurrent's refusal, brought forward to before the host layers. The claim itself belongs after
# them, but a run that is going to be refused should be refused in the first seconds rather than
# after static analysis, the unit tests and the goldens. The check inside the claim stays: a holder
# can also
# appear while those layers are running.
instrumented_lock_precheck() {
  local common
  common="$(git rev-parse --git-common-dir 2>/dev/null)" || return 0
  [[ -d "$common" ]] || return 0
  INSTRUMENTED_LOCK="$common/jellyshelf-instrumented.lock"
  [[ -d "$INSTRUMENTED_LOCK" ]] || return 0
  lock_read_holder
  lock_holder_alive "$HOLDER_PID" || return 0
  refuse_if_device_shared
}

instrumented_lock_claim() {
  local common
  common="$(git rev-parse --git-common-dir 2>/dev/null)" || return 0
  [[ -d "$common" ]] || return 0
  INSTRUMENTED_LOCK="$common/jellyshelf-instrumented.lock"

  local interactive=0 waited=0 announced=0 next_report=0 report_every=30
  local stale_seen=0 clear_failures=0 stash
  # 2>/dev/null comes first on purpose: redirections are applied left to right, and a failing
  # >/dev/tty otherwise reports itself to a stderr that has not been silenced yet.
  if [[ -e /dev/tty ]] && : 2>/dev/null >/dev/tty; then interactive=1; else report_every=300; fi

  while true; do
    # The claim and the retry are the same call: whoever wins the mkdir owns the lock, so a
    # released lock is picked up here without a separate check that could race two waiters.
    if mkdir "$INSTRUMENTED_LOCK" 2>/dev/null; then
      lock_take
      [[ "$announced" -eq 1 ]] && printf '         device free after %s — starting\n' "$(fmt_duration "$waited")"
      return 0
    fi

    lock_read_holder
    if ! lock_holder_alive "$HOLDER_PID"; then
      # An empty pid usually means a holder that won the mkdir a millisecond ago and has not
      # written info yet, not a corpse — the two statements are adjacent. Treating that as stale is
      # how two waiters both "cleared" a freed lock and both claimed it, which this loop did before
      # the grace pass was added. One poll of patience distinguishes them.
      if [[ "$stale_seen" -eq 0 ]]; then
        stale_seen=1
        sleep "$LOCK_POLL"
        waited=$((waited + LOCK_POLL))
        continue
      fi
      # Renamed rather than deleted in place, because rename is exclusive where rm-then-mkdir is
      # not: of two waiters clearing the same corpse exactly one mv succeeds, and the loser's next
      # mkdir decides the claim atomically instead of deleting the winner's fresh lock.
      stash="$INSTRUMENTED_LOCK.stale.$$"
      if mv "$INSTRUMENTED_LOCK" "$stash" 2>/dev/null; then
        printf '         (cleared a stale lock from pid %s)\n' "${HOLDER_PID:-unknown}"
        rm -rf "$stash"
        stale_seen=0
        continue
      fi
      # Losing that race is normal and self-correcting. Losing it repeatedly is not a race at all
      # but something we cannot move — an unwritable git dir — and waiting on it would never end.
      clear_failures=$((clear_failures + 1))
      if [[ "$clear_failures" -ge 3 ]]; then
        echo "         NOTE: a stale lock is here and will not clear — proceeding without it." >&2
        return 0
      fi
      continue
    fi
    stale_seen=0

    # --concurrent is checked here rather than at the call site on purpose: an unheld lock is still
    # worth taking, so that a *later* run waits for this one instead of piling a third on top.
    if [[ "$WANT_CONCURRENT" -eq 1 ]]; then
      refuse_if_device_shared
      echo
      printf 'BUSY:    %s is driving %s (pid %s) — --concurrent, not waiting.\n' \
        "${HOLDER_DIR:-another worktree}" "${HOLDER_DEV:-a device}" "$HOLDER_PID"
      echo "         Different devices, so only the Jellyfin account is shared: if both runs have"
      echo "         a filled .test.env, expect live-test flakes."
      return 0
    fi

    if [[ "$announced" -eq 0 ]]; then
      announced=1
      echo
      echo "BUSY:    another test run is already driving a device:"
      printf '           worktree %s\n' "${HOLDER_DIR:-unknown}"
      printf '           device   %s\n' "${HOLDER_DEV:-unknown}"
      printf '           started  %s  (pid %s)\n' "${HOLDER_WHEN:-unknown}" "$HOLDER_PID"
      echo "         Running both at once means two LiveUiJourneyTests on one Jellyfin account."
      if [[ "$interactive" -eq 1 ]]; then
        echo "         Waiting for it to finish. Ctrl-C to give up, --concurrent to skip the wait."
      else
        printf '         No terminal to interrupt on — waiting up to %s, then running anyway.\n' \
          "$(fmt_duration "$LOCK_WAIT_CAP")"
      fi
      next_report=$report_every
    fi

    if [[ "$interactive" -eq 0 && "$waited" -ge "$LOCK_WAIT_CAP" ]]; then
      printf '         NOTE: still held after %s — running anyway. Overlapping live tests may flake.\n' \
        "$(fmt_duration "$waited")" >&2
      # Deliberately not claimed: the live holder keeps it, and this run stays a guest so that
      # finishing first cannot delete a lock it never owned.
      return 0
    fi

    sleep "$LOCK_POLL"
    waited=$((waited + LOCK_POLL))
    if [[ "$waited" -ge "$next_report" ]]; then
      printf '         still waiting (%s)\n' "$(fmt_duration "$waited")"
      next_report=$((waited + report_every))
    fi
  done
}

# Guarded by LOCK_HELD so a guest run — one that proceeded past a live holder — never removes the
# holder's lock on its way out.
instrumented_lock_release() {
  local status=$?
  [[ "$LOCK_HELD" -eq 1 && -n "$INSTRUMENTED_LOCK" ]] || return $status
  LOCK_HELD=0
  rm -rf "$INSTRUMENTED_LOCK"
  return $status
}

# The live tests are a package rather than an annotation, which is what makes them filterable from
# the command line at all: `package` and `notPackage` are runner arguments, so the suite can be
# split into "everything else" and "the ones that touch the server" without touching a test.
LIVE_PACKAGE="com.gmail.volkovskiyda.jellyshelf.live"
ANDROID_TEST_RESULTS="$PWD/app/build/outputs/androidTest-results/connected/debug"
RESULTS_STASH=""

# AGP writes one TEST-<device>-_app-.xml per device and clears that directory at the start of every
# invocation, so a second pass destroys the first's results. Each pass is copied out under a name
# carrying its tag, and they all go back before :app:testSummary reads the directory. The names only
# have to be unique — testSummary walks the tree and sums the <testsuites> header of every file.
stash_instrumented_results() {
  local tag="$1" f base
  [[ -d "$ANDROID_TEST_RESULTS" ]] || return 0
  [[ -n "$RESULTS_STASH" ]] || RESULTS_STASH="$(mktemp -d "${TMPDIR:-/tmp}/jellyshelf-results.XXXXXX")"
  for f in "$ANDROID_TEST_RESULTS"/*.xml; do
    [[ -e "$f" ]] || continue
    base="$(basename "$f" .xml)"
    cp "$f" "$RESULTS_STASH/${base}-${tag}.xml"
  done
}

# Also called from the trap, so an interrupted run still reports the passes that did finish rather
# than only whichever one Gradle was in the middle of. Idempotent: no stash, nothing to do.
restore_instrumented_results() {
  local status=$?
  [[ -n "$RESULTS_STASH" && -d "$RESULTS_STASH" ]] || return $status
  mkdir -p "$ANDROID_TEST_RESULTS"
  # The last pass's own XML is already in the stash, so clearing here cannot lose anything, and it
  # keeps that pass from being counted twice under two names.
  rm -f "$ANDROID_TEST_RESULTS"/*.xml
  cp "$RESULTS_STASH"/*.xml "$ANDROID_TEST_RESULTS"/ 2>/dev/null
  rm -rf "$RESULTS_STASH"
  RESULTS_STASH=""
  return $status
}

# True when the live tests whose XML is on disk actually ran. Every live test guards itself with
# assumeTrue — no .test.env, no sync scope, server unreachable — and a skip is not a failure, so a
# run where all of them skip is a *green* invocation that touched nothing. On API 37+ that is the
# ordinary outcome for a device whose app has not been granted ACCESS_LOCAL_NETWORK: the
# reachability probe is dropped rather than refused, times out, and the whole package assumes itself
# away. Counting testcases in the live package rather than whole files keeps this usable from the
# single-device branch too, where one invocation carries the offline half as well.
#
# A skip has two shapes. AGP's older runner wrote `<skipped/>`; the connected-test engine that
# arrived with AGP 9.4.0 writes an `assumeTrue` violation as a `<failure>` whose text names
# `AssumptionViolatedException` — while still passing the task, so the XML is the only place the
# difference shows. Both count, or a blocked device reads as fully covered.
live_layer_covered() {
  local counts total skipped
  counts="$(cat "$ANDROID_TEST_RESULTS"/*.xml 2>/dev/null | awk -v pkg="$LIVE_PACKAGE" '
    index($0, "<testcase ") { live = (index($0, "classname=\"" pkg) > 0); if (live) total++ }
    live && (index($0, "<skipped") || index($0, "AssumptionViolatedException")) { skipped++ }
    END { print (total + 0) " " (skipped + 0) }
  ')"
  total="${counts%% *}" ; skipped="${counts##* }"
  [[ "$total" -gt 0 && "$skipped" -lt "$total" ]]
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
      printf 'awake:   %-24s NOT restored — undo by hand (stay_on_while_plugged_in 0, screen_off_timeout 120000, delete lock_screen_lock_after_timeout)\n' \
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
      SOURCE_LABEL="--device"; [[ "$PINNED_DEVICE" -eq 1 ]] && SOURCE_LABEL=".device"
      echo "         $SOURCE_LABEL $WANT_DEVICE is not attached — moot under --host-only."
      DEVICES=()
    elif [[ "$PINNED_DEVICE" -eq 1 ]]; then
      # Standing config, not an answer for this run: the pinned emulator simply isn't booted yet.
      # Drop the pin and let the normal path choose, rather than refusing to run.
      echo "         NOTE: .device names $WANT_DEVICE, which is not attached — ignoring the pin."
      WANT_DEVICE=""
    else
      echo "ERROR: --device $WANT_DEVICE is not attached, or is offline/unauthorized." >&2
      exit 2
    fi
  else
    DEVICES=("$WANT_DEVICE")
    [[ "$PINNED_DEVICE" -eq 1 ]] && echo "         pinned by .device"
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
  echo "         NOTE: the offline tests run on all ${#DEVICES[@]} at once; the live ones then run on"
  echo "         each device in turn, never two at a time against the same server."
elif [[ ${#DEVICES[@]} -eq 1 ]]; then
  # The one place ANDROID_SERIAL is set: AGP reads it to target a device, and exporting it here —
  # rather than reading whatever the environment held — keeps the chosen, reported and tested
  # device the same one by construction.
  export ANDROID_SERIAL="${DEVICES[0]}"
  echo "running: ${DEVICES[0]}"
fi
# Fanning out is the one case that must not inherit a stale serial from the caller's environment.
[[ ${#DEVICES[@]} -gt 1 ]] && unset ANDROID_SERIAL

# One handler for both undo steps, installed before either of them has anything to undo, so every
# way out of the script — summary, Ctrl-C, a layer that dies — comes back through it. Both halves
# no-op until they have been armed, which is what makes it safe to install this early.
cleanup() {
  local status=$?
  restore_instrumented_results
  instrumented_lock_release
  awake_restore
  return $status
}
trap 'cleanup' EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

if [[ "$WANT_CONCURRENT" -eq 1 && "$HOST_ONLY" -eq 0 && ${#DEVICES[@]} -gt 0 ]]; then
  instrumented_lock_precheck
fi

# After the choice rather than before it, so --awake reaches the devices the layer will actually run
# on and leaves the ones the picker ruled out untouched.
if [[ "$WANT_AWAKE" -eq 1 ]]; then
  if [[ "$HOST_ONLY" -eq 1 ]]; then
    echo "awake:   moot under --host-only — nothing runs on a device"
  elif [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "awake:   no device to hold awake"
  else
    awake_hold
  fi
fi
# Unconditional where --awake is opt-in: without it the affected emulator cannot pass the live
# layer at all, and the function gates itself to the emulators that need it.
if [[ "$HOST_ONLY" -eq 0 && ${#DEVICES[@]} -gt 0 ]]; then
  goldfish_selinux_hold
  uiautomation_blocked note
fi
echo

CHECKS_RESULT="" ; UNIT_RESULT="" ; SCREENSHOT_RESULT="" ; INSTRUMENTED_RESULT=""
# Devices whose live tests all skipped. A space-separated string rather than an array: this
# is read while empty, and bash 3.2 — still /bin/bash on macOS — treats "${arr[@]}" on an
# empty array as unbound under `set -u`.
LIVE_UNCOVERED=""
FAILED=0
# Printed as an absolute file:// URL at the end — terminals linkify that, a relative path
# they do not, and the point of the report is that it opens in one click.
SUMMARY_REPORT="$PWD/app/build/test-summary/index.html"

run_layer() {
  local label="$1" ; shift
  # A layer that can succeed without having run anything — the live ones — names a predicate here.
  # It is consulted only once Gradle has succeeded, and answering non-zero downgrades the verdict
  # from PASS to SKIP (return 2) without touching the exit status: a layer that ran nothing has not
  # failed, it has just covered nothing, and "PASS" is the one thing that must not be printed for it.
  local verdict_fn=""
  if [[ "${1:-}" == "--verdict-fn" ]]; then verdict_fn="$2" ; shift 2 ; fi
  echo "-- $label --"
  if ./gradlew "$@" --console=plain; then
    if [[ -n "$verdict_fn" ]] && ! "$verdict_fn"; then
      echo "SKIP: $label — every test in it skipped, so nothing was covered"
      echo
      return 2
    fi
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
  # Three tools with no overlap: detekt covers Kotlin complexity/naming/style, ktlint formatting,
  # lint the Android-specific checks. lintDebug only — the release variant would report the same
  # findings twice.
  #
  # `ktlintCheck` unqualified, unlike the two beside it: ktlint is applied to every project
  # (through the `jellyshelf.ktlint` convention plugin in build-logic/, which the root's ktlintCheck
  # also reaches into), so the bare name runs it in all of them — the root's build scripts and
  # settings.gradle.kts, :app's source sets, :baselineprofile's, and build-logic's own convention
  # plugins. Qualifying it would silently drop most of the repo. A finding here is usually one
  # command away from fixed: `./gradlew ktlintFormat`.
  #
  # `--continue` is load-bearing, and it is the whole reason this layer names its own Gradle flag.
  # Without it Gradle stops at the first task that fails, so one detekt finding hides every ktlint
  # and lint finding in the repo — and :app:testSummary then reports those two as "not run", which
  # reads like they passed. The point of a layer that runs three tools is to learn all three
  # answers in one go. The build still fails; --continue changes what gets reported, not the
  # verdict.
  run_layer "static analysis" --continue detekt ktlintCheck :app:lintDebug \
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

# Rationale in the header. The version properties come from scripts/version.sh, as in the workflows:
# versionCode is the commit count and versionName is the latest tag plus the commits since it, so a
# checkout with no usable tag yet falls back to the build file's default rather than passing an
# empty name. Crashlytics' upload task exists in every checkout; Kotzilla's exists only when the
# plugin is on, and naming a task Gradle cannot find is an error, so that exclusion follows the
# same condition the build file uses.
RELEASE_BUILD_NUMBER="$(scripts/version.sh code)"
RELEASE_ARGS=("-PbuildNumber=$RELEASE_BUILD_NUMBER")
RELEASE_VERSION_NAME="$(scripts/version.sh name 2>/dev/null || true)"
[[ -n "$RELEASE_VERSION_NAME" ]] && RELEASE_ARGS+=("-PversionName=$RELEASE_VERSION_NAME")
RELEASE_ARGS+=(-x :app:uploadCrashlyticsMappingFileRelease)
[[ -f app/kotzilla.json ]] && RELEASE_ARGS+=(-x :app:uploadMappingFileRelease)
run_layer "release build" :app:assembleRelease "${RELEASE_ARGS[@]}" \
  && RELEASE_RESULT="passed" || RELEASE_RESULT="FAILED"

RELEASE_PACKAGE="com.gmail.volkovskiyda.jellyshelf"
RELEASE_APK="app/build/outputs/apk/release/app-release.apk"
# How long a start-up crash gets to happen. The 2026-10-08 crash fired inside the first second;
# ten leaves room for a slow physical device and a cold Compose first frame.
RELEASE_LAUNCH_WAIT=10

# Rationale in the header. Returns 0 when the build launched and stayed up, 1 when it did not, 2
# when the device could not take the install — a newer versionCode installed — which says nothing
# about the build and so is reported rather than failed. Every adb call is pinned to the serial
# rather than to ANDROID_SERIAL, which at this point is set for one device and unset for several.
release_smoke() {
  local serial="$1" installed launcher out pid crash
  installed="$("$ADB" -s "$serial" shell dumpsys package "$RELEASE_PACKAGE" 2>/dev/null \
    | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)"
  if [[ -n "$installed" && "$installed" -gt "$RELEASE_BUILD_NUMBER" ]]; then
    echo "  $serial holds versionCode $installed, newer than this build's $RELEASE_BUILD_NUMBER;"
    echo "  a release package cannot be downgraded without uninstalling it, and that would wipe its data."
    return 2
  fi
  echo "  installing on $serial (versionCode $RELEASE_BUILD_NUMBER${installed:+ over $installed})"
  if ! out="$("$ADB" -s "$serial" install -r "$RELEASE_APK" 2>&1)"; then
    echo "  install failed: $out"
    return 1
  fi
  "$ADB" -s "$serial" shell am force-stop "$RELEASE_PACKAGE"
  # The crash buffer is its own log buffer and `-c` without `-b` leaves it alone.
  "$ADB" -s "$serial" logcat -c -b main -b crash 2>/dev/null
  # `cmd package resolve-activity` prints the component the launcher would start, so a renamed or
  # aliased MainActivity does not silently turn this into a test of a stale name.
  launcher="$("$ADB" -s "$serial" shell cmd package resolve-activity --brief \
    -c android.intent.category.LAUNCHER "$RELEASE_PACKAGE" 2>/dev/null | tail -1 | tr -d '\r')"
  [[ "$launcher" == */* ]] || { echo "  no launcher activity resolved for $RELEASE_PACKAGE: $launcher"; return 1; }
  out="$("$ADB" -s "$serial" shell am start -W -n "$launcher" 2>&1 | tr -d '\r')"
  if ! grep -q '^Status: ok' <<<"$out"; then
    echo "  launch refused:"; sed 's/^/    /' <<<"$out"
    return 1
  fi
  echo "  launched $launcher, watching for $RELEASE_LAUNCH_WAIT s"
  sleep "$RELEASE_LAUNCH_WAIT"
  pid="$("$ADB" -s "$serial" shell pidof "$RELEASE_PACKAGE" 2>/dev/null | tr -d '\r')"
  crash="$("$ADB" -s "$serial" logcat -d -b crash 2>/dev/null | grep -F "Process: $RELEASE_PACKAGE," || true)"
  # Put back the way it was found: stopped, so the behavior layer and the next person start from a
  # clean slate. The install itself stays — it is the newest build, same as the tester channel's.
  "$ADB" -s "$serial" shell am force-stop "$RELEASE_PACKAGE"
  # Both signals, because neither alone is enough: the system restarts an app that crashed in the
  # foreground, so a crashed process can be back under a new pid by the time pidof runs (measured:
  # `am crash` at 5 s, a fresh pid at 10 s). The crash buffer is what catches that case.
  if [[ -n "$crash" || -z "$pid" ]]; then
    if [[ -n "$pid" ]]; then
      echo "  a process is running after $RELEASE_LAUNCH_WAIT s, but the crash buffer names the package"
    else
      echo "  the process is gone after $RELEASE_LAUNCH_WAIT s"
    fi
    echo "  crash buffer: adb -s $serial logcat -d -b crash"
    "$ADB" -s "$serial" logcat -d -b crash 2>/dev/null | grep -A 12 -F "Process: $RELEASE_PACKAGE," | sed 's/^/    /' | head -40
    return 1
  fi
  echo "  up after $RELEASE_LAUNCH_WAIT s, no crash logged"
  return 0
}

if [[ "$RELEASE_RESULT" == "passed" ]]; then
  echo "-- release build: install and cold launch --"
  if [[ "$HOST_ONLY" -eq 1 ]]; then
    RELEASE_RESULT="passed; not launched (--host-only)"
    echo "skipped (--host-only)"
  elif [[ ${#DEVICES[@]} -eq 0 ]]; then
    RELEASE_RESULT="passed; not launched (no device)"
    echo "skipped, no device or emulator attached"
  elif [[ ! -f keystore.properties ]]; then
    RELEASE_RESULT="passed; not launched (unsigned — no keystore.properties)"
    echo "skipped: the APK is unsigned without keystore.properties, and an unsigned APK does not install"
  else
    SMOKE_OK=(); SMOKE_HELD=(); SMOKE_FAILED=()
    for d in "${DEVICES[@]}"; do
      release_smoke "$d"
      case $? in
        0) SMOKE_OK+=("$d") ;;
        2) SMOKE_HELD+=("$d") ;;
        *) SMOKE_FAILED+=("$d") ;;
      esac
    done
    if [[ ${#SMOKE_FAILED[@]} -gt 0 ]]; then
      RELEASE_RESULT="FAILED (built, but did not survive a cold launch on ${SMOKE_FAILED[*]})"
      FAILED=1
      echo "FAIL: release build — cold launch on ${SMOKE_FAILED[*]}"
    else
      RELEASE_RESULT="passed${SMOKE_OK[*]:+ (launched on ${SMOKE_OK[*]})}${SMOKE_HELD[*]:+; not launched on ${SMOKE_HELD[*]} (newer build installed)}"
      echo "PASS: release build — cold launch"
    fi
  fi
  echo
fi

if [[ "$HOST_ONLY" -eq 1 ]]; then
  INSTRUMENTED_RESULT="skipped (--host-only)"
  echo "-- behavior tests: skipped (--host-only) --"
  echo
elif [[ ${#DEVICES[@]} -eq 0 ]]; then
  INSTRUMENTED_RESULT="skipped (no device)"
  echo "-- behavior tests: skipped, no device or emulator attached --"
  echo
else
  # Claimed here rather than up front, so a run that has to wait for another worktree spends the
  # wait having already finished static analysis, the unit tests and the goldens — the layers that
  # need no device and no server. Nothing above this point touches either.
  instrumented_lock_claim
  if uiautomation_blocked block; then
    INSTRUMENTED_RESULT="FAILED (not run: UiAutomation held by another process)"
    FAILED=1
  elif [[ ${#DEVICES[@]} -eq 1 ]]; then
    # One device is one Gradle invocation: nothing to interleave, nothing to stash.
    if run_layer "behavior tests (on ${DEVICES[0]})" :app:connectedDebugAndroidTest; then
      INSTRUMENTED_RESULT="passed"
      # No --verdict-fn here: this invocation carries the offline tests too, so it really did cover
      # something and the layer really did pass. Only the live half can have vanished, which makes
      # it a note rather than a verdict.
      live_layer_covered || LIVE_UNCOVERED=" ${DEVICES[0]}"
    else
      INSTRUMENTED_RESULT="FAILED"
    fi
  else
    # AGP runs connected tests on every attached device *in parallel* — measured, not assumed: the
    # same class across two devices took max(13s, 5s), not the sum. For the offline tests that is
    # exactly what you want. For the live ones it is a bug: LiveUiJourneyTest signs into the same
    # Jellyfin as the same user and drives the same item, so N devices means N concurrent journeys
    # fighting over one account, which is what cost a run a 401.
    #
    # So the layer splits. Everything but the live package fans out as before; the live package
    # then runs once per device with ANDROID_SERIAL pinned, strictly one at a time. Coverage is
    # unchanged — every test still runs on every device — only the live overlap is gone.
    INSTRUMENTED_FAILED=0
    run_layer "behavior tests (offline, on ${DEVICES[*]})" :app:connectedDebugAndroidTest \
      "-Pandroid.testInstrumentationRunnerArguments.notPackage=$LIVE_PACKAGE" || INSTRUMENTED_FAILED=1
    stash_instrumented_results offline

    for d in "${DEVICES[@]}"; do
      export ANDROID_SERIAL="$d"
      # This invocation runs the live package and nothing else, so "it passed" and "it ran" are not
      # the same claim — hence the verdict predicate.
      run_layer "live tests (on $d)" --verdict-fn live_layer_covered :app:connectedDebugAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.package=$LIVE_PACKAGE"
      case $? in
        0) ;;
        2) LIVE_UNCOVERED="$LIVE_UNCOVERED $d" ;;
        *) INSTRUMENTED_FAILED=1 ;;
      esac
      stash_instrumented_results "live-$d"
    done
    unset ANDROID_SERIAL

    # Each invocation wipes the previous one's XML — verified, not assumed — so without this the
    # summary would report only the last device's live pass and call it the whole layer.
    restore_instrumented_results
    if [[ "$INSTRUMENTED_FAILED" -eq 0 ]]; then
      INSTRUMENTED_RESULT="passed (offline fanned out, live one device at a time)"
    else
      INSTRUMENTED_RESULT="FAILED"
    fi
  fi
  # Said in the summary line and not only in the note under it. A layer reporting a bare "passed"
  # is the whole mechanism by which an unreachable server goes unnoticed for weeks.
  if [[ -n "$LIVE_UNCOVERED" && "$INSTRUMENTED_RESULT" != "FAILED" ]]; then
    INSTRUMENTED_RESULT="$INSTRUMENTED_RESULT; live tests covered nothing on$LIVE_UNCOVERED"
  fi

  # Released here rather than left to the trap: everything below is report aggregation, which
  # touches neither the device nor the server, and a waiting worktree should not sit through it.
  instrumented_lock_release
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
printf '  %-20s %s\n' "release build"      "$RELEASE_RESULT"
printf '  %-20s %s\n' "behavior tests"     "$INSTRUMENTED_RESULT"

# Printed on the passing path as well as the failing one, because this *is* the passing path: a
# skip is not a failure, so nothing else here would ever mention it.
if [[ -n "$LIVE_UNCOVERED" ]]; then
  echo
  echo "WARNING: every live test skipped on$LIVE_UNCOVERED, so that layer covered nothing there."
  echo "  They guard themselves with assumeTrue and a skip is not a failure, which is exactly why"
  echo "  this needs saying out loud. Usual causes, commonest first:"
  echo "    - on API 37+, the app has not been granted ACCESS_LOCAL_NETWORK, so its reachability"
  echo "      probe to a LAN server is dropped rather than refused and times out. The tests grant"
  echo "      it themselves (JourneyPermissions.kt) before probing, so this now means the grant"
  echo "      failed: the tell is \`adb -s <serial> shell appops get <pkg> ACCESS_LOCAL_NETWORK\`"
  echo "      reading 'ignore' after a run."
  echo "    - the server is genuinely unreachable from that device"
  echo "    - .test.env is missing, or has no JELLYFIN_SYNC_FOLDER / JELLYFIN_SYNC_FOLDER_ID"
fi

if [[ "$FAILED" -ne 0 ]]; then
  echo
  print_report_link
  echo "Per-layer detail: app/build/reports/tests/ (unit),"
  echo "  app/build/reports/screenshotTest/ (goldens), app/build/reports/androidTests/ (behavior)."
  if [[ "$CHECKS_RESULT" == "FAILED" ]]; then
    echo "If the static-analysis failure is ktlint (formatting), it is fixable in one command:"
    echo "  ./gradlew ktlintFormat"
  fi
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

# The offer, rationale in the header. The command is printed whatever happens next, so a run with
# no terminal (CI, a pipe) still leaves the reader knowing what the push will do; only the question
# needs somewhere to be asked, and it reads /dev/tty rather than stdin because adb has already
# drained stdin by this point in any run that had a device.
echo
echo "Next: the Test Lab matrix both workflows run, all six devices in one go (4 virtual + 2 physical"
echo "of the 10 + 5 a Pacific day — the push to main then spends 2 + 1 more):"
echo "  scripts/testlab.sh all"
if [[ "$WANT_TESTLAB" -eq 1 ]]; then
  echo "Running it now (--testlab)."
elif [[ -t 0 ]] && { : >/dev/tty; } 2>/dev/null; then
  printf 'Run it now? [y/N] ' >/dev/tty
  IFS= read -r answer </dev/tty || answer=""
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "Not run. The command above is the whole step whenever you want it."; exit 0 ;;
  esac
else
  exit 0
fi
echo
# A call, not an exec: the EXIT trap still has --awake's settings to put back once the matrix ends.
scripts/testlab.sh all
