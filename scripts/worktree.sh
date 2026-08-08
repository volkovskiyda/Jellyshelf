#!/usr/bin/env bash
set -uo pipefail

# worktree.sh
#
# Creates, lists and removes git worktrees for this repo, so several features can be in flight at
# once without stashing or rebuilding on every branch switch.
#
#   scripts/worktree.sh add <branch> [--from <ref>] [--device <serial>]
#   scripts/worktree.sh list
#   scripts/worktree.sh rm <branch> [--delete-branch] [--force]
#
# Worktrees live in a sibling folder, ../Jellyshelf-worktrees/<branch>, never inside the repo:
# anything under the checkout is something Gradle's file watching and Android Studio's indexer will
# walk, and something a stray `git add -A` can reach. A sibling is invisible to both.
#
# `git worktree add` copies tracked files and nothing else, which is the whole problem this script
# exists to solve. Six ignored paths are what make this repo buildable and testable, and a fresh
# worktree has none of them:
#
#   local.properties        the SDK location — without it the build fails outright
#   keystore.properties     release signing config  ) both needed by scripts/release.sh, and both
#   jellyshelf-release.jks  the release keystore    ) fail loudly when absent
#   .env                    base64 backup of the three above (internal/backup-secrets.sh)
#   .test.env              *fails silently* — see below
#   internal/               plan folders, notes, screenshots
#
# .test.env is the dangerous one. The live tests skip themselves when it is missing rather than
# failing, so a worktree without it runs the whole suite green having never touched the server —
# a pass that means nothing. Nothing warns you. Linking it is the only reliable fix.
#
# They are symlinked, not copied, so there is one source of truth: rotating a credential or editing
# a plan doc lands in every worktree at once, and the release keystore exists on disk once. The cost
# is that a worktree cannot point at a different Jellyfin or a different SDK — replace the link with
# a real file in that one worktree if you ever need that.
#
# internal/ is linked for a second reason beyond convenience: plan folders carry status trackers
# that /plan-implement writes to. Two divergent copies means half the ticked items go missing.
#
# --device writes .device, the standing answer to run-tests.sh's "which device" question. Worth
# setting: the live suite signs into the same Jellyfin as the same user and drives the same item,
# so two worktrees testing at once cost a run a 401 mid-journey. One device per worktree does not
# fix that — the server is the shared resource, not the phone. Pin the serial so the prompt stops
# appearing.
#
# Running the instrumented layer in one worktree at a time is enforced rather than remembered:
# run-tests.sh takes a lock in the git common dir, and a second run waits for it — after its own
# host layers, so the wait costs nothing but the device time. Nothing is asked; Ctrl-C gives up, and
# --concurrent skips the wait. `list` shows who holds it, so you can check before starting.
#
# What is *not* shared, deliberately: build/ and .gradle/ (per-directory by construction, ~1.9 GB
# per worktree once built) and .idea/ (each worktree is its own Studio project). Note that two
# concurrent Gradle builds fork a second daemon at 4 GB heap plus a 2 GB Kotlin daemon, so parallel
# *building* is far more expensive than parallel *editing*. Sequential builds reuse one daemon.

usage() {
  sed -n '/^# worktree.sh$/,/^#   scripts\/worktree.sh rm /p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# Resolved from the git common dir rather than from $0, so the script works when run from inside a
# worktree — where dirname/.. is that worktree, not the checkout holding the ignored files.
GIT_COMMON="$(git rev-parse --git-common-dir 2>/dev/null)" ||
  { echo "ERROR: not a git repository." >&2; exit 2; }
GIT_COMMON="$(cd "$GIT_COMMON" && pwd)"
MAIN="$(dirname "$GIT_COMMON")"
WORKTREE_ROOT="$(dirname "$MAIN")/$(basename "$MAIN")-worktrees"

# Directories get a trailing marker in the listing; otherwise this is just the link set.
SHARED=(local.properties .test.env keystore.properties jellyshelf-release.jks .env internal)

# git worktree accepts branch names with slashes, the filesystem accepts them as nested dirs, and
# the combination makes `rm` ambiguous. Flatten to one directory per branch.
dir_for() { echo "$WORKTREE_ROOT/${1//\//-}"; }

link_shared() {
  local dest="$1" name src linked=() missing=()
  for name in "${SHARED[@]}"; do
    src="$MAIN/$name"
    # A missing source is reported, not created: keystore.properties absent on a machine that never
    # signs a release is normal, and inventing an empty one would turn a clear error into a puzzle.
    [[ -e "$src" ]] || { missing+=("$name"); continue; }
    ln -sfn "$src" "$dest/$name"
    linked+=("$name")
  done
  [[ ${#linked[@]} -gt 0 ]] && printf '  linked   %s\n' "${linked[*]}"
  if [[ ${#missing[@]} -gt 0 ]]; then
    printf '  MISSING  %s (absent in %s)\n' "${missing[*]}" "$MAIN"
    # Named explicitly because this is the failure that looks like a pass.
    for name in "${missing[@]}"; do
      [[ "$name" == ".test.env" ]] &&
        echo "           without .test.env the live tests SKIP — a green run proves nothing"
    done
  fi
}

# .device and .envrc are per-worktree local config that must never be committed. info/exclude lives
# in the common dir, so one write covers every worktree, present and future, without touching the
# tracked .gitignore. .device is in .gitignore too — that entry documents it for anyone reading the
# repo, while this one is what actually holds on a worktree branched from before that commit.
ensure_excluded() {
  local exclude="$GIT_COMMON/info/exclude" entry
  mkdir -p "$(dirname "$exclude")"
  for entry in .device .envrc; do
    grep -qxF "$entry" "$exclude" 2>/dev/null || echo "$entry" >>"$exclude"
  done
}

cmd_add() {
  local branch="" from="" device=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --from) [[ $# -ge 2 ]] || { echo "ERROR: --from needs a ref" >&2; exit 2; }; from="$2"; shift ;;
      --from=*) from="${1#--from=}" ;;
      --device) [[ $# -ge 2 ]] || { echo "ERROR: --device needs a serial" >&2; exit 2; }; device="$2"; shift ;;
      --device=*) device="${1#--device=}" ;;
      -*) echo "ERROR: unknown option: $1" >&2; exit 2 ;;
      *) [[ -z "$branch" ]] || { echo "ERROR: one branch at a time (got '$branch' and '$1')" >&2; exit 2; }
         branch="$1" ;;
    esac
    shift
  done
  [[ -n "$branch" ]] || { echo "ERROR: add needs a branch name" >&2; exit 2; }

  local dest; dest="$(dir_for "$branch")"
  [[ -e "$dest" ]] && { echo "ERROR: $dest already exists." >&2; exit 2; }

  # An existing branch is checked out as-is; a new one branches from --from, defaulting to whatever
  # the main checkout is on. Guessing 'main' instead would silently discard the base the caller was
  # standing on when they asked.
  mkdir -p "$WORKTREE_ROOT"
  if git show-ref --verify --quiet "refs/heads/$branch"; then
    git worktree add "$dest" "$branch" || exit 1
    echo "  branch   $branch (existing)"
  else
    local base="${from:-HEAD}"
    git worktree add -b "$branch" "$dest" "$base" || exit 1
    echo "  branch   $branch (new, from ${from:-$(git -C "$MAIN" rev-parse --abbrev-ref HEAD)})"
  fi

  link_shared "$dest"
  ensure_excluded

  if [[ -n "$device" ]]; then
    echo "$device" >"$dest/.device"
    echo "  device   $device (.device — run-tests.sh will not prompt)"
  fi

  echo
  echo "  cd $dest"
}

cmd_list() {
  local path branch dev name status lock_dir="" lock_pid="" lock_owner=""

  # run-tests.sh's instrumented lock, read-only. Shown here because "which worktree is on the
  # device right now" is exactly what you want to know before starting a run in another one.
  if [[ -f "$GIT_COMMON/jellyshelf-instrumented.lock/info" ]]; then
    lock_pid="$(sed -n 1p "$GIT_COMMON/jellyshelf-instrumented.lock/info")"
    lock_dir="$(sed -n 2p "$GIT_COMMON/jellyshelf-instrumented.lock/info")"
    kill -0 "$lock_pid" 2>/dev/null && lock_owner="$lock_dir" || lock_owner=""
  fi

  # --porcelain is the stable form; the human form's column padding has changed between versions.
  while IFS= read -r line; do
    case "$line" in
      worktree\ *) path="${line#worktree }" ;;
      branch\ *)   branch="${line#branch refs/heads/}" ;;
      detached)    branch="(detached)" ;;
      "")
        [[ -n "${path:-}" ]] || continue
        printf '%s  [%s]\n' "$path" "${branch:-?}"
        # The main checkout is a candidate holder too — it runs tests like any other worktree.
        [[ -n "$lock_owner" && "$path" == "$lock_owner" ]] &&
          printf '    TESTING NOW — holds the device lock (pid %s)\n' "$lock_pid"
        if [[ "$path" != "$MAIN" ]]; then
          dev="$( [[ -f "$path/.device" ]] && tr -d '[:space:]' <"$path/.device" || echo "—" )"
          # Broken links are the expected decay mode: the main checkout moved, or a secret was
          # deleted. -e follows the link, so it is false for both missing-link and dangling-link.
          status=""
          for name in "${SHARED[@]}"; do [[ -e "$path/$name" ]] || status+=" $name"; done
          printf '    device %s' "$dev"
          [[ -n "$status" ]] && printf '   MISSING:%s' "$status"
          printf '\n'
        fi
        path=""; branch="" ;;
    esac
  done < <(git worktree list --porcelain; echo)
}

cmd_rm() {
  local target="" delete_branch=0 force=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --delete-branch) delete_branch=1 ;;
      --force) force=1 ;;
      -*) echo "ERROR: unknown option: $1" >&2; exit 2 ;;
      *) target="$1" ;;
    esac
    shift
  done
  [[ -n "$target" ]] || { echo "ERROR: rm needs a branch name or path" >&2; exit 2; }

  local dest; dest="$(dir_for "$target")"
  [[ -d "$dest" ]] || dest="$target"
  [[ -d "$dest" ]] || { echo "ERROR: no worktree at $dest" >&2; exit 2; }
  [[ "$(cd "$dest" && pwd)" == "$MAIN" ]] && { echo "ERROR: refusing to remove the main checkout." >&2; exit 2; }

  # git's own dirty check is the gate. --force here forwards to it rather than reimplementing it,
  # so uncommitted work in a worktree cannot be thrown away by a typo'd branch name.
  local args=(worktree remove "$dest")
  [[ "$force" -eq 1 ]] && args+=(--force)
  git "${args[@]}" || exit 1
  echo "removed  $dest"

  if [[ "$delete_branch" -eq 1 ]]; then
    # -d, never -D: an unmerged branch should survive a worktree teardown.
    git branch -d "$target" || echo "         branch $target kept (not fully merged — delete it by hand if you meant to)"
  fi
}

case "${1:-}" in
  add)  shift; cmd_add "$@" ;;
  list) shift; cmd_list "$@" ;;
  rm)   shift; cmd_rm "$@" ;;
  -h|--help|"") usage ;;
  *) echo "ERROR: unknown command: $1 (try --help)" >&2; exit 2 ;;
esac
