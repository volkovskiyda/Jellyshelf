#!/usr/bin/env bash
# Base64 backup/restore of the repo's git-ignored secret files, to and from .env.
#
# It handles only git-ignored files and embeds no secrets itself — the file and secret names it
# carries are the ones docs/RELEASING.md already publishes — so it lives beside restore-signing.sh,
# the CI-side half that rebuilds the same files from the same base64.
#
# .env is a CONVENIENCE copy, not a backup: it sits inside the repo folder, so deleting the
# project deletes it. Keep the real keystore backup off-repo. And base64 is encoding, not
# encryption — .env is exactly as sensitive as the keystore password and the Jellyfin test-user
# password that .test.env carries.
#
#   ./scripts/backup-secrets.sh backup           # three files -> .env
#   ./scripts/backup-secrets.sh restore [--force]  # .env -> three files
#   ./scripts/backup-secrets.sh verify           # compare .env against the files on disk
#
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ENV_FILE=.env

# key:file — the keys match the CI secret names (KEYSTORE_* in docs/RELEASING.md)
# (KEYSTORE_* exist as repo secrets today; TEST_ENV_BASE64 is reserved, no workflow reads it),
# so .env doubles as the source for `gh secret set`.
PAIRS=(
    "TEST_ENV_BASE64:.test.env"
    "KEYSTORE_PROPERTIES_BASE64:keystore.properties"
    "KEYSTORE_BASE64:jellyshelf-release.jks"
    "KOTZILLA_JSON_BASE64:app/kotzilla.json"
)

# `base64 -i` is macOS-only and GNU base64 wraps at 76 columns, so read from stdin and strip
# newlines by hand: one secret must be one line for both grep and `gh secret set`.
encode() { base64 <"$1" | tr -d '\n'; }
digest() { shasum -a 256 | cut -d' ' -f1; }

value_of() { grep "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }

backup() {
    local missing=()
    for pair in "${PAIRS[@]}"; do
        [[ -f "${pair#*:}" ]] || missing+=("${pair#*:}")
    done
    if ((${#missing[@]})); then
        echo "missing, nothing written: ${missing[*]}" >&2
        exit 1
    fi

    {
        echo '# Base64 backup of the git-ignored secret files (repo root).'
        echo '# NOT encryption — base64 is trivially reversible. Treat this file as the secrets themselves.'
        echo '# Written by scripts/backup-secrets.sh — run `./scripts/backup-secrets.sh restore` to undo.'
        echo '# Source files: .test.env, keystore.properties, jellyshelf-release.jks, app/kotzilla.json'
        echo
        for pair in "${PAIRS[@]}"; do
            printf '%s=%s\n' "${pair%%:*}" "$(encode "${pair#*:}")"
        done
    } >"$ENV_FILE"
    chmod 600 "$ENV_FILE"

    echo "wrote $ENV_FILE ($(wc -c <"$ENV_FILE" | tr -d ' ') bytes)"
    verify
}

restore() {
    local force=${1:-}
    [[ -f "$ENV_FILE" ]] || {
        echo "no $ENV_FILE to restore from" >&2
        exit 1
    }

    for pair in "${PAIRS[@]}"; do
        local key=${pair%%:*} file=${pair#*:} value
        value=$(value_of "$key")
        if [[ -z $value ]]; then
            echo "skip $file — no $key in $ENV_FILE" >&2
            continue
        fi
        if [[ -f $file && $force != "--force" ]]; then
            echo "skip $file — already exists (pass --force to overwrite)" >&2
            continue
        fi
        printf '%s' "$value" | base64 -d >"$file"
        chmod 600 "$file"
        echo "restored $file"
    done
}

verify() {
    local status=0
    for pair in "${PAIRS[@]}"; do
        local key=${pair%%:*} file=${pair#*:} value
        value=$(value_of "$key" 2>/dev/null || true)
        if [[ -z $value ]]; then
            echo "MISSING $key in $ENV_FILE"
            status=1
        elif [[ ! -f $file ]]; then
            echo "MISSING $file on disk (but $key is in $ENV_FILE)"
            status=1
        elif [[ "$(digest <"$file")" == "$(printf '%s' "$value" | base64 -d | digest)" ]]; then
            echo "OK      $key -> $file"
        else
            echo "DIFFERS $key -> $file — .env is stale, re-run backup"
            status=1
        fi
    done
    return $status
}

case "${1:-}" in
    backup) backup ;;
    restore) restore "${2:-}" ;;
    verify) verify ;;
    *)
        echo "usage: $0 {backup|restore [--force]|verify}" >&2
        exit 2
        ;;
esac
