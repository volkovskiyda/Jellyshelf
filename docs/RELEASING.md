# Releasing

Two delivery channels, both driven by CI. Neither needs a version edit in `build.gradle.kts`:
`versionCode` is always `git rev-list --count HEAD` — monotonic across both workflows, so a build
from one can never install backwards over a build from the other — and `versionName` is
`<latest tag>.<versionCode>` in both (the `distribute` job derives the tag with
`git describe --tags --abbrev=0`; the Release workflow takes the pushed tag).

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

The APK's `versionName`, the asset filenames, and the release title all carry the versionCode —
tag `v1.0` at commit 164 reports `1.0.164`, publishes `jellyshelf-1.0.164.apk`, and titles the
release `v1.0.164` (the tag itself stays `v1.0`). The tag by itself does not identify a build, and
versionCode is the only identifier shared with the App Distribution channel, which is what lets a
mapping file be matched to a crash by hand.

Plain `git tag v<version> && git push origin v<version>` does the same thing.

## In-app update check

The app can tell a user that a newer build exists. There is no Play listing to ask, so the user
picks which of the two channels above they installed from, in **Settings → Updates**.

**Both channels ship the same signed release APK.** GitHub Releases are tag-driven and curated;
App Distribution gets every green `main` push. Neither is a debug channel — a point worth stating,
because it has been assumed otherwise: `release.yml` runs `assembleRelease` with the restored
keystore, and the R8 mapping attached to each release only exists for a minified release build.

The behaviour, in full:

- **Off by default.** Nothing touches the network until the user opts in — which is also what keeps
  Test Lab, the live UI journey and the baseline-profile runs unaffected.
- **Release builds only**, and in a debug build the Settings section is not rendered at all. A debug
  install is a different package (`…jellyshelf.debug`) at `versionCode = 1`, so every published
  release would read as newer. A locally assembled release without `-PbuildNumber` reports `1` too,
  and is skipped for the same reason.
- **At most one check a day**, on cold start, plus whatever "Check now" the user taps. The GitHub
  check is an unauthenticated `releases/latest` GET against a 60-per-hour limit, so it needs no
  token — do not add one.
- **At most one dialog a day**, whatever was found and on whichever channel.
- **"Not now" is a snooze, not a mute.** It silences *that build* for 7 days, but a **newer** build
  prompts as soon as it is found. Install 1.0, dismiss the 1.1 prompt, and if 1.2 ships the next
  daily check offers it; if 1.2 never ships, 1.1 comes back on day 7.
- The dialog appears **only on the Library tab** — never over Categories, Detail, or the player.
  A check the user asked for with "Check now" is the exception: its answer appears wherever they
  asked, and does not spend the once-a-day dialog allowance.
- "Check now" that finds nothing newer says **"You're up to date"**. It skips the politeness windows
  but not the version comparison — GitHub's `releases/latest` answers whether or not it is an
  upgrade, so without that rule a current user is offered the build they are running.
- A failed check reports the **actual reason** (not entitled, API blocked, sign-in cancelled), never
  a silent "no update available".
- The App Distribution install narrates itself in a snackbar (preparing → downloading % →
  installing), swipeable away; a failure states its reason for 3 seconds and clears itself.

### Kill switch: `force_legacy_tester_sign_in`

The tester sign-in does **not** use the SDK's `signInTester()`. That call hardcodes
`FLAG_ACTIVITY_NEW_TASK` on its Custom Tab, which puts the browser in its own task — a stray card in
Recents, Back landing on the sign-in page, and no way for the app to close it, because nothing can
finish an activity in another app's task. `TesterSignInLauncher` opens the same URL itself, without
that flag, so the tab is in our task and gets closed when the redirect lands.

That rests on two undocumented internals of a beta SDK (`16.0.0-beta20`, the newest published), both
read out of the AAR with `javap -c`:

1. the sign-in URL in `TesterSignInManager.SIGNIN_REDIRECT_URL`, and
2. `TesterSignInManager.onActivityCreated`, which calls `SignInStorage.setSignInStatus(true)`
   whenever a `SignInResultActivity` is created — it never checks that the SDK started the flow,
   which is why a redirect the app caused still registers as a sign-in.

The app detects and falls back on its own for everything it *can* see (no foreground activity, no
Custom Tabs browser, no installation id, a launch that throws). What it cannot see is Google
changing the URL: the tab would open on an error page and the user would return not signed in, which
reads as a cancelled sign-in. **That is the symptom to watch for**, and this is the lever:

| | |
|---|---|
| Parameter | `force_legacy_tester_sign_in` |
| Type | Boolean |
| Default | `false` — no parameter needs to exist in the console for that |
| Effect when `true` | The launcher is never asked; `signInTester()` runs, Chrome-task behaviour and all |

Set it in **Firebase console → Remote Config → Add parameter**, then Publish. Conditions apply as
usual, so it can be scoped to the affected app versions rather than everyone — target the release
that shipped the break and leave later ones on the new path.

Two things about the latency, both deliberate:

- The app fetches at cold start with a 12-hour minimum interval, and reads the **last activated**
  value at the moment of sign-in. So a flag published now takes effect on the next launch of an app
  that has fetched it — not mid-session, and not on a device that has not been opened since. This is
  a kill switch, not a feature flag; do not reuse the shape for anything needing to act now.
- Debug builds never fetch, matching Crashlytics and Performance, which also keeps instrumented
  tests and Test Lab off it. They always see the default.

Remote Config needs no API-key change: `firebaseremoteconfig.googleapis.com` and
`firebaseremoteconfigrealtime.googleapis.com` are already in the key's `apiTargets` (added for
Performance, 2026-08-05).

### Prerequisite for the App Distribution channel

1. ~~Enable `firebaseapptesters.googleapis.com`~~ — **already enabled** on `jellyshelf-3dfc8`
   (verified 2026-08-06). Nothing to do.
2. ~~Add it to the API key's `apiTargets`~~ — **done 2026-08-06.** Kept here because both
   `--api-target` and `--allowed-application` **replace their whole lists**, so any future edit must
   repeat every API and every app/SHA-1 pair in one invocation or silently drop the rest. The
   Android key is restricted, so a missing entry makes calls fail closed with `API_DISABLED` —
   which the app renders as exactly that, rather than as "no update".

   ```sh
   gcloud services api-keys update \
     projects/967566106537/locations/global/keys/7c345f60-52f6-4530-891f-659b995fb649 \
     --project=jellyshelf-3dfc8 \
     --api-target=service=firebaseinstallations.googleapis.com \
     --api-target=service=firebaseremoteconfig.googleapis.com \
     --api-target=service=firebaseremoteconfigrealtime.googleapis.com \
     --api-target=service=firebaseapptesters.googleapis.com \
     --allowed-application=package_name=com.gmail.volkovskiyda.jellyshelf,sha1_fingerprint=39f33a734e0e9593059d68d843770a0d04822f7e \
     --allowed-application=package_name=com.gmail.volkovskiyda.jellyshelf.debug,sha1_fingerprint=3fe00757a5b0241b2a59c8c552fbc8358d36852c \
     --allowed-application=package_name=com.gmail.volkovskiyda.jellyshelf.benchmark,sha1_fingerprint=39f33a734e0e9593059d68d843770a0d04822f7e
   ```

   Only `firebaseapptesters.googleapis.com` is needed. `firebaseappdistribution.googleapis.com` is
   the *admin* API the CI service account uses for uploads, and is never called with this key.
3. The tester must be in the `testers` group (`ci.yml`) and have accepted the invitation.
4. Verify on a real device, on a **release** build. The `.debug` package is a separate Firebase app
   with no releases at all, and the Settings section is not rendered there anyway.

### What the SDK adds to every release APK

`firebase-appdistribution` is a `releaseImplementation`, so its merged manifest contributions land in
**every** release build whether or not the user opts in:

- `REQUEST_INSTALL_PACKAGES`, `POST_NOTIFICATIONS`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`
- an **exported** `SignInResultActivity` handling `intent://appdistribution/<applicationId>`, plus an
  `InstallActivity`, two feedback activities and a `FileProvider`

This was a deliberate trade, not an oversight: the API-only artifact (`firebase-appdistribution-api`)
is a stub that cannot detect releases at all, so the full SDK is unavoidable if the channel is to
exist. The only way to drop the permissions is to drop the channel. Do not "clean this up" without
re-taking that decision.

The same dependencies reach `nonMinifiedRelease` and `benchmarkRelease` — those build types are
derived from `release` and inherit it — so the profiling APKs carry the permissions too. They install
as `.benchmark` and are never shipped.

The two App Distribution artifacts are **not BoM-managed**: `firebase-bom` does not list them, so
they pin their own version in `libs.versions.toml` and need their own bump. Upstream they are still
**beta**.

The precedent for editing the restricted key — including before/after snapshots — is
`internal/finished/20260805-firebase-performance-plan/01-api-key-and-console.md`, and the two
channels this reads from were set up by `internal/finished/release-ci-plan/`. Both are git-ignored
working notes, not part of the repo.

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
