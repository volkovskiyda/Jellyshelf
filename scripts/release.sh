#!/usr/bin/env bash
# Cuts a release: asks for the version name, tags main as v<name>, pushes the tag. The Release
# workflow does everything else — builds, signs, and publishes the APK and its R8 mapping.
#
# There is nothing to edit in build.gradle.kts: scripts/version.sh derives both numbers from git.
# versionCode is the commit count; versionName is the tag plus the commits since it, so the release
# itself reports <name>.0 (v1.14 at commit 538 reports 1.14.0, versionCode 538). Single-branch by
# design — no develop, no merge flow.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

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
echo "versionCode:   $(scripts/version.sh code)  (computed, not entered)"
read -rp "New version name, <major>.<minor> (without the leading v): " version

if [[ -z "$version" ]]; then
  echo "No version given." >&2
  exit 1
fi

# The build adds the third part itself — the commits since the tag, 0 for the release — so a
# three-part name would ship as a four-part version. scripts/version.sh refuses such a tag too, but
# only once CI is already running on it.
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+$ ]]; then
  echo "Version names are <major>.<minor>, e.g. 1.15 — the build adds the patch." >&2
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
