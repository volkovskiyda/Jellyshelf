#!/usr/bin/env bash
#
# make-demo-thumbnails.sh — regenerates the demo library's bundled thumbnails.
#
#   scripts/make-demo-thumbnails.sh app/src/main/assets/demo
#
# Twelve 640x360 cards: a two-tone gradient under an abstract bar figure, one palette and one bar
# pattern each. Deliberately clean-room — everything is drawn by ffmpeg's own `gradients` and
# `drawbox` filters, so no sourced imagery or fonts end up in an open-source repository. The bar
# heights come from an integer sequence rather than a random source, so a regenerated set is
# identical to the committed one.
#
# Rendered as PNG and encoded by cwebp rather than ffmpeg: Homebrew's ffmpeg ships without
# libwebp. Requires ffmpeg and cwebp (`brew install ffmpeg webp`).
#
# The output belongs in plain git, not LFS: the release workflows check out without LFS, and
# pointer files would ship in place of the images.
set -euo pipefail
OUT="${1:?usage: $0 <output-directory>}"
TMP="$(mktemp -d)"
PAIRS=(
  "0x24466e:0x0b1626:0x8fb6de" "0x4a3468:0x140d20:0xc0a5e0" "0x18564b:0x061815:0x86d5c3"
  "0x6b3d14:0x1d1005:0xe3b183" "0x35471f:0x0f1409:0xa8c98a" "0x5a2635:0x18090e:0xdb9aa8"
  "0x1f4a5c:0x081418:0x8ecbdd" "0x474716:0x141406:0xcfcf8a" "0x372561:0x0e0a19:0xb2a1e6"
  "0x164d33:0x06150e:0x8bd8ae" "0x5e4726:0x1a1309:0xd9bd8c" "0x2a3d59:0x0b1018:0x9db5d4"
)
for i in "${!PAIRS[@]}"; do
  n=$(printf '%02d' $((i + 1)))
  IFS=: read -r top bottom accent <<< "${PAIRS[$i]}"
  # 16 bars whose heights come from a per-image integer sequence — deterministic, so a
  # regenerated set is byte-comparable, and visibly different between images.
  bars=""
  for b in $(seq 0 15); do
    h=$(( 40 + ((b * 37 + (i + 1) * 53) % 200) ))
    x=$(( 48 + b * 34 ))
    y=$(( 300 - h ))
    alpha=$(awk -v k="$(( (b + i) % 3 ))" 'BEGIN { printf "%.2f", 0.30 + k * 0.16 }')
    bars="${bars},drawbox=x=${x}:y=${y}:w=20:h=${h}:color=${accent}@${alpha}:t=fill"
  done
  ffmpeg -loglevel error -y \
    -f lavfi -i "gradients=s=640x360:c0=${top}:c1=${bottom}:x0=0:y0=0:x1=640:y1=360:d=1" \
    -frames:v 1 -vf "format=rgb24${bars}" "${TMP}/thumb_${n}.png"
  cwebp -quiet -q 76 "${TMP}/thumb_${n}.png" -o "${OUT}/thumb_${n}.webp"
done
rm -rf "$TMP"
