#!/usr/bin/env bash
set -euo pipefail

# build-library-index.sh
#
# Aggregates every yt-dlp "*.info.json" sidecar under a directory into a single
# flat "jellyshelf-index.json" array that the Jellyshelf Android app fetches over
# HTTP and joins to Jellyfin items by YouTube id.
#
# Serve the output from any static file server reachable by the phone, then set
# its URL as the "Metadata index URL" in the app's Settings.
#
# Requires: jq.

DIR="${1:-.}"
OUT="${2:-jellyshelf-index.json}"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq not found on PATH." >&2; exit 1; }
[[ -d "$DIR" ]] || { echo "ERROR: not a directory: $DIR" >&2; exit 1; }

count=$(find "$DIR" -type f -name '*.info.json' | wc -l | tr -d ' ')
if [[ "$count" -eq 0 ]]; then
  echo "No .info.json files found under $DIR. Run fetch-youtube-metadata.sh first." >&2
  exit 1
fi

find "$DIR" -type f -name '*.info.json' -exec cat {} + \
  | jq -s 'map({
      id:          .id,
      title:       .title,
      channel:     (.channel // .uploader),
      channelId:   (.channel_id // .uploader_id),
      duration:    ((.duration // 0) | floor),
      uploadDate:  .upload_date,
      tags:        (.tags // []),
      categories:  (.categories // []),
      description: .description,
      thumbnail:   .thumbnail,
      fetchedAt:   .epoch
    })' > "$OUT"

echo "Wrote $OUT with $(jq 'length' "$OUT") entries (from $count sidecar files)."
