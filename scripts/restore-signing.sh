#!/usr/bin/env bash
# Recreates the git-ignored release-signing inputs on a CI runner from the base64 secrets, at the
# repo-root paths app/build.gradle.kts reads (keystore.properties -> KEYSTORE_FILE -> the jks).
# Used by both delivery workflows: ci.yml's distribute job and release.yml.
#
# Fails loudly if either secret is missing rather than building an unsigned APK and shipping it:
# without keystore.properties the signing config is simply not created, which is the right
# behaviour for a PR checkout but the wrong one here.
set -euo pipefail

: "${KEYSTORE_PROPERTIES_BASE64:?set the KEYSTORE_PROPERTIES_BASE64 repository secret}"
: "${KEYSTORE_BASE64:?set the KEYSTORE_BASE64 repository secret}"

printf '%s' "$KEYSTORE_PROPERTIES_BASE64" | base64 -d > keystore.properties
printf '%s' "$KEYSTORE_BASE64" | base64 -d > jellyshelf-release.jks

echo "Restored keystore.properties and jellyshelf-release.jks"
