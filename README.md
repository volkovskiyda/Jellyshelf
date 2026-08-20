# Jellyshelf

An Android companion for a self-hosted **Jellyfin** library of yt-dlp-archived YouTube
videos. It categorizes the archive (automatically by channel, year, month, duration and
YouTube category, plus your own manual categories), plays it on a built-in Media3 player,
and syncs watch status with Jellyfin over the REST API. Playback can also be handed to an
external player or to the Jellyfin web UI — the choice is a setting on the play button.

No Jellyfin server to hand? **Settings → Try demo** runs the whole app on a seeded library —
see [Demo mode](#demo-mode--try-it-without-a-server).

`com.gmail.volkovskiyda.jellyshelf` · single app module (plus a `:baselineprofile` tooling
module) · Compose + Navigation 3 + Adaptive ·
Media3 (ExoPlayer + MediaSession) · Room · Ktor (OkHttp engine) / kotlinx.serialization ·
WorkManager · DataStore · Coil · Koin · bundled yt-dlp (youtubedl-android).

## The data pipeline

```
videos/*.mp4  ──fetch-youtube-metadata.sh──►  *.info.json (sidecars)
*.info.json   ──build-library-index.sh────►  jellyshelf-index.json  ──HTTP──┐
                                                                            ▼
Jellyfin  ──REST (X-Emby-Token)──►  items + watch state  ──join by YouTube id──►  Room  ──►  UI
                                                                            ▲
                              bundled yt-dlp  ──in-app fetch, per video──────┘
```

1. **`scripts/fetch-youtube-metadata.sh`** (requires [`yt-dlp`](https://github.com/yt-dlp/yt-dlp)
   on `PATH`) — recurses your download dir, extracts the 11-char YouTube id from each
   filename, writes a `<video>.info.json` sidecar next to the video (same stem). Idempotent
   (skips videos that already have a sidecar) and resumable (safe to Ctrl-C and re-run).
   ```bash
   scripts/fetch-youtube-metadata.sh --dry-run /media/youtube   # print "id <- filename", fetch nothing
   scripts/fetch-youtube-metadata.sh /media/youtube             # write the sidecars
   ```
   Options: `--sleep N` throttles requests (default `2`s); `--exts "mp4 mkv …"` overrides the
   scanned extensions. For age/region-gated videos, pass yt-dlp flags via `YTDLP_OPTS`:
   ```bash
   YTDLP_OPTS="--cookies-from-browser firefox" scripts/fetch-youtube-metadata.sh /media/youtube
   ```
   Filenames that yield no id and failed fetches are logged to `<DIR>/metadata-failures.log`.
2. **`scripts/build-library-index.sh`** (requires [`jq`](https://jqlang.github.io/jq/)) —
   aggregates every `*.info.json` under a dir into one flat `jellyshelf-index.json` array
   (id, title, channel, channelId, duration, uploadDate, tags, categories, description,
   thumbnail, chapters, fetchedAt). Re-run it whenever you add videos.
   ```bash
   scripts/build-library-index.sh /media/youtube jellyshelf-index.json   # args: DIR [OUT]
   ```
   Then serve the output over HTTP so the phone can reach it — see
   [Serving the index](#serving-the-index).
3. **The app** pulls Jellyfin items (→ Jellyfin ItemId, watch state, duration) and the
   index (→ channel, tags, upload date, description, chapters, thumbnail), joins them by
   YouTube id into Room, and auto-groups along five dimensions: channel, year, month,
   duration band and YouTube category. The index URL is optional — without it the app
   falls back to Jellyfin's own metadata (no channel grouping).
4. **The app can also skip the scripts entirely.** It bundles yt-dlp
   (youtubedl-android), so any video Jellyfin has but the index doesn't can be filled in
   from the device: per video from its detail screen, in bulk from the **Others →
   Uncategorized** filter, and automatically during a sync while fewer than ten videos are
   missing metadata. Newest extraction wins, so an in-app fetch is not overwritten by a
   staler index entry on the next sync.

## Serving the index

`jellyshelf-index.json` just needs to be reachable from the phone over HTTP(S) at a stable
URL you paste into **Settings → Metadata index URL**. Any static file server works; two
common setups below. Regenerate the file (step 2) and it's picked up on the next sync — no
server restart needed.

### nginx

Drop the file into a webroot and serve it directly. This exposes only that one path:

```nginx
# /etc/nginx/conf.d/jellyshelf.conf
server {
    listen 80;
    server_name media.example.com;

    # file lives at /srv/jellyshelf/jellyshelf-index.json
    location = /jellyshelf-index.json {
        root         /srv/jellyshelf;
        default_type application/json;
        add_header   Cache-Control "no-cache";   # always serve the freshest index
    }
}
```

`https://media.example.com/jellyshelf-index.json` is then your index URL. Point
`build-library-index.sh`'s output straight at the webroot to skip a copy step:

```bash
scripts/build-library-index.sh /media/youtube /srv/jellyshelf/jellyshelf-index.json
```

### Traefik

Traefik is a reverse proxy, not a file server, so put a tiny static server behind it and let
Traefik terminate TLS. This `docker-compose.yml` serves the file over HTTPS with an
automatic Let's Encrypt certificate:

```yaml
services:
  jellyshelf-index:
    image: nginx:alpine
    volumes:
      - /srv/jellyshelf:/usr/share/nginx/html:ro   # jellyshelf-index.json goes here
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.jellyshelf.rule=Host(`media.example.com`) && Path(`/jellyshelf-index.json`)"
      - "traefik.http.routers.jellyshelf.entrypoints=websecure"
      - "traefik.http.routers.jellyshelf.tls.certresolver=le"
      - "traefik.http.services.jellyshelf.loadbalancer.server.port=80"
```

This assumes a Traefik instance with a `websecure` (443) entrypoint and an ACME
`certresolver` named `le` already configured. If Jellyfin itself runs behind the same
Traefik, add this as another labelled service on the shared proxy network and both share
one certificate/host. Index URL: `https://media.example.com/jellyshelf-index.json`.

> Serving over HTTPS is recommended when the index leaves your LAN, and **required** by release
> builds — only debug builds allow cleartext traffic (see [Build](#build)). A plain `http://` URL
> on a release build fails before it reaches the network; the app says so instead of surfacing
> the platform's raw "CLEARTEXT communication … not permitted" error.

## Demo mode — try it without a server

**Settings → Try demo** fills the library with ~60 seeded videos and opens it. No server, no
network, no account: everything downstream of a populated library — browsing, search, the duration
filter, the auto-categories, continue-watching, the in-app player — works exactly as it does after
a real sync, because the seeder builds its rows and derives its categories with the same code a
sync uses.

There is a second way in, through the real sign-in form, which demonstrates the sign-in
choreography the button skips:

| Field | Value |
| --- | --- |
| Server URL | `jellyfin` |
| Username | `demo` |
| Password | anything — except `incorrect` |

Password `incorrect` is deliberate: it shows the genuine authentication-failure state, with no
server anywhere to reject anything. Any other password enters the demo. These values are matched
before any URL normalization and before any network call, and none of them is ever stored as a
connection.

**Leaving** is the ordinary **Sign out** — its confirmation dialog reads "Leave demo?" while a
demo is loaded — which drops the seeded rows and the demo flag together. Connecting to a real
server clears the demo data first, so seeded rows never meet a real sync — the Settings screen
says so while a demo is loaded.

The dataset is [`app/src/main/assets/demo/library.json`](app/src/main/assets/demo/library.json),
a real, valid example of the metadata index format that
[`scripts/build-library-index.sh`](scripts/build-library-index.sh) emits — worth a look if you are
setting that pipeline up. Watch state is derived in code by position in the list rather than
authored in the JSON, so the watched / part-watched / unwatched mix is guaranteed whatever the
content is edited to.

The bundled assets are regenerable: [`scripts/make-demo-thumbnails.sh`](scripts/make-demo-thumbnails.sh)
draws the thumbnails, and [`scripts/make-demo-clip.sh`](scripts/make-demo-clip.sh) cuts the video.

> Every demo video plays the same ten-second clip: an excerpt from **Big Buck Bunny**,
> © 2008 Blender Foundation, [peach.blender.org](https://peach.blender.org), used under
> [CC-BY 3.0](https://creativecommons.org/licenses/by/3.0/).

## Setup in the app (Settings tab)

1. **Server URL** — e.g. `https://192.168.1.10:8096`. Typed without a scheme, it is read as
   `https://`. Release builds accept `https://` only; a plain-HTTP LAN server needs a debug build.
2. **Username + password → Sign in** — exchanges them for a *user-scoped* access token
   (`POST /Users/AuthenticateByName`). Only the token is stored; the password is discarded as
   soon as the token comes back. The token identifies the user, so there is nothing to pick.
3. **Metadata index URL** (optional) — where you serve `jellyshelf-index.json`.
4. **Sync scope** (optional) — browse the server's collections and pick one folder to sync
   instead of everything. Narrowing it deletes the now-out-of-scope videos locally right away;
   videos that merely stop appearing in an unchanged scope get a grace period of three syncs
   first, so a Jellyfin rescan doesn't empty the library.
5. **Sync now** — first sync; a WorkManager job then re-syncs every 2 hours.

**Appearance → Theme** overrides light/dark independently of the system: tapping the switch walks
light → auto → dark and back, and the change animates as a circular reveal from the tap. Auto is
the default and follows the system.

**Updates** can tell you when a newer build of the app exists — off by default, and switched on in
Settings by picking the channel you installed from (GitHub Releases or Firebase App Distribution).
It checks at most once a day, prompts at most once a day, and "Not now" silences that build for a
week while a *newer* one still prompts. Release builds only; the section is not there in a debug
build. Details in [docs/RELEASING.md](docs/RELEASING.md).

If the server later rejects the token (password change, session revoked), the app says
"session expired — sign in again" and stops syncing rather than falling back to anything else.

### Advanced: API key instead of sign-in

Under **Advanced** there is still an **API key** field (Jellyfin → Dashboard → API Keys), for
setups where a password login isn't an option. It is a deliberate second choice: a Jellyfin API
key is **server-wide and admin-scoped**, so any leak — player history, casting, server access
logs — exposes the whole server rather than one user. In this mode you also have to
**Connect & load users** and pick whose watch state to read and write, because the key alone
doesn't say. Whichever credential is in play travels as the `X-Emby-Token` header; the user
token wins whenever one is present.

## Watching a video

A video's detail screen has one **Play** button with a dropdown next to it. The dropdown picks
how *every* video plays from then on — it is the setting, there is no Settings-screen row:

- **Play in app** (default) — the built-in Media3 player, described below.
- **External player** — fires an `ACTION_VIEW` intent at the Jellyfin static stream
  (`/Videos/{id}/stream?static=true`) → VLC / MX / any player.
- **Open in web** — deep-links to the Jellyfin web details page
  (`/web/index.html#/details?id={id}`).

### The in-app player

Immersive fullscreen, free orientation, screen kept awake while playing. It plays the direct
stream and, if the device can't decode it, retries once through the server's HLS transcode
(`/Videos/{id}/main.m3u8`) at the same position — no codec constraints are sent, so Jellyfin
transcodes to H.264/AAC, which anything decodes. If the transcode fails too, the error points at
the External player mode.

- **Queue** — opening a video from a list queues that whole list, so previous/next step through
  it. Opened from the media notification there is no list, so the queue is just that one video.
- **Chapters** — parsed from the yt-dlp `chapters` field, or from YouTube-style `0:00 Intro`
  timecodes in the description (only when they follow YouTube's own rules: three or more, first
  at zero, ascending, within the runtime — otherwise none, since wrong chapters are worse than
  no chapters). Previous-chapter restarts the current one when more than 3 s into it.
- **Seeking** — 10 s back / 30 s forward, deliberately asymmetric. Drag horizontally to scrub
  with a preview, seeking on release. Press and hold for 3× speed; the speed menu offers
  0.5×–3×, and the speed picked there is remembered — later videos, and later launches, start at
  it. The press-and-hold 3× is not: it is a gesture, and it ends with the finger.
- **Background playback** — a `MediaSessionService` owns the player, so playback survives leaving
  the screen and system surfaces (notification, output switcher, Android Auto) can drive it.
  Tapping the notification reopens the player on whatever is playing.
- **Resume** — the position is saved locally every 10 seconds and on pause, so process death
  can't lose it, and reported to Jellyfin through the live playback session (see
  [Watch state](#watch-state)). Positions only start counting once the
  video is genuinely under way (a tenth of it, or one minute, whichever is smaller), so stepping
  through a queue can't overwrite a resume point you earned with one you didn't.

The media notification needs `POST_NOTIFICATIONS` on API 33+, requested from the player screen.
Denied, playback still works — the notification just stays hidden.

### Where the credential goes

The in-app player always sends it as an `X-Emby-Token` request header, never in the URL.

For the **external player** that is the default too, passed via the intent's `headers` extra,
because an `ACTION_VIEW` URL is handed to whichever app the user picks and then persists in that
player's recent-files list, its logs, and any cast target. A header is used for the request and
not retained.

A chooser can't know in advance which player will be picked, so this can't be decided per player.
If yours ignores the headers extra (VLC's support has varied by version) playback fails with a
401 — turn on **Settings → Advanced → Token in playback URL** to put it back in the query
instead. That toggle applies to the external-player intent only.

### Watch state

Syncs both ways: the app reads `Played` / `PlaybackPositionTicks` / `PlayCount` from Jellyfin on
every sync, and writes back when you mark watched/unwatched or finish a video. A finished video
is marked played through `/PlayedItems` (the endpoint that actually increments `PlayCount` and
stamps `LastPlayedDate`).

The in-app player follows Jellyfin's own session flow while a video plays: a start report once
playback is genuinely under way, progress every 10 seconds (and once more on a pause), and a stop
report carrying the final position. So the video appears as "now playing" on the server dashboard,
and the *server's* resume thresholds — not this app — decide when a partway stop counts as watched
or lands in "Continue Watching"; a video can even tip over to watched mid-play once it crosses the
server's threshold.

An external player reports nothing while it runs, so there is no session for the server to
threshold: a partway stop there writes its resume position directly to the item's user data, which
is what puts it in "Continue Watching". MX Player and VLC report their position back on exit, so
the external path records progress too; other players simply won't.

## Endpoints to verify against your Jellyfin version

The client (`data/remote/JellyfinApi.kt`) — hand-written Ktor calls, not a Retrofit interface —
targets standard endpoints; confirm these against your server build and adjust if needed:
- `POST /Users/AuthenticateByName` — sign-in. Needs the `MediaBrowser …` authorization header,
  which is the one call that doesn't carry `X-Emby-Token`.
- `GET /Users` — the user list, in advanced API-key mode only.
- `GET /Users/{userId}/Views`, `GET /Items?IsFolder=true` — the sync-scope folder browser.
- `GET /Items` — the paged library listing (`SortName` ascending, 200 per page). Watch state
  reads come with it and are reliable.
- `POST` / `DELETE /Users/{userId}/PlayedItems/{itemId}` — mark (un)watched, and what a finished
  video reports through.
- `POST /Sessions/Playing`, `POST /Sessions/Playing/Progress`, `POST /Sessions/Playing/Stopped` —
  the in-app player's live session reporting; the stop report is what carries an in-app partway
  resume position, leaving the server to threshold it.
- `POST /Users/{userId}/Items/{itemId}/UserData` — the *external-player* resume-position write
  ("Continue Watching"), where no live session exists.
- `DELETE /Items/{itemId}` — "Remove watched", which deletes the media file too.
- `POST /Playlists` — create a playlist from a category.
- `GET /Videos/{id}/stream?static=true`, `GET /Videos/{id}/main.m3u8` — direct play and the
  transcode fallback. `GET /Items/{id}/Images/Primary` — thumbnails.

## Build

```bash
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # to a connected device/emulator
./gradlew :app:assembleRelease      # R8-shrunk; signed only if keystore.properties exists
```

Minimum: `minSdk 31`, `compileSdk 37`. Debug builds allow cleartext HTTP for LAN servers, and
install alongside release (`.debug` application id, badged launcher icon).

**arm64 only.** The bundled yt-dlp ships a Python runtime per ABI, so the APK is restricted to
`arm64-v8a` to avoid carrying a second copy. Physical devices are effectively all arm64; an
**x86_64 emulator cannot install the APK** (`INSTALL_FAILED_NO_MATCHING_ABIS`) — use an arm64
system image.

**Signing** is optional and reads `keystore.properties` at the repo root (see
[Environment config](#environment-config)). Without that file `assembleRelease` still configures
and builds — it just produces an unsigned APK, which is what keeps CI from needing a keystore.

Versions are never edited by hand: `versionCode` is the commit count and `versionName` comes from
the release tag, both supplied by CI. Every green push to `main` reaches testers through Firebase
App Distribution, and a `v*` tag publishes a signed APK to the Releases page —
see [docs/RELEASING.md](docs/RELEASING.md).

**Baseline profile.** The release APK ships a committed baseline profile, so a fresh install runs
AOT-compiled through launch and the first scroll instead of waiting for ART's background dexopt to
catch up. Generating it drives the real app on a device, which the arm64 constraint above puts out
of CI's reach — so it is generated by hand and checked in:

```bash
./gradlew :app:generateReleaseBaselineProfile   # physical arm64 device, awake and unlocked
```

It installs as its own package — `com.gmail.volkovskiyda.jellyshelf.benchmark`, from the suffix the
profiling variants carry, badged **Jellyshelf Bench** on the launcher — so a generation run never
touches the release build or `.debug` on the device. The run drives a **real server** when `.test.env` is filled (see
[Environment config](#environment-config)): it signs in, syncs, and streams a video, so the profile
covers what a first launch actually loads — the network stack, sync, and ExoPlayer's streaming path.
With no `.test.env` it falls back to demo mode, which needs no server but leaves all of that out.
Regenerate when the startup path changes shape rather than on every commit, and check the output
before committing it — two failure modes produce a plausible-looking but worthless profile. Runbook:
[docs/BASELINE-PROFILE.md](docs/BASELINE-PROFILE.md).

## Testing

One command runs every layer available and prints a single verdict:

```bash
scripts/run-tests.sh              # everything: static analysis, unit, screenshots, on-device
scripts/run-tests.sh --host-only  # skip the device layer even if one is attached
```

It aggregates all of it into one page at `app/build/test-summary/index.html`, on failures too.
With more than one device attached it asks which to use; `--device`, `--all` or a `.device` file
answer that up front. Only one checkout at a time may drive a device — a second run waits for the
first, because the live tests share one Jellyfin account
([docs/WORKTREES.md](docs/WORKTREES.md)). Within a single run across several devices the same
constraint applies: the offline tests fan out in parallel, then the live tests run on each device
in turn, so two journeys never hit the server at once. The layers can also be run individually:

```bash
./gradlew detekt ktlintCheck :app:lintDebug  # static analysis
./gradlew ktlintFormat                    # fix the formatting half of it in place
./gradlew :app:testDebugUnitTest          # JVM unit tests incl. MockEngine networking tests
./gradlew :app:validateDebugScreenshotTest  # Compose screenshot goldens (host-side, LayoutLib)
./gradlew :app:connectedDebugAndroidTest  # instrumentation tests (needs a device/emulator)
```

- **Static analysis** — three tools with no overlap between them: detekt for Kotlin complexity,
  naming and style, ktlint for formatting, Android lint at `checkAllWarnings` for the platform
  checks. None of them carries a baseline by design: any finding fails the build, so fix the
  finding rather than regenerating one. ktlint's half is usually one command away —
  `./gradlew ktlintFormat`. Its rules come from `.editorconfig`, which Android Studio reads too,
  so the editor and the build agree; the code style there is `intellij_idea` rather than ktlint's
  own `ktlint_official`, which would restyle most of the repo and then fight Ctrl-Alt-L. (detekt
  used to run the ktlint rule set itself, through `detekt-formatting`. That plugin was dropped
  when ktlint arrived — two engines reporting the same finding under two rule ids, free to
  disagree on version, is worse than either alone.)
- **Unit tests** (`src/test`) run on the JVM with no device. `JellyfinApiTest` drives the Ktor
  client over a `MockEngine` to lock in the request-body wire format, URL/header construction,
  unknown-key tolerance, and error mapping. The decision-heavy pieces — merge, prune policy,
  resume rules, chapter parsing, search ranking — are pure functions tested here rather than
  on a device.
- **Screenshot goldens** (`src/screenshotTest`) render previews host-side. Reference PNGs live in
  Git LFS, so a clone needs `git lfs install`. When a diff is an intentional UI change, re-bake
  with `./gradlew :app:updateDebugScreenshotTest`.
- **Instrumentation tests** (`src/androidTest`) run in a real APK: Compose behavior tests, Room
  DAO round-trips, the on-device kotlinx.serialization path, and a browse-cost benchmark over a
  10k-row library. Compose tests run the accessibility checks, so an unlabelled control, an
  undersized touch target or low contrast fails the suite. There is no Robolectric on purpose.
  With no device attached these layers **skip** rather than fail, in Gradle and in the script
  both.
- **Live tests** (`src/androidTest/…/live/`) hit a real Jellyfin and are **opt-in via `.test.env`**.
  See [Demo and live tests](#demo-and-live-tests) below for what they cover and how to run each.

**The demo library doubles as the test fixture** for anything whose behaviour depends on real
content. Search and duration filtering are the case in point: they never reach Jellyfin — a Room
read plus in-memory ranking — so a real server would add nondeterminism and no coverage, while 60
hand-authored videos make the expected results nameable. They are covered at each layer over that
one dataset: the ranking host-side (`DemoLibrarySearchTest`, through the production seed mapper),
the SQL range and the two composed (`DemoLibrarySearchInstrumentedTest`, against real Room), the
controls' contracts (`LibraryContentTest`), and one user typing into the real app end to end
(`DemoLibrarySearchFlowTest`). Scale is a separate question, and `BrowseCostBenchmark` answers it
over 10k rows.

CI (`.github/workflows/ci.yml`) runs `scripts/run-tests.sh --host-only` plus `assembleDebug` on
every push to `main` and every PR, and uploads the summary and reports as artifacts.

### Demo and live tests

Almost everything runs with **no server**: demo mode is the fixture, and `scripts/run-tests.sh` on a
fresh checkout is green with nothing configured. Two tests in `src/androidTest/…/live/` are the
exception — they need a real Jellyfin, and they are the only ones that do.

| | Needs | Touches the server |
|---|---|---|
| **Demo / offline tests** — everything else | nothing | no |
| **`LiveEndpointTest`** — endpoints deserialize against a live server | `.test.env` | reads only |
| **`LiveUiJourneyTest`** — the real app against that server, end to end | `.test.env` **with a sync scope** | writes, then undoes |

All of it runs from `scripts/run-tests.sh`. To run one class on its own, filter through the
instrumentation runner — `--tests` is a unit-test option and `connectedAndroidTest` rejects it:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.gmail.volkovskiyda.jellyshelf.live.LiveUiJourneyTest
```

To enable both: copy `.example.test.env` → `.test.env` and fill in the server URL and a username +
password — ideally a dedicated **non-admin** test user. The tests sign in the way the app does
(`AuthenticateByName`) and drive everything with the returned user-scoped token; no admin API key is
involved. They **skip automatically** (never fail) when the config is missing or the server is
unreachable, so nothing here breaks a checkout that has neither. The same file is the reference
sheet for smoke-testing a release build by hand ([docs/RELEASING.md](docs/RELEASING.md)) and the
config the baseline-profile generator reads
([docs/BASELINE-PROFILE.md](docs/BASELINE-PROFILE.md)).

Once `.test.env` is filled, both run as part of a plain `scripts/run-tests.sh` — deliberately, since
a live check nobody remembers to run is a live check nobody runs. Add about a minute for the journey.
One caveat there: `SyncSchedulerInstrumentedTest` swaps WorkManager for its test double, and that
swap is process-wide, so in a *whole-suite* run no worker can execute afterwards. The journey detects
that and runs the same `sync()` the worker would have called; run the class on its own (above) to
exercise the worker path too.

**What the journey does, and what it puts back.** It signs in through the real form, picks the sync
scope, syncs, opens a video, marks it watched and unwatched again, plays it for real (paused, seeked
15% in, left), then browses Categories. Every write is verified on the server *and* undone: the
item's prior `UserData` is captured before anything touches it and written back afterwards, with the
restore itself asserted. The local slate is wiped at both ends, so the device is left signed out.

**It never syncs an unscoped server.** `JELLYFIN_SYNC_FOLDER` (the path the picker shows) or
`JELLYFIN_SYNC_FOLDER_ID` (that folder's id) — either alone is enough, and with neither the journey
skips rather than pulling a whole server through the app. The path is what gets the picker walked,
so it is the one that covers the picker; an id on its own is written straight to settings. Set both
and they have to name the same folder, which the test asserts.

It writes to **exactly one item** — `JELLYFIN_TEST_ITEM_ID` if `.test.env` pins one, otherwise the
library's first video, which must already be unwatched (it skips if not, saying so). That is what
makes the undo exact rather than approximate: Jellyfin's mark-unplayed resets `PlayCount` to 0, which
restores an item that started at 0 and cannot restore one that started at 3. Every other video is
read-only, and the bulk **Remove watched videos** action is never touched — it deletes files from the
server, which no test may do.

### Environment config

Local config lives in git-ignored `KEY=VALUE` files at the repo root, **not** `local.properties`.
Both are optional; copy the committed `.example.*` template and fill it in when you need one:

| File | Committed? | Purpose |
|------|-----------|---------|
| `.test.env` | git-ignored | Live-test + release smoke-test config: `JELLYFIN_SERVER_URL`, `JELLYFIN_USERNAME`, `JELLYFIN_PASSWORD`, plus optional `JELLYFIN_INDEX_URL` (defaults to `<server>/jellyshelf-index.json`), `JELLYFIN_SYNC_FOLDER` / `JELLYFIN_SYNC_FOLDER_ID` (the sync scope — `LiveUiJourneyTest` needs one of them), `JELLYFIN_TEST_ITEM_ID` (the one item the journey may write to). Absent → the live tests skip. |
| `.example.test.env` | committed | Template for `.test.env`. |
| `keystore.properties` | git-ignored | Release signing: `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`. Absent → release builds are unsigned. |
| `.example.keystore.properties` | committed | Template for `keystore.properties`. |
| `*.jks` | git-ignored | The keystore itself, sitting next to those files. |

Gradle's `loadEnv` reads both. The test config is passed to the instrumentation tests as runtime
runner arguments (`am instrument -e` extras), so it is never compiled into any `BuildConfig` and
changing it needs no rebuild.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

The one bundled asset that is not original to this project is the demo clip,
`app/src/main/assets/demo/sample.mp4` — a ten-second excerpt from **Big Buck Bunny**,
© 2008 Blender Foundation, [peach.blender.org](https://peach.blender.org), used under
[CC-BY 3.0](https://creativecommons.org/licenses/by/3.0/). The demo thumbnails and the demo
dataset are original.

```
Copyright 2026 Dmytro Volkovskiy

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

> Jellyshelf is an independent, third-party project. It is not affiliated with or
> endorsed by the Jellyfin project. "Jellyfin" is a trademark of its respective owner
> and is used here only to describe interoperability.
