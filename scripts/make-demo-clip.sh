#!/usr/bin/env bash
#
# make-demo-clip.sh — regenerates app/src/main/assets/demo/sample.mp4, the clip every demo video
# plays.
#
#   scripts/make-demo-clip.sh app/src/main/assets/demo/sample.mp4
#
# Ten seconds of *Big Buck Bunny* (© 2008 Blender Foundation, peach.blender.org), CC-BY 3.0 —
# attribution lives in the README. Sourced from Wikimedia Commons because blender.org's own
# per-resolution download URLs are gone; it is the same film.
#
# H.264 Main / AAC, 720p30, kept under a megabyte: it ships inside the APK, and every demo video
# points at this one file.
#
# Requires ffmpeg and curl. The ~160 MB source is downloaded to a temporary directory and is
# deliberately not committed.
set -euo pipefail
OUT="${1:?usage: $0 <output-file.mp4>}"
SRC_URL="https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.720p.vp9.webm"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Downloading source (~160 MB)…"
curl -fsSL -o "$TMP/source.webm" "$SRC_URL"

# 1:10 is a wide meadow shot with visible motion — a still frame of the intro would make the
# player look frozen.
ffmpeg -loglevel error -y -ss 00:01:10 -t 10 -i "$TMP/source.webm" \
  -vf "scale=1280:720,fps=30" \
  -c:v libx264 -profile:v main -level 3.1 -crf 31 -pix_fmt yuv420p \
  -c:a aac -b:a 64k -ac 2 \
  -movflags +faststart "$OUT"

echo "Wrote $OUT ($(du -h "$OUT" | cut -f1))"
