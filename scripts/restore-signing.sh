#!/usr/bin/env bash
# Recreates the git-ignored build inputs on a CI runner from the base64 secrets, at the paths the
# build reads them from: keystore.properties -> KEYSTORE_FILE -> the jks at the repo root, and
# app/kotzilla.json under app/, where the Kotzilla plugin looks. Used by both delivery workflows:
# ci.yml's distribute job and release.yml.
#
# Fails loudly if any secret is missing rather than shipping a build that is quietly wrong: without
# keystore.properties the signing config is simply not created, and without kotzilla.json the
# Kotzilla plugin disables itself, so the APK would report no sessions and its R8 mapping would
# never be uploaded — leaving every crash from that build unsymbolicated. Both are the right
# behaviour for a PR checkout and the wrong one here.
#
# The name still says "signing" because two workflows and docs/RELEASING.md refer to it; renaming
# would be churn for no gain.
set -euo pipefail

: "${KEYSTORE_PROPERTIES_BASE64:?set the KEYSTORE_PROPERTIES_BASE64 repository secret}"
: "${KEYSTORE_BASE64:?set the KEYSTORE_BASE64 repository secret}"
: "${KOTZILLA_JSON_BASE64:?set the KOTZILLA_JSON_BASE64 repository secret}"

printf '%s' "$KEYSTORE_PROPERTIES_BASE64" | base64 -d > keystore.properties
printf '%s' "$KEYSTORE_BASE64" | base64 -d > jellyshelf-release.jks
printf '%s' "$KOTZILLA_JSON_BASE64" | base64 -d > app/kotzilla.json

echo "Restored keystore.properties, jellyshelf-release.jks and app/kotzilla.json"
