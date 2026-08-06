# Releasing

Two delivery channels, both driven by CI. Neither needs a version edit in `build.gradle.kts`:
`versionCode` is always `git rev-list --count HEAD` — monotonic across both workflows, so a build
from one can never install backwards over a build from the other — and `versionName` is either
`<base>.<versionCode>` or, for a tagged release, the tag itself.

## Continuous — testers

Every green push to `main` builds a signed release APK and uploads it to **Firebase App
Distribution** for the `testers` group. Nothing to run: it is the `distribute` job in
[`ci.yml`](../.github/workflows/ci.yml), and it waits for the checks job.

## Curated — GitHub Release

```sh
scripts/release.sh
```

It checks that `HEAD` is `origin/main` with a clean tree, shows the versionCode the build will get,
asks for the version name, then tags `v<name>` and pushes. The
[Release workflow](../.github/workflows/release.yml) takes over and publishes a release with
`jellyshelf-<version>.<versionCode>.apk` and `mapping-<version>.<versionCode>.txt` attached.

The APK's own `versionName` is the tag alone — tag `v1.0` reports `1.0` — but the **asset filenames
append the versionCode**, so `v1.0` at commit 164 publishes `jellyshelf-1.0.164.apk`. The tag by
itself does not identify a build, and versionCode is the only identifier shared with the App
Distribution channel, which is what lets a mapping file be matched to a crash by hand.

Plain `git tag v<version> && git push origin v<version>` does the same thing.

## Testing a release build

Release builds are smoke-tested by signing in as a real (dedicated, non-admin) Jellyfin user — the
path every real install takes — not with an admin API key. The values to type live in the
git-ignored **`.test.env`** at the repo root (template: `.example.test.env`); the live-endpoint
instrumentation tests read the same file, so there is exactly one place to keep them.

Debug builds never need any of this: day-to-day work runs on **demo mode** (or mocks/fakes in
tests), with no server at all.

**Run the live suite first — CI never will.** The pipeline is deliberately hermetic: the `testlab`
job uploads APKs built on a runner that has no `.test.env`, so `LiveEndpointTest` and
`LiveUiJourneyTest` find no credentials and skip themselves there. A machine with a filled
`.test.env` and a device attached is the only place a real server is ever exercised, and
`scripts/run-tests.sh` is what does it — about 45 s for the whole live layer on a Pixel 5, the UI
journey included. Do this before tagging, so the manual pass below only has to prove the *release*
build rather than the app:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.gmail.volkovskiyda.jellyshelf.live
```

A green run means sign-in, the sync-scope picker, sync, the watched toggle, playback with a resume
point, and every undo held up against the real server. `0 skipped` in the output is the part to
check: a blank or unreachable `.test.env` makes both tests skip rather than fail, which is easy to
mistake for a pass.

1. Install a release build: an App Distribution build, a Releases-page APK, or a local
   `./gradlew :app:installRelease` (needs `keystore.properties`). It installs alongside debug —
   the bare `com.gmail.volkovskiyda.jellyshelf` id, unbadged icon.
2. Open `.test.env` and sign in with `JELLYFIN_SERVER_URL` + `JELLYFIN_USERNAME` +
   `JELLYFIN_PASSWORD`. Release builds accept **`https://` only** — for server and index URL both;
   a plain-HTTP LAN server can only be exercised from a debug build, by design.
3. Metadata index URL: leave the app's field to its **fill-from-server** default
   (`<server>/jellyshelf-index.json`) unless `.test.env` sets `JELLYFIN_INDEX_URL` to somewhere
   else.
4. Sync scope: if `JELLYFIN_SYNC_FOLDER` is set, pick that folder in the sync-scope browser
   (`JELLYFIN_SYNC_FOLDER_ID` is the same folder's item id, for tests that need it directly).
   Otherwise leave the scope at "all collections".
5. **Sync now**, then check the library populates and a video plays.

After editing `.test.env`, refresh the local secrets snapshot so its `verify` stays green:
`./internal/backup-secrets.sh backup` (maintainer-local — the script lives in the git-ignored
`internal/`).

**Keep the mapping file.** Release builds are R8-obfuscated, so a stack trace from a released APK is
unreadable without the `mapping-<version>.<versionCode>.txt` from that exact build. Firebase
Crashlytics gets its own copy automatically; the release asset is for anyone reading a trace pasted
into an issue.

## One-time setup

Repository secrets — run these from a checkout that has the real signing files, and never paste
their contents anywhere:

```sh
base64 -i keystore.properties  | gh secret set KEYSTORE_PROPERTIES_BASE64
base64 -i jellyshelf-release.jks | gh secret set KEYSTORE_BASE64
gh secret set FIREBASE_SERVICE_ACCOUNT < firebase-ci.json && rm firebase-ci.json
```

`FIREBASE_SERVICE_ACCOUNT` is a JSON key for the `firebase-ci` service account in the
`jellyshelf-3dfc8` project. To recreate it from scratch:

```sh
gcloud services enable iam.googleapis.com toolresults.googleapis.com testing.googleapis.com \
  firebaseappdistribution.googleapis.com --project=jellyshelf-3dfc8

gcloud iam service-accounts create firebase-ci --project=jellyshelf-3dfc8 \
  --display-name=firebase-ci \
  --description="GitHub Actions: Firebase App Distribution uploads and Test Lab runs"

gcloud projects add-iam-policy-binding jellyshelf-3dfc8 --condition=None \
  --member=serviceAccount:firebase-ci@jellyshelf-3dfc8.iam.gserviceaccount.com \
  --role=roles/editor

gcloud iam service-accounts keys create firebase-ci.json --project=jellyshelf-3dfc8 \
  --iam-account=firebase-ci@jellyshelf-3dfc8.iam.gserviceaccount.com
```

**Why `roles/editor` and not something narrower.** The `testlab` job writes to the free Test Lab
results bucket that Firebase provides, and
[gcloud requires `roles/editor` on the principal to use it](https://firebase.google.com/docs/test-lab/android/iam-permissions-reference).
`roles/cloudtestservice.testAdmin` is only sufficient alongside `roles/firebase.analyticsViewer`
*and* a `--results-bucket` you own — a bucket, a lifecycle rule and a workflow flag, to narrow a key
that is already confined to this one throwaway project. Editor also subsumes App Distribution, so
the one binding covers both jobs.

`toolresults.googleapis.com` is easy to miss: Test Lab stores every run's results through it, so the
`testlab` job fails without it even though `testing.googleapis.com` is on.

Then, in the Firebase console, open **App Distribution**, and create a tester group named `testers`.

## Signing

`keystore.properties` and `jellyshelf-release.jks` live at the repo root and are git-ignored; CI
recreates both from the secrets above via [`scripts/restore-signing.sh`](../scripts/restore-signing.sh).
A checkout without them still builds — the release signing config is simply not created and the APK
comes out unsigned, which is what pull-request CI wants.

`debug.keystore` is different: it *is* committed, so every machine and CI sign debug builds with the
same certificate. The Firebase API key is restricted to this project's two package names and their
signing certificates, which only works if that certificate is stable.

## Baseline profile

Nothing to do at release time: the profile is committed, and both workflows package whatever is
checked in. It is generated by hand against a connected arm64 device
(`./gradlew :app:generateReleaseBaselineProfile`) and refreshed when the app's startup path changes
shape — a handful of times a year, not once per release. Runbook, including what to verify before
committing a new one: [BASELINE-PROFILE.md](BASELINE-PROFILE.md). The click path it drives, and why,
is on [`BaselineProfileGenerator`](../baselineprofile/src/main/kotlin/com/gmail/volkovskiyda/jellyshelf/baselineprofile/BaselineProfileGenerator.kt).
