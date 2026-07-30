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

# Single scan, NUL-delimited (filenames with newlines can't break the count) and sorted so
# the generated index is deterministic across runs and filesystems.
files=()
while IFS= read -r -d '' f; do files+=("$f"); done \
  < <(find "$DIR" -type f -name '*.info.json' -print0 | sort -z)
count=${#files[@]}
if [[ "$count" -eq 0 ]]; then
  echo "No .info.json files found under $DIR. Run fetch-youtube-metadata.sh first." >&2
  exit 1
fi

cat "${files[@]}" \
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
      chapters:    ((.chapters // []) | map({start: .start_time, title: .title})),
      fetchedAt:   .epoch
    })' > "$OUT"

echo "Wrote $OUT with $(jq 'length' "$OUT") entries (from $count sidecar files)."
