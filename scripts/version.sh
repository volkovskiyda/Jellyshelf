#!/usr/bin/env bash
# Prints the version a published build carries, computed from git — nothing is edited per release.
# Both workflows and scripts/run-tests.sh call this, so the rule lives in one place:
#
#   scripts/version.sh code         versionCode: the commit count, `git rev-list --count HEAD`
#   scripts/version.sh name [tag]   versionName: <tag without its v>.<commits since the tag>
#
# The two count different things on purpose. versionCode never resets, so a build from either
# channel can never install backwards over one from the other, and it is what the in-app update
# check compares. versionName starts again at 0 on every release — v1.14 builds as 1.14.0 and the
# commit after it as 1.14.1 — so it reads as "how far past which release" and is for people only.
#
# The tag defaults to the latest one reachable from HEAD. release.yml passes the tag it was pushed
# for instead, so the build names the release being cut even if the commit carries a second tag.
set -euo pipefail

usage="usage: scripts/version.sh code | scripts/version.sh name [tag]"

case "${1:-}" in
  code)
    git rev-list --count HEAD
    ;;
  name)
    tag="${2:-$(git describe --tags --abbrev=0)}"
    # Two parts only: the build adds the third. A v1.14.1 tag would build as 1.14.1.0, so it fails
    # here rather than shipping a four-part version.
    if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+$ ]]; then
      echo "Tag '$tag' is not v<major>.<minor> — the build adds the patch (commits since the tag)." >&2
      exit 1
    fi
    # Assigned rather than inlined into the echo: a failing substitution inside an argument does not
    # trip `set -e`, and an unknown tag would print "1.14." and exit 0.
    distance="$(git rev-list --count "$tag"..HEAD)"
    echo "${tag#v}.$distance"
    ;;
  *)
    echo "$usage" >&2
    exit 1
    ;;
esac
