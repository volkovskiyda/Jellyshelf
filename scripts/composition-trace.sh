#!/usr/bin/env bash
# Records a system trace with every composable in it as a named slice (Compose composition tracing),
# from the terminal — what Android Studio's profiler does for you, for when a scripted run or a
# device without the IDE is the better fit. docs/COMPOSITION-TRACING.md has the three ways in and
# what each is for; this is the third.
#
# The app side is androidx.compose.runtime:runtime-tracing, which only the debug and benchmarkRelease
# build types carry (app/build.gradle.kts says why release does not). Its native half,
# tracing-perfetto-binary, ships in no APK at all: this script sideloads it the way Android Studio
# and Macrobenchmark do — pushes the library to /data/local/tmp and hands its path to the app's
# DUMP-protected TracingReceiver, which verifies a checksum before loading it. The version must be
# the one the app's tracing-perfetto asks for, so the script asks the app first and fetches exactly
# that (Gradle's cache, else Google Maven).
#
# Two modes:
#   default  the app is already running; trace for -t seconds while you drive it.
#   --cold   force-stop the app and trace its next cold launch from the first composable on —
#            first-frame composition is the one place a running-app trace cannot reach.
# Timings in these traces run high: each composable becomes a track event while the trace records,
# so read them for which composables run and how often, and take durations from the benchmark.
#
# Usage: scripts/composition-trace.sh [-p <package>] [-t <seconds>] [-o <file>] [--cold]
#   -p  default com.gmail.volkovskiyda.jellyshelf.debug (….benchmark for the benchmark build)
#   -t  default 10
#   -o  default build/traces/<package>-<timestamp>.perfetto-trace
# ANDROID_SERIAL picks the device. Open the result in https://ui.perfetto.dev.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PACKAGE="com.gmail.volkovskiyda.jellyshelf.debug"
SECONDS_TO_TRACE=10
OUT=""
COLD=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    -p) PACKAGE="$2"; shift 2 ;;
    -t) SECONDS_TO_TRACE="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --cold) COLD=true; shift ;;
    -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
done
OUT="${OUT:-build/traces/$PACKAGE-$(date +%Y%m%d-%H%M%S).perfetto-trace}"

RECEIVER="$PACKAGE/androidx.tracing.perfetto.TracingReceiver"
DEVICE_LIB="/data/local/tmp/libtracing_perfetto.so"
DEVICE_TRACE="/data/misc/perfetto-traces/composition-trace.perfetto-trace"

# The receiver answers every broadcast with one JSON object; exitCode 1 is success, and every
# reply names the binary version it needs (`requiredVersion`). A force-stopped app receives no
# broadcast without --include-stopped-packages. stdin is closed on each adb call because adb shell
# otherwise drains this script's.
broadcast() {
  adb shell am broadcast --include-stopped-packages -a "androidx.tracing.perfetto.action.$1" "${@:2}" \
    "$RECEIVER" </dev/null |
    sed -n 's/.*data="\(.*\)"$/\1/p'
}

receivers="$(adb shell cmd package query-receivers --brief -a androidx.tracing.perfetto.action.ENABLE_TRACING </dev/null)"
if [[ "$receivers" != *"$RECEIVER"* ]]; then
  echo "ERROR: $PACKAGE has no composition-tracing receiver, or is not installed." >&2
  echo "       Only the debug and benchmarkRelease builds carry runtime-tracing — not release." >&2
  exit 1
fi

# A handshake with a path that cannot exist fails without enabling anything, and its reply still
# names the version to fetch.
version="$(broadcast ENABLE_TRACING -e path /nonexistent | sed -n 's/.*"requiredVersion":"\([^"]*\)".*/\1/p')"
[[ -n "$version" ]] || { echo "ERROR: $PACKAGE did not report a tracing-perfetto version." >&2; exit 1; }

abi="$(adb shell getprop ro.product.cpu.abi </dev/null | tr -d '\r')"
aar_name="tracing-perfetto-binary-$version.aar"
aar="$(find "$HOME/.gradle/caches/modules-2/files-2.1/androidx.tracing/tracing-perfetto-binary/$version" \
  -name "$aar_name" 2>/dev/null | head -1 || true)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [[ -z "$aar" ]]; then
  aar="$work/$aar_name"
  curl -fsSL -o "$aar" \
    "https://dl.google.com/android/maven2/androidx/tracing/tracing-perfetto-binary/$version/$aar_name"
fi
unzip -p "$aar" "jni/$abi/libtracing_perfetto.so" >"$work/libtracing_perfetto.so"
[[ -s "$work/libtracing_perfetto.so" ]] || { echo "ERROR: $aar_name has no $abi library." >&2; exit 1; }
adb push "$work/libtracing_perfetto.so" "$DEVICE_LIB" >"$work/push.log" 2>&1 || { cat "$work/push.log" >&2; exit 1; }

# track_event is where the composables land; the app's atrace sections (Jellyshelf.*, frames,
# RenderThread) and the scheduler are what make them readable against the rest of a frame.
config="$(cat <<EOF
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
data_sources { config { name: "track_event" } }
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
      atrace_categories: "am"
      atrace_categories: "gfx"
      atrace_categories: "input"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "dalvik"
      atrace_apps: "$PACKAGE"
    }
  }
}
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
duration_ms: $((SECONDS_TO_TRACE * 1000))
EOF
)"

if $COLD; then
  # The receiver stores the cold-start request only when it also enabled tracing then and there
  # (result 1), never when the process had it on already (2) — so the broadcast has to start a
  # fresh process, and that process has to go before the launch that is being traced. It writes
  # the request under the app's shared media directory and does not create that directory: on an
  # install that never made it, the receiver throws ENOENT on a pool thread and the app dies.
  adb shell mkdir -p "/sdcard/Android/media/$PACKAGE" </dev/null
  adb shell am force-stop "$PACKAGE" </dev/null
  reply="$(broadcast ENABLE_TRACING_COLD_START -e path "$DEVICE_LIB")"
  [[ "$reply" == *'"exitCode":1'* ]] || { echo "ERROR: cold-start handshake failed: $reply" >&2; exit 1; }
  adb shell am force-stop "$PACKAGE" </dev/null
  # --background returns once the session is set up and prints its pid, so the launch below is
  # inside the trace and the wait after it is for this session alone.
  pid="$(adb shell perfetto --txt -c - -o "$DEVICE_TRACE" --background <<<"$config" 2>"$work/perfetto.log" |
    tr -dc '0-9')"
  [[ -n "$pid" ]] || { cat "$work/perfetto.log" >&2; exit 1; }
  echo "Tracing the cold launch of $PACKAGE for ${SECONDS_TO_TRACE}s…"
  adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 </dev/null >/dev/null 2>&1
  # /proc rather than `kill -0`: perfetto runs in its own SELinux domain, which the shell may not
  # signal, so kill -0 fails at once and the pull would take a file perfetto has not written yet.
  sleep "$SECONDS_TO_TRACE"
  while adb shell test -d "/proc/$pid" </dev/null; do sleep 1; done
else
  adb shell pidof "$PACKAGE" </dev/null >/dev/null ||
    { echo "ERROR: $PACKAGE is not running — open it first, or pass --cold." >&2; exit 1; }
  # 2 is "already enabled": an earlier run's handshake, still good for this process's lifetime.
  reply="$(broadcast ENABLE_TRACING -e path "$DEVICE_LIB")"
  [[ "$reply" == *'"exitCode":'[12][,}]* ]] || { echo "ERROR: handshake failed: $reply" >&2; exit 1; }
  echo "Tracing $PACKAGE for ${SECONDS_TO_TRACE}s — drive the app now…"
  # perfetto's own output is a warning that Ctrl+C needs a pty; shown only if it fails.
  adb shell perfetto --txt -c - -o "$DEVICE_TRACE" <<<"$config" >"$work/perfetto.log" 2>&1 ||
    { cat "$work/perfetto.log" >&2; exit 1; }
fi

mkdir -p "$(dirname "$OUT")"
adb pull "$DEVICE_TRACE" "$OUT" >"$work/pull.log" 2>&1 || { cat "$work/pull.log" >&2; exit 1; }
adb shell rm -f "$DEVICE_TRACE" </dev/null
echo "Trace: $OUT — open it in https://ui.perfetto.dev"
