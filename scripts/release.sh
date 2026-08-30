#!/usr/bin/env bash
# Cuts a release: asks for the version name, tags main as v<name>, pushes the tag. The Release
# workflow does everything else — builds, signs, and publishes the APK and its R8 mapping.
#
# There is nothing to edit in build.gradle.kts: versionCode is the commit count and versionName is
# the tag plus that count (v1.3 at commit 348 reports 1.3.348). Single-branch by design — no
# develop, no merge flow.
set -euo pipefail

git fetch origin main

if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]]; then
  echo "HEAD is not origin/main. Push your work (and let CI go green) first." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is dirty — commit or stash before tagging." >&2
  exit 1
fi

echo "Last release:  $(git describe --tags --abbrev=0 2>/dev/null || echo '<none>')"
echo "versionCode:   $(git rev-list --count HEAD)  (computed, not entered)"
read -rp "New version name (without the leading v): " version

if [[ -z "$version" ]]; then
  echo "No version given." >&2
  exit 1
fi

if git rev-parse "v$version" >/dev/null 2>&1; then
  echo "Tag v$version already exists." >&2
  exit 1
fi

git tag "v$version"
git push origin "v$version"

echo "Pushed v$version — the Release workflow takes it from here:"
echo "  https://github.com/volkovskiyda/Jellyshelf/actions/workflows/release.yml"
