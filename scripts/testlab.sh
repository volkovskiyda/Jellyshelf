#!/usr/bin/env bash
# testlab.sh
#
# Runs the instrumented suite on Firebase Test Lab — the one place it runs on devices this repo's
# arm64-only APK can install on, since an x86_64 emulator refuses it (INSTALL_FAILED_NO_MATCHING_ABIS)
# and the arm64 GitHub runners have no nested virtualisation. Both workflows call this script, and so
# does a developer who wants the answer before pushing rather than after:
#
#   scripts/testlab.sh ci        the three devices ci.yml runs on every push to main
#   scripts/testlab.sh release   the three devices release.yml runs on a tag
#   scripts/testlab.sh all       all six at once — what run-tests.sh offers after a green run
#
# It builds the debug APK and its test APK itself, so one command is the whole step. What it
# installs is the debug APK, which means R8's output is never instrumented anywhere: a keep-rule
# regression survives this gate and first shows up in a tester's crash report. run-tests.sh's
# release layer builds the minified APK and runs lintVital over it, which is as far as any gate goes;
# closing it for real needs testBuildType = "release" plus keep rules for the test APK — a change of
# its own. Worth knowing when reading a green run: it says nothing about the minified build.
#
# Authentication: in GitHub Actions the FIREBASE_SERVICE_ACCOUNT secret (a JSON key for the
# firebase-ci service account, see docs/RELEASING.md) is activated for the run. Locally the variable
# is unset and whatever account `gcloud auth login` left active is used — the project is named on
# the command rather than written into gcloud's config, so a run never changes which project the
# developer's gcloud points at.
#
# Quota is what decides how many devices fit, and why `all` is a deliberate spend. The Spark plan
# allows 10 virtual and 5 physical device executions a Pacific day, counted per device in the
# matrix. `ci` spends 2 + 1 a run, so five pushes to main fit in a day with neither bucket running
# out ahead of the other; `release` is another 2 + 1 on top of the 2 + 1 its commit already spent
# when it landed on main. `all` is 4 + 2, and a push after it spends 2 + 1 more — so a day holds one
# pre-push `all` and one push comfortably, and a second pre-push run empties the physical bucket.
# The live numbers: `gcloud alpha services quota list --service=testing.googleapis.com`.
#
# The live tests skip themselves here on every matrix: their .test.env instrumentation extras are
# absent from a runner's build, and the server is not reachable from Google's network anyway — a
# local build with a filled .test.env carries the extras, but the server is still out of reach, so
# the outcome is the same skip. BrowseCostBenchmark is @Ignore'd and costs no quota.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PROJECT=jellyshelf-3dfc8
APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Each device carries something none of the others do, and no two overlap on any axis — API level,
# form factor, orientation, emulated vs real silicon. Test Lab runs a matrix in parallel, so it
# costs the slowest device rather than the sum, around five minutes.
#
# ci.yml's three, on every push to main:
#   MediumTablet.arm 31 landscape — minSdk, the tablet form factor and the only landscape window,
#     in one execution. Carrying minSdk on the tablet rather than a phone saves an execution. What
#     it gives up is a bug living specifically in phone layout at API 31; phone layout is covered
#     at 36 here and at 31 by the release matrix. Landscape since 2026-08-08, and rather than
#     portrait rather than as well as it, because orientation is a device key of its own: Test Lab
#     bills per model, version and orientation, so a fourth entry would cost a third virtual
#     execution and take the ceiling from five pushes a day to three. Landscape is the harsher of
#     the two at 800x500 dp, where a full-width 16:9 thumbnail is 450 dp tall and pushes a whole
#     screen below the fold — nine DetailContentTest cases failed that way on a build this matrix
#     had already passed in portrait. What it gives up is a 500x800 window, the mild middle ground
#     between landscape and a phone.
#   MediumPhone.arm 36 — phone layout at the level most installs actually run.
#     MediumPhone_ps16k.arm 37 was tried here on 2026-08-09 and put back: it is the only image
#     with API 37 and 16 KB memory pages, which would be worth having since the APK ships five
#     native libraries, but it fails InstallFlowTest where every other device passes the same APK
#     — one case in one run (matrix-1nax7tw90ci2m), two in the next (matrix at 22:14). Both are
#     the offer dialog never arriving; giving it an explicit 5 s wait made it worse rather than
#     better, so the cause is not simply that the image is slow. Worth another look on its own,
#     not while moving the matrix.
#   frankel 36 — a physical Pixel 10: real silicon, and so real hardware codecs behind the playback
#     tests, which no emulator exercises. It offers API 36 and nothing else, the newest level any
#     physical device in the catalogue runs — there is no physical 37, against 67 physical models
#     at 36. Replaces shiba (Pixel 8) at 35, which was pinned there only by the input-injection bug
#     below; frankel was the model that bug hit hardest, so it is also the strongest evidence the
#     fix holds on real hardware.
#
# release.yml's three, on a tag — chosen for what the CI three do not carry, since the tagged commit
# already went green on them when it landed on main:
#   MediumTablet.arm 35 — tablet layout at a modern API. With CI's tablet@31 and phone@36 this
#     completes the form-factor × API-level square, the last corner being MediumPhone.arm below.
#   MediumPhone.arm 31 — phone layout at minSdk, which ci.yml deliberately gives up when it pairs
#     API 31 with the tablet to save an execution. A release is where that gap is worth closing.
#   tangorpro 33 — a physical Pixel Tablet: real silicon behind a tablet layout, so hardware codecs
#     and a large screen at once, which no other device in either matrix has together.
#
# There is no tablet at API 36 to be had. MediumTablet.arm's images stop at 35 (`gcloud firebase
# test android models list` prints the supported versions), and the only API 36 tablets in the
# catalogue are physical, where API 36 is a coin flip — see the input-injection note below. That
# same note is why the physical tablet is pinned to 33 rather than tangorpro's other option of 36;
# 33 is also its HIGH-capacity version, so it barely queues.
#
# The minSdk entries are the only ones below SQLite 3.36, which spells a query plan `SCAN TABLE
# videos` where later levels spell it `SCAN videos` (measured 2026-08-20: API 31 ships 3.32.2, API
# 34 ships 3.39.2). VideoDaoInstrumentedTest normalises the two, and that normalising is
# load-bearing exactly while an entry stays below 34 — its scan assertion matches plan text, and
# unnormalised it would pass on a real table scan.
#
# Why API 36 was off limits until 2026-08-09, and what changed. Every UI test could fail with
# `NoSuchMethodException: android.hardware.input.InputManager.getInstance` — the test framework's
# input injection, not this app: it took down exactly the tests that inject touches and left every
# DAO, sync and serialization test passing. Measured 2026-08-06: frankel (Pixel 10) failed; tokay
# (Pixel 9) passed 205/205 and then failed the same way eight minutes later on the same APK. That
# looked like Test Lab serving whichever unit is free, some Android 16 units having dropped the
# method — so the matrix pinned physical to 35 and kept API 37 out entirely.
#
# The real cause was in this repo's dependency graph. Nothing declared espresso-core, so Compose
# ui-test's request for 3.4.0 resolved to 3.5.0 (December 2022), which reflects on that method.
# Declaring 3.7.0 fixed it: the local Pixel_9 AVD at API 36 went from 135 of 285 failing to 285 of
# 285 passing, with no mention of the method anywhere in the run. Since the pin existed only to
# dodge that bug, it goes. If this ever comes back, check the resolved espresso-core version before
# blaming the device pool:
#   ./gradlew :app:dependencies --configuration debugAndroidTestRuntimeClasspath
CI_DEVICES=(
  "model=MediumTablet.arm,version=31,orientation=landscape"
  "model=MediumPhone.arm,version=36"
  "model=frankel,version=36"
)
RELEASE_DEVICES=(
  "model=MediumPhone.arm,version=31"
  "model=MediumTablet.arm,version=35"
  "model=tangorpro,version=33"
)

usage() {
  sed -n '/^# testlab.sh$/,/^#   scripts\/testlab.sh all .*$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

case "${1:-}" in
  ci) DEVICES=("${CI_DEVICES[@]}") ;;
  release) DEVICES=("${RELEASE_DEVICES[@]}") ;;
  all) DEVICES=("${CI_DEVICES[@]}" "${RELEASE_DEVICES[@]}") ;;
  -h|--help) usage; exit 0 ;;
  "") echo "ERROR: which matrix? ci, release or all (try --help)" >&2; exit 2 ;;
  *) echo "ERROR: unknown matrix: $1 — ci, release or all (try --help)" >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { echo "ERROR: one matrix name, nothing else (try --help)" >&2; exit 2; }

command -v gcloud >/dev/null || { echo "ERROR: gcloud not found — brew install google-cloud-sdk" >&2; exit 2; }

# Virtual models carry the `.arm` suffix; everything else in the catalogue is hardware. Said before
# the run because the two buckets are what a day's pushes are rationed by.
virtual=0; physical=0
for d in "${DEVICES[@]}"; do
  if [[ "$d" == model=*.arm,* ]]; then virtual=$((virtual + 1)); else physical=$((physical + 1)); fi
done
echo "Test Lab matrix '$1': ${#DEVICES[@]} devices — spends $virtual virtual + $physical physical of the 10 + 5 a Pacific day."

# The key is written to a file only for as long as gcloud needs it to activate. In Actions that is
# the runner's temp dir, gone with the job; locally the variable is normally unset and the branch
# is never taken, but a developer exporting the key by hand gets the same cleanup.
if [[ -n "${FIREBASE_SERVICE_ACCOUNT:-}" ]]; then
  KEY_FILE="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/firebase-sa.XXXXXX")"
  trap 'rm -f "$KEY_FILE"' EXIT
  printf '%s' "$FIREBASE_SERVICE_ACCOUNT" > "$KEY_FILE"
  gcloud auth activate-service-account --key-file "$KEY_FILE"
elif [[ -n "${GITHUB_ACTIONS:-}" ]]; then
  echo "ERROR: set the FIREBASE_SERVICE_ACCOUNT repository secret (see docs/RELEASING.md)" >&2
  exit 2
else
  ACCOUNT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null || true)"
  [[ -n "$ACCOUNT" ]] || { echo "ERROR: no active gcloud account — run \`gcloud auth login\` first." >&2; exit 2; }
  echo "Using the local gcloud login ($ACCOUNT) on project $PROJECT."
fi

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain

DEVICE_FLAGS=()
for d in "${DEVICES[@]}"; do DEVICE_FLAGS+=(--device "$d"); done

# --timeout bounds one device's run; the workflows' job timeout has to clear it plus the build.
# --num-flaky-test-attempts 1 retries a failed test once, which is the one allowance for the
# physical units' occasional hiccup — a test that fails twice has failed.
gcloud firebase test android run --type instrumentation \
  --project "$PROJECT" \
  --app "$APP_APK" \
  --test "$TEST_APK" \
  "${DEVICE_FLAGS[@]}" \
  --timeout 25m \
  --num-flaky-test-attempts 1
