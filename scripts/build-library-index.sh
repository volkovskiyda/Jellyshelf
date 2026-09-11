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

# Build into a temporary file and move it into place only on success: "$OUT" is
# served live to the app, and a plain redirect truncates it before jq runs, so a
# failed build used to leave the phone fetching an empty index until the next run.
tmp="$(mktemp "${OUT}.XXXXXX")"
trap 'rm -f -- "$tmp"' EXIT

# Stream the sidecars one at a time rather than slurping them all. `jq -s` held
# every sidecar in memory at once — ~500 MiB of JSON became ~1.3 GiB RSS and
# segfaulted the builder on a memory-tight host. With `-n 'inputs'` jq parses,
# maps and frees one file at a time, so peak memory is a single sidecar (~10 MiB)
# no matter how large the library grows. xargs keeps the file list off argv too;
# it may split the list across several jq runs, hence the one-object-per-line
# output that the final `jq -s` collects into the array. Output is unchanged.
printf '%s\0' "${files[@]}" \
  | xargs -0 jq -c -n 'inputs | {
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
    }' \
  | jq -s '.' > "$tmp"

chmod 644 -- "$tmp"
mv -- "$tmp" "$OUT"
trap - EXIT

echo "Wrote $OUT with $(jq 'length' "$OUT") entries (from $count sidecar files)."
