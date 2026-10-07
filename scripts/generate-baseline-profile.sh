#!/usr/bin/env bash
# Regenerates the release baseline profile on a connected device, the way
# `./gradlew :app:generateReleaseBaselineProfile` would if AGP 9.4.1 let it finish.
#
# It does not. The connected-test engine marks the generator's run failed even when every test
# passes: on 2026-10-07 the same three tests reported `OK (3 tests)` and INSTRUMENTATION_CODE -1
# (success) through a plain `am instrument -r`, while the Gradle task said "There were failing tests"
# over an XML of 3 tests and 0 failures. The likely trigger is Macrobenchmark's results reporting —
# nine INSTRUMENTATION_STATUS_CODE 2 bundles carrying the profile files — but the engine records
# nothing about why. A failed test task means collect, merge and copy never run, so the one-liner
# produces no profile at all.
#
# So this runs the device half on its own, judges it by its XML instead of its exit code, and then
# runs the other half with the test task excluded, which reads the files the first half pulled.
# The XML check is strict on purpose — exactly the three generator tests, none failed or skipped —
# because the generator's own guard against a quietly partial profile is a failing test.
#
# Usage: scripts/generate-baseline-profile.sh   (ANDROID_SERIAL picks the device; one physical arm64
# device, awake — see docs/BASELINE-PROFILE.md)
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

TEST_TASK=":baselineprofile:connectedNonMinifiedReleaseAndroidTest"
RESULTS="baselineprofile/build/outputs/androidTest-results/connected/nonMinifiedRelease"
EXPECTED_TESTS=3

./gradlew "$TEST_TASK" --console=plain
test_status=$?

xml="$(ls "$RESULTS"/*.xml 2>/dev/null | head -1)"
[[ -n "$xml" ]] || { echo "ERROR: no test results under $RESULTS — the device run never reported." >&2; exit 1; }
header="$(grep -m1 '<testsuites ' "$xml")"
attr() { sed -n "s/.* $1=\"\([0-9]*\)\".*/\1/p" <<<"$header"; }
tests="$(attr tests)" ; failures="$(attr failures)" ; errors="$(attr errors)" ; skipped="$(attr skipped)"
echo "generator results: tests=$tests failures=$failures errors=$errors skipped=$skipped (gradle exit $test_status)"
if [[ "$tests" != "$EXPECTED_TESTS" || "$failures" != 0 || "$errors" != 0 || "$skipped" != 0 ]]; then
  echo "ERROR: the generator did not pass all $EXPECTED_TESTS tests — no profile was collected." >&2
  echo "       Read $xml and the report the task named above." >&2
  exit 1
fi
[[ "$test_status" -ne 0 ]] && echo "NOTE: Gradle reported the test task failed with every test green — the AGP 9.4.1 engine bug above; carrying on."

./gradlew :app:generateReleaseBaselineProfile -x "$TEST_TASK" --console=plain
