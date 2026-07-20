#!/usr/bin/env bash
set -euo pipefail

# fetch-youtube-metadata.sh
#
# Scans a directory of yt-dlp-downloaded videos, extracts the 11-char YouTube ID
# from each filename, and writes a sidecar "<basename>.info.json" next to the
# video (same stem, so the Android app can pair them 1:1).
#
# - Idempotent: skips any video that already has a matching .info.json.
# - Resumable: safe to Ctrl-C and re-run.
# - Verifiable: --dry-run prints the filename -> ID mapping and fetches nothing.
#
# Requires: yt-dlp on PATH.

DIR="."
DRY_RUN=0
SLEEP=2
# Video extensions to consider (space separated).
EXTS="mp4 mkv webm m4v mov avi flv"

usage() {
  cat <<EOF
Usage: $0 [options] [DIR]

  DIR                directory to scan, recurses (default: current dir)
  --dry-run          print "filename -> ID" for each video, fetch nothing
  --sleep N          seconds between requests, throttles YouTube (default: 2)
  --exts "a b c"     override video extensions (default: "$EXTS")
  -h, --help         show this help

Pass extra yt-dlp flags via the YTDLP_OPTS env var, e.g. for age/region-gated:
  YTDLP_OPTS="--cookies-from-browser firefox" $0 /media/youtube
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --sleep)   SLEEP="$2"; shift 2 ;;
    --exts)    EXTS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*)        echo "Unknown option: $1" >&2; usage; exit 1 ;;
    *)         DIR="$1"; shift ;;
  esac
done

if ! command -v yt-dlp >/dev/null 2>&1 && [[ "$DRY_RUN" -eq 0 ]]; then
  echo "ERROR: yt-dlp not found on PATH." >&2
  exit 1
fi

[[ -d "$DIR" ]] || { echo "ERROR: not a directory: $DIR" >&2; exit 1; }

FAIL_LOG="$DIR/metadata-failures.log"
: > "$FAIL_LOG"

# Extract the YouTube ID from a filename.
# Prefers the last [XXXXXXXXXXX] bracketed group (yt-dlp default naming);
# falls back to the last standalone 11-char token in the stem.
extract_id() {
  local name="$1" id=""
  id="$(grep -oE '\[[A-Za-z0-9_-]{11}\]' <<<"$name" | tail -1 | tr -d '[]')"
  if [[ -z "$id" ]]; then
    id="$(grep -oE '[A-Za-z0-9_-]{11}' <<<"${name%.*}" | tail -1)"
  fi
  printf '%s' "$id"
}

# Build the find expression for the requested extensions.
find_args=()
for ext in $EXTS; do
  find_args+=(-o -iname "*.${ext}")
done
# Drop the leading "-o".
find_args=("${find_args[@]:1}")

total=0 skipped=0 fetched=0 failed=0 noid=0

while IFS= read -r -d '' file; do
  total=$((total + 1))
  base="$(basename "$file")"
  stem="${file%.*}"
  id="$(extract_id "$base")"

  if [[ -z "$id" ]]; then
    noid=$((noid + 1))
    echo "NO-ID   $base" | tee -a "$FAIL_LOG"
    continue
  fi

  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf '%-13s <- %s\n' "$id" "$base"
    continue
  fi

  if [[ -f "${stem}.info.json" ]]; then
    skipped=$((skipped + 1))
    continue
  fi

  echo "FETCH   $id  ($base)"
  if yt-dlp \
      --skip-download \
      --write-info-json \
      --no-write-playlist-metafiles \
      --ignore-config \
      --sleep-requests "$SLEEP" \
      -o "${stem}.%(ext)s" \
      ${YTDLP_OPTS:-} \
      "https://www.youtube.com/watch?v=${id}" >/dev/null 2>>"$FAIL_LOG"; then
    fetched=$((fetched + 1))
  else
    failed=$((failed + 1))
    echo "FAILED  $id  ($base)" | tee -a "$FAIL_LOG"
  fi
done < <(find "$DIR" -type f \( "${find_args[@]}" \) -print0)

echo
echo "-------- summary --------"
echo "videos found : $total"
if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "(dry run: nothing fetched)"
else
  echo "fetched      : $fetched"
  echo "skipped      : $skipped (already had .info.json)"
  echo "no ID found  : $noid"
  echo "failed       : $failed"
  if [[ "$failed" -gt 0 || "$noid" -gt 0 ]]; then
    echo "see          : $FAIL_LOG"
  fi
fi
