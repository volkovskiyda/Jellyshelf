# Jellyshelf

An Android companion for a self-hosted **Jellyfin** library of yt-dlp-archived YouTube
videos. It categorizes the archive (auto-by-channel + your own manual categories) and
syncs watch status with Jellyfin over the REST API. Playback stays in Jellyfin (or any
external player) — Jellyshelf is the catalog + sync layer, not a video player.

`com.gmail.volkovskiyda.jellyshelf` · single-module · Compose + Navigation 3 + Adaptive ·
Room · Ktor (OkHttp engine) / kotlinx.serialization · WorkManager · DataStore · lightweight manual DI.

## The data pipeline

```
videos/*.mp4  ──fetch-youtube-metadata.sh──►  *.info.json (sidecars)
*.info.json   ──build-library-index.sh────►  jellyshelf-index.json  ──HTTP──┐
                                                                            ▼
Jellyfin  ──REST (X-Emby-Token)──►  items + watch state  ──join by YouTube id──►  Room  ──►  UI
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
   thumbnail). Re-run it whenever you add videos.
   ```bash
   scripts/build-library-index.sh /media/youtube jellyshelf-index.json   # args: DIR [OUT]
   ```
   Then serve the output over HTTP so the phone can reach it — see
   [Serving the index](#serving-the-index).
3. **The app** pulls Jellyfin items (→ Jellyfin ItemId, watch state, duration) and the
   index (→ channel, tags, upload date, description, thumbnail), joins them by YouTube id
   into Room, and auto-groups by channel. The index URL is optional — without it the app
   falls back to Jellyfin's own metadata (no channel grouping).

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

## Setup in the app (Settings tab)

1. **Server URL** — e.g. `https://192.168.1.10:8096`. Release builds accept `https://` only; a
   plain-HTTP LAN server needs a debug build.
2. **API key** — Jellyfin → Dashboard → API Keys → new key. Sent as the `X-Emby-Token`
   header on every request.
3. **Connect & load users** — API keys are server-wide, so pick which user's watch state
   to read/write.
4. **Metadata index URL** (optional) — where you serve `jellyshelf-index.json`.
5. **Sync now** — first sync; a WorkManager job then re-syncs every 6 hours.

## Watching a video

From a video's detail screen:
- **Play** — fires an `ACTION_VIEW` intent at the Jellyfin static stream
  (`/Videos/{id}/stream?static=true&api_key=…`) → opens in VLC / MX / any player.
- **Open in Jellyfin** — deep-links to the Jellyfin web details page
  (`/web/index.html#/details?id={id}`).

Watch state syncs both ways: the app reads `Played` / `PlaybackPositionTicks` / `PlayCount`
from Jellyfin on every sync, and writes back when you mark watched/unwatched.

## Endpoints to verify against your Jellyfin version

The client (`data/remote/JellyfinApi.kt`) — hand-written Ktor calls, not a Retrofit interface —
targets standard endpoints; confirm these against your server build and adjust if needed:
- `GET /Users`, `GET /Items` — stable.
- `POST` / `DELETE /Users/{userId}/PlayedItems/{itemId}` — mark (un)watched.
- `POST /Sessions/Playing/Progress` — position write; some versions prefer a play-session
  flow. Position *reads* come with `/Items` and are reliable.

## Build

```bash
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # to a connected device/emulator
```

Minimum: `minSdk 30`, `compileSdk 37`. Debug builds allow cleartext HTTP for LAN servers.

## Testing

```bash
./gradlew :app:testDebugUnitTest          # JVM unit tests incl. MockEngine networking tests
./gradlew :app:connectedDebugAndroidTest  # instrumentation tests (needs a device/emulator)
```

- **Unit tests** (`src/test`) run on the JVM with no device. `JellyfinApiTest` drives the Ktor
  client over a `MockEngine` to lock in the migrated request-body wire format, URL/header
  construction, unknown-key tolerance, and error mapping.
- **Instrumentation tests** (`src/androidTest`) run in a real APK: Room DAO round-trips and the
  on-device kotlinx.serialization path (a lighter stand-in for full R8/keep-rule validation).
- **Live-endpoint tests** (`LiveEndpointTest`) hit a real Jellyfin and are **opt-in via `.test.env`**:
  copy `.example.test.env` → `.test.env` and fill in the server URL / API key / index URL. They
  **skip automatically** (never fail) when `.test.env` is absent/blank or the server is unreachable,
  so a plain `connectedDebugAndroidTest` on a fresh checkout stays green.

### Environment config

Local config lives in git-ignored `.env`-style files at the repo root, **not** `local.properties`.
Copy the committed `.example.*` templates and fill them in:

| File | Committed? | Purpose |
|------|-----------|---------|
| `.test.env` | git-ignored | Real live-test config: `JELLYFIN_SERVER_URL`, `JELLYFIN_API_KEY`, `JELLYFIN_INDEX_URL`. |
| `.example.test.env` | committed | Template for `.test.env`. |
| `.env` | git-ignored | General local config (none needed yet). |
| `.example.env` | committed | Template for `.env`. |

Gradle's `loadEnv(".test.env")` reads the test config and passes it to the instrumentation tests as
runtime runner arguments (`am instrument -e` extras) — the values are never compiled into any
`BuildConfig`.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

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
