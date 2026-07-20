# Jellyshelf

An Android companion for a self-hosted **Jellyfin** library of yt-dlp-archived YouTube
videos. It categorizes the archive (auto-by-channel + your own manual categories) and
syncs watch status with Jellyfin over the REST API. Playback stays in Jellyfin (or any
external player) — Jellyshelf is the catalog + sync layer, not a video player.

`com.gmail.volkovskiyda.jellyshelf` · single-module · Compose + Navigation 3 + Adaptive ·
Room · Retrofit/Moshi · WorkManager · DataStore · lightweight manual DI.

## The data pipeline

```
videos/*.mp4  ──fetch-youtube-metadata.sh──►  *.info.json (sidecars)
*.info.json   ──build-library-index.sh────►  jellyshelf-index.json  ──HTTP──┐
                                                                            ▼
Jellyfin  ──REST (X-Emby-Token)──►  items + watch state  ──join by YouTube id──►  Room  ──►  UI
```

1. **`scripts/fetch-youtube-metadata.sh`** — scans your download dir, extracts the
   11-char YouTube id from each filename, writes a `<video>.info.json` sidecar. Idempotent
   and resumable; `--dry-run` verifies id extraction first.
   ```bash
   scripts/fetch-youtube-metadata.sh --dry-run /media/youtube
   scripts/fetch-youtube-metadata.sh /media/youtube
   ```
2. **`scripts/build-library-index.sh`** — aggregates every `*.info.json` into one
   `jellyshelf-index.json` (id, title, channel, duration, uploadDate, tags, …). Serve it
   from any static file server the phone can reach.
   ```bash
   scripts/build-library-index.sh /media/youtube jellyshelf-index.json
   ```
3. **The app** pulls Jellyfin items (→ Jellyfin ItemId, watch state, duration) and the
   index (→ channel, tags, upload date, description, thumbnail), joins them by YouTube id
   into Room, and auto-groups by channel. The index URL is optional — without it the app
   falls back to Jellyfin's own metadata (no channel grouping).

## Setup in the app (Settings tab)

1. **Server URL** — e.g. `http://192.168.1.10:8096`.
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

The client (`data/remote/JellyfinApi.kt`) targets standard endpoints; confirm these against
your server build and adjust if needed:
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
