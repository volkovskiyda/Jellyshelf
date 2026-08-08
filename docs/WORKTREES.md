# Parallel checkouts with git worktrees

Two features in flight at once, without stashing and without a full rebuild on every branch switch.
A worktree is a second working directory backed by the same `.git`, so branches, remotes and
history are shared while the files, `build/` and `.gradle/` are not.

```bash
scripts/worktree.sh add <branch> [--from <ref>] [--device <serial>]
scripts/worktree.sh list
scripts/worktree.sh rm <branch> [--delete-branch] [--force]
```

The script exists because `git worktree add` copies **tracked files and nothing else**, and this
repo needs six ignored paths to build, sign and test. Creating a worktree by hand leaves you with a
checkout that cannot find the SDK, cannot sign a release, and — worst of the three — reports a
green test run having never contacted the server.

## What `add` does

```bash
scripts/worktree.sh add player-fixes --device 192.168.11.111:5555
```

1. Creates `../Jellyshelf-worktrees/player-fixes`, a **sibling** of the repo. Never inside it:
   anything under the checkout is walked by Gradle's file watching and Studio's indexer, and can be
   reached by a stray `git add -A`. Branch names with slashes are flattened (`feat/x` → `feat-x`),
   so one branch is always one directory.
2. Checks out the branch, creating it from `--from` (default: whatever the main checkout is on)
   when it does not exist. Defaulting to `main` instead would silently discard the base you were
   standing on when you asked.
3. **Symlinks** the shared files, listed below.
4. Adds `.device` and `.envrc` to `.git/info/exclude`, which is shared by every worktree.
5. Writes `.device` if `--device` was passed.

### The shared files

| Path | Absent means |
|---|---|
| `local.properties` | the build fails outright — no SDK location |
| `keystore.properties` | `scripts/release.sh` fails loudly |
| `jellyshelf-release.jks` | as above; the keystore exists on disk once, not once per worktree |
| `.env` | base64 backup of the three above (`internal/backup-secrets.sh`) |
| `.test.env` | **nothing fails** — see below |
| `internal/` | plan folders, notes, screenshots |

`.test.env` is the dangerous one. The live tests *skip themselves* when it is missing rather than
failing, so a worktree without it runs the whole suite green having never touched Jellyfin. Nothing
warns you at the point of the pass. `add` reports it as `MISSING` and says so explicitly; `list`
keeps reporting it.

Symlinks rather than copies, so there is one source of truth: rotating a credential or editing a
plan doc lands in every worktree at once. `internal/` has a second reason — plan folders carry
status trackers that `/plan-implement` writes to, and two divergent copies means half the ticked
items go missing. The cost is that a worktree cannot point at a *different* Jellyfin or SDK.
Replace the link with a real file in that one worktree if you ever need that.

## `list`

```
/Users/wolf/Develop/AndroidStudioProjects/Jellyshelf  [main]
/Users/wolf/.../Jellyshelf-worktrees/player-fixes  [player-fixes]
    TESTING NOW — holds the device lock (pid 48120)
    device 192.168.11.111:5555
/Users/wolf/.../Jellyshelf-worktrees/search-tweaks  [search-tweaks]
    device —   MISSING: .test.env
```

Broken and missing links both show as `MISSING` — `-e` follows the link, so a dangling one (the
main checkout moved, a secret was deleted) is not mistaken for a healthy one.

## One device run at a time

The instrumented layer is serialised across worktrees, and the reason is the **server**, not the
phone: `LiveUiJourneyTest` signs into the same Jellyfin as the same user and drives the same item.
Two of them overlapping has already cost a run a 401 mid-journey. Giving each worktree its own
device does not help — so a second device is worth buying for form-factor coverage and for nothing
else here.

`run-tests.sh` takes a lock in the git common dir, shared by every worktree. A second run that
finds it held **waits**, and asks nothing:

```
BUSY:    another test run is already driving a device:
           worktree /Users/wolf/.../Jellyshelf-worktrees/player-fixes
           device   192.168.11.111:5555
           started  2026-08-08 20:31:04  (pid 48120)
         Running both at once means two LiveUiJourneyTests on one Jellyfin account.
         Waiting for it to finish. Ctrl-C to give up, --concurrent to skip the wait.
         still waiting (30s)
         device free after 2m15s — starting
```

The wait costs less than it looks. The lock is claimed **just before the device layer**, so detekt,
the unit tests and the goldens have already run and reported by the time anything blocks — the wait
is device time, not your time. Ctrl-C during it exits cleanly and the summary still prints what ran.

- **Interactive**: waits indefinitely. There is someone to interrupt.
- **No terminal** (CI, `| tee`, a backgrounded run): waits 15 minutes, then runs anyway with a
  warning. A wait that never ends is a hung job, which is the worse failure of the two.
- **`--concurrent`**: does not wait. For runs that cannot collide — no `.test.env`, or a
  `-Pandroid.testInstrumentationRunnerArguments.class=…` filter that excludes the live tests. It
  still *takes* the lock when free, so a later run queues behind it rather than piling up a third.

A holder killed with `-9`, or lost to a power cut, leaves the lock behind; the next run notices the
dead pid and clears it. `list` reads the same lock, so you can check who has the device before
starting anything.

## `.device`

One line — a serial, or the word `all`. It is the standing answer to run-tests.sh's "which device"
question, which otherwise appears on every run in every worktree once you have more than one device
attached. A flag still wins, since it answers for that run specifically.

Unlike `--device`, a `.device` naming something unattached is a **warning, not an error**: standing
config outlives a booted emulator, so the run falls back to the normal choice instead of refusing
to test. It is git-ignored twice over — `.gitignore` documents it for anyone reading the repo, and
`.git/info/exclude` is what actually holds in a worktree branched from before that commit.

## Costs, and the emulator question

**One emulator per feature buys nothing.** Every worktree builds the same `…jellyshelf.debug`
application id, so parallel installs overwrite each other on a shared device — and the obvious fix,
a per-worktree `applicationIdSuffix`, *fails the build*: `google-services.json` has exactly three
clients and AGP hard-fails with "No matching client found" for a fourth. Combined with the server
being the real bottleneck, a second AVD earns its keep only for form-factor coverage (a tablet, an
API 30 device), not for parallelism.

**RAM is the binding constraint on parallel *building*.** Each concurrent Gradle build forks its own
daemon at 4 GB plus a 2 GB Kotlin daemon; each emulator is another 2–3 GB. On a 16 GB machine, two
worktrees building at once with an emulator up is at the wall, and three is not possible. Sequential
builds reuse one daemon — so parallel *editing* is cheap and parallel *building* is not. `build/`
and `.gradle/` are per-directory by construction, about 1.9 GB per worktree once built.

`.idea/` is per-worktree too: open each one as its own Studio project.

## Removing one

```bash
scripts/worktree.sh rm player-fixes [--delete-branch]
```

git's own dirty check is the gate — uncommitted work in a worktree cannot be thrown away by a
typo'd branch name. `--force` forwards to git rather than reimplementing the check.
`--delete-branch` uses `git branch -d`, never `-D`: an unmerged branch survives a worktree teardown
and has to be deleted by hand if that is really what you meant.
