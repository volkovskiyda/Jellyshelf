#!/usr/bin/env bash
# Prints the "Verify before committing" figures from docs/BASELINE-PROFILE.md for a profile pair,
# side by side, so a freshly generated profile can be judged against the table there in one look.
#
# Usage: scripts/baseline-profile-metrics.sh [dir]
#   dir holds baseline-prof.txt and startup-prof.txt; defaults to the committed pair under
#   app/src/release/generated/baselineProfiles. Point it at a kept run (internal/runs/...) to
#   compare an older generation.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

d="${1:-app/src/release/generated/baselineProfiles}"
b="$d/baseline-prof.txt"; s="$d/startup-prof.txt"
[[ -f "$b" && -f "$s" ]] || { echo "ERROR: $d has no baseline-prof.txt + startup-prof.txt pair" >&2; exit 1; }

printf '%-34s %10s %10s\n' "metric" "baseline" "startup"
printf '%-34s %10s %10s\n' "lines (rules)" "$(wc -l <"$b" | tr -d ' ')" "$(wc -l <"$s" | tr -d ' ')"
for pat in 'Lcom/gmail/volkovskiyda' 'ui/player' 'ui/library' 'Landroidx/media3' 'MediaCodec' 'Lokhttp3'; do
  printf '%-34s %10s %10s\n' "grep -c $pat" "$(grep -c "$pat" "$b" || true)" "$(grep -c "$pat" "$s" || true)"
done
printf '%-34s %10s %10s\n' "grep -ci ktor" "$(grep -ci ktor "$b" || true)" "$(grep -ci ktor "$s" || true)"
printf '%-34s %10s %10s\n' "obfuscated-looking (La0; style)" \
  "$(grep -cE '^[A-Z]*L[a-z][0-9]*;' "$b" || true)" "$(grep -cE '^[A-Z]*L[a-z][0-9]*;' "$s" || true)"
if cmp -s "$b" "$s"; then echo "baseline == startup: IDENTICAL (bad)"; else echo "baseline != startup (good)"; fi
printf 'head: %s\n' "$(head -1 "$b")"
